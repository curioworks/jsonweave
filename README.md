# jsonweave 🧵

**Declarative JSON-to-JSON transformation: you write the shape of the output.**

[![CI](https://github.com/curioworks/jsonweave/actions/workflows/ci.yml/badge.svg)](https://github.com/curioworks/jsonweave/actions/workflows/ci.yml)

Most transformation tools ask you to describe where each piece of the *input* should go. Jsonweave asks the opposite question: **what should the output look like?** A spec is a picture of the output document. Every object and array in it is literal output structure, and every value says where it comes from — optionally flowing through stream operations (`filter`, `map`, `sortBy`, `sum`, … like Java Streams) on the way. Reading a spec *is* reading the output.

## A first example

Input:

```json
{
  "user": {"firstName": "Jane", "lastName": "Doe"},
  "orders": [
    {"id": 1, "total": 250, "status": "SHIPPED"},
    {"id": 2, "total": 90,  "status": "CANCELLED"},
    {"id": 3, "total": 120, "status": "SHIPPED"}
  ]
}
```

Spec — shaped exactly like the output you want:

```jsonc
{
  "customerName": "$.user.firstName",     // copy one value from the input
  "shippedOrderIds": {                    // this key will hold an array:
    "#stream": "$.orders",                //   stream the orders...
    "#filter": {"@.status": "SHIPPED"},   //   keep only the shipped ones...
    "#map": "@.id"                        //   and take each one's id
  },
  "spend": {                              // plain objects are just output structure
    "total": {"#stream": "$.orders", "#sum": "@.total"}   // add up each order's total
  }
}
```

Output:

```json
{
  "customerName": "Jane",
  "shippedOrderIds": [1, 3],
  "spend": {"total": 460}
}
```

## Install

Jsonweave is on Maven Central. The core engine has no dependencies beyond Jackson:

```xml
<dependency>
  <groupId>io.github.curioworks</groupId>
  <artifactId>jsonweave-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

```groovy
// Gradle
implementation 'io.github.curioworks:jsonweave-core:0.1.0'
```

The two expression engines are optional add-ons — pull one in only if your specs use `#mvel` or `#js` (see [Expressions](#expressions)):

```xml
<!-- MVEL: the JVM performance dialect -->
<dependency>
  <groupId>io.github.curioworks</groupId>
  <artifactId>jsonweave-mvel</artifactId>
  <version>0.1.0</version>
</dependency>

<!-- JS: the portable dialect (GraalJS, sandboxed) -->
<dependency>
  <groupId>io.github.curioworks</groupId>
  <artifactId>jsonweave-js</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
JsonNode output = Jsonweave.compile(spec).transform(input);
```

## How to read a spec

### The symbols

Data and operations never share a symbol:

| Symbol | Meaning | Example |
|---|---|---|
| `$.` | data from the **input document root** (`$` alone is the whole input) | `$.user.firstName` |
| `@.` | data from the **current element**, inside per-element scopes like `#map` (`@` alone is the element itself) | `@.id` |
| `#` | an **operation** — never data | `#stream`, `#filter`, `#sum` |
| `$name` | a **variable** declared with `#let` (`$` + name = variable; `$.` = input root) | `$shipped` |

Everything else is a literal and passes through verbatim: `"hello"`, `"$100"`, `"user@example.com"`, numbers, booleans, `null`, and any object or array without `#` keys.

### Operations and their arguments

An operation is a JSON key starting with `#`, and **the key's value is the operation's argument**:

```jsonc
{"#upper": "$.name"}         // argument = which value to uppercase
{"#count": "$.shipments"}    // argument = which data to count
{"#limit": 3}                // argument = how many elements to keep
{"#sum": "@.total"}          // inside a pipeline: which field of each element to add up
```

Arguments come in three shapes:

1. **The data to operate on** — a path, a literal, or a nested operation, evaluated like any other spec value. Value functions (`#upper`, `#concat`, `#lookup`, …) work this way, and so do stream terminals used *outside* a pipeline:

   ```jsonc
   {"#count": "$.shipments"}      // how many shipments (an array counts its elements)
   {"#sum": "$.nums"}             // add up the numbers in the array
   {"#first": "$.events"}         // the first event
   {"#distinct": "$.tags"}        // the tags, de-duplicated
   ```

2. **An element reference** — *inside a pipeline*, operations that examine each element (`#sum`, `#min`, `#max`, `#avg`, `#sortBy`, `#groupBy`, and the keys of match objects) take an `@`-anchored path:

   ```jsonc
   {"#stream": "$.orders", "#sum": "@.total"}   // for each order, read its total, add them up
   {"#stream": "$.nums",   "#sum": "@"}         // "@" = the elements themselves (a stream of numbers)
   ```

   There is no bare shorthand: `"total"` instead of `"@.total"` is a compile error with a fix-it hint. **Every data reference in a spec starts with `$` or `@` — no exceptions.**

3. **`"@"` as "the elements themselves"** — mid-pipeline, some operations have nothing left to select (`#count`, `#first`, `#last`, `#distinct`, `#reverse`): the source was already named by `#stream`, so their argument is simply `"@"`, the stream itself. Anything else is a compile-time error carrying the correct spelling, so a wrong guess can't slip through silently:

   ```jsonc
   {"#stream": "$.orders", "#filter": {"@.vip": true}, "#count": "@"}   // count what survived the filter
   ```

A few operations take an options object instead, e.g. `{"#replace": {"#in": "$.sku", "#find": "-", "#with": "_"}}` or `{"#toObject": {"#key": "@.sku", "#value": "@.price"}}`.

One guard-rail: in *value positions* (`#map` templates, output objects) a bare string is a **literal**, so writing `"#map": "id"` when you meant `"#map": "@.id"` would silently produce the constant `"id"` — Jsonweave therefore rejects it at compile time with a fix-it hint.

## Pipelines — streams in JSON

`#stream` turns an array into a stream (a scalar or object becomes a one-element stream; a nested pipeline or `#entries` also works as a source). Stages run **in the order you declare them**, fused into a single lazy pass:

```jsonc
{
  "top3": {
    "#stream": "$.orders",                              // source
    "#filter": {"@.total": {"#gt": 100}},               // keep orders over 100
    "#sortBy": {"#path": "@.total", "#order": "desc"},  // biggest first
    "#limit": 3,                                        // just the top three
    "#map": {"id": "@.id", "amount": "@.total"}         // reshape each element
  }
}
```

| Stage | Argument | Does |
|---|---|---|
| `#filter` | [match object](#match-objects) | keeps matching elements |
| `#map` | template, or a path like `"@.id"` | transforms each element (missing results are dropped) |
| `#flatMap` | template, or a path like `"@.tags"`, or `"@"` | maps then flattens one level (`"@"` = flatten the elements) |
| `#sortBy` | element path (`"@.total"`), `{"#path", "#order"}`, an array of those for multi-key, or `"@"` | sorts (missing keys last) |
| `#distinct` | `"@"` | removes duplicates (numeric-aware: `1` ≡ `1.0`) |
| `#skip` / `#limit` | count | pagination |
| `#reverse` | `"@"` | reverses order |

A pipeline ends with at most one **terminal**; without one, the result is the surviving array:

| Terminal | Argument | Returns |
|---|---|---|
| `#first` / `#last` | `"@"` | one element (missing when the stream is empty) |
| `#count` | `"@"` | number of elements |
| `#sum` / `#min` / `#max` / `#avg` | element path (`"@.total"`), or `"@"` | aggregate of the extracted values (`#sum`/`#avg` skip non-numeric) |
| `#join` | separator string | concatenated string |
| `#groupBy` | element path (`"@.status"`) | object of arrays, keyed by the extracted value |
| `#anyMatch` / `#allMatch` / `#noneMatch` | match object | boolean |
| `#toObject` | `{"#key": ..., "#value": ...}` per-element expressions | object with **data-driven keys** |

Short-circuiting terminals (`#first`, `#anyMatch`, …) stop consuming the source early.

When you aren't filtering or mapping anything, skip the pipeline entirely — terminals accept their data directly: `{"#count": "$.shipments"}`, `{"#sum": "$.nums"}`, `{"#last": "$.events"}` (works for `#count`, `#first`, `#last`, `#distinct`, `#reverse`, `#sum`, `#min`, `#max`, `#avg`; aggregates then operate on the elements themselves).

### Match objects

Pure-JSON predicates (Mongo-style) — used by `#filter`, `#if` and the `#*Match` terminals:

```jsonc
{"@.status": "SHIPPED"}                                 // equality (numeric-aware)
{"@.total": {"#gt": 100, "#lte": 500}}                  // #eq #ne #gt #gte #lt #lte
{"@.tier": {"#in": ["GOLD", "SILVER"]}}                 // #in #nin #exists
{"#or": [{"@.vip": true}, {"@.total": {"#gt": 900}}]}   // #and #or #not
{"@.customer.tier": "GOLD"}                             // dots reach deeper into the element
{"@": {"#gt": 10}}                                      // @ tests the element itself
{"@.status": "$.wantedStatus"}                          // right-hand sides are expressions too
{"$.flag": true}                                        // $.-anchored keys test root values
```

Missing fields: `#eq`/`#gt`/… never match, `#ne`/`#nin` do, `#exists` tests presence explicitly. JSON `null` in the input is a present value, not a missing one.

## Missing data and defaults

A path that resolves to nothing simply **omits its output key** — output stays clean without null noise. When you want a fallback instead, `#default` works on any operation, including whole pipelines:

```jsonc
{"#get": "$.nickname", "#default": "$.firstName"}          // fetch with fallback
{"#stream": "$.orders", "#first": "@", "#default": null}    // empty stream → null instead of omitted
```

`{"#literal": ...}` emits anything verbatim (the escape hatch for strings that look like paths); an output key that must literally start with `#` is written `"\\#key"`.

## Value functions

Usable anywhere a value is expected, and they compose:

| Operation | Shape |
|---|---|
| `#concat` | `{"#concat": ["$.first", " ", "$.last"]}` — missing/null parts vanish |
| `#upper` `#lower` `#trim` | `{"#upper": "$.name"}` |
| `#replace` | `{"#replace": {"#in": "$.sku", "#find": "-", "#with": "_"}}` (literal, not regex) |
| `#substring` | `{"#substring": {"#of": "$.s", "#start": 0, "#end": 4}}` — clamps, negatives count from the end |
| `#toString` `#toNumber` | coercion; unparsable `#toNumber` is missing (pair with `#default`) |
| `#if` | `{"#if": {"$.age": {"#gte": 18}}, "#then": "adult", "#else": "minor"}` |
| `#lookup` | `{"#lookup": {"1": "ACTIVE"}, "#on": "$.status", "#default": "UNKNOWN"}` — the table may also be a path |

```jsonc
{"names": {"#stream": "$.users",
           "#map": {"#upper": {"#concat": ["@.first", " ", "@.last"]}}}}   // → ["JANE DOE", ...]
```

## Dynamic keys and merging

When the *output keys themselves* come from data:

```jsonc
// [{"sku": "SKU1", "price": 9.99}, ...]  →  {"SKU1": 9.99, ...}
{"priceBySku": {"#stream": "$.products",
                "#toObject": {"#key": "@.sku", "#value": "@.price"}}}

// re-key an object: object → #entries → #toObject (extractors are full expressions)
{"upper": {"#stream": {"#entries": "$.prices"},
           "#toObject": {"#key": {"#upper": "@.key"}, "#value": "@.value"}}}

// {"jane": 91, "raj": 45}  →  ["jane"]     — #entries streams an object as {key, value} pairs
{"passed": {"#stream": {"#entries": "$.scores"},
            "#filter": {"@.value": {"#gte": 60}},
            "#map": "@.key"}}
```

`#spread` copies a source object's fields into the output object — "pass everything through, tweak a few fields":

```jsonc
{"#spread": "$",                            // all input fields
 "price": {"#toString": "$.price"},         // …override one
 "audited": true}                           // …add one
```

Later keys win, in declared order; `#spread` also accepts an array of sources.

## Variables: compute once, use everywhere

`#` operations *do* things; `$name` variables *remember* things. When you need the same intermediate result in several places, compute it once at the spec root with `#let` and name it — otherwise you would paste the same pipeline into every key (and pay for it on every use):

```jsonc
{
  "#let": {                                            // evaluated once, before the output is built
    "shipped": {"#stream": "$.orders", "#filter": {"@.status": "SHIPPED"}}
  },
  "count":   {"#stream": "$shipped", "#count": "@"},    // then read the variable...
  "ids":     {"#stream": "$shipped", "#map": "@.id"},    // ...as many times as you like
  "revenue": {"#stream": "$shipped", "#sum": "@.total"}
}
```

Rule of thumb: reach for `#let` the moment you'd write the same pipeline twice. Later variables may reference earlier ones.

**Mind the dot** — the two spellings mean different things:

| | |
|---|---|
| `$shipped` | the *variable* named `shipped` (must be declared in `#let`; a typo is a compile error) |
| `$.shipped` | the *input field* named `shipped` |

## Chaining: multi-step specs

A **top-level array** runs specs in sequence, each step's output becoming the next step's *input document*:

```jsonc
[
  {"shipped": {"#stream": "$.orders", "#filter": {"@.status": "SHIPPED"}}},

  // in step 2 the document IS step 1's output —
  // so "$.shipped" here reads the field that step 1 just produced:
  {"revenue": {"#stream": "$.shipped", "#sum": "@.total"}}
]
```

`{"#chain": [...]}` is the same thing in explicit form. Nested arrays are ordinary array templates — only the top level chains. Each step gets its own `#let` scope.

Use a **chain** when the transformation naturally has phases (normalize, then aggregate); use a **variable** when one result feeds several output keys within a single shape.

## Custom functions

Register Java functions and call them like built-ins:

```java
Transform t = Jsonweave.builder()
        .register("median", (List<JsonNode> input, JsonNode arg) -> {
            // input = the stream contents; arg = the raw spec value of "#median"
            ...
        })
        .compile(spec);
```

```jsonc
{"mid": {"#stream": "$.prices", "#median": true}}   // as a pipeline stage
{"mid": {"#median": "$.prices"}}                  // standalone: the argument supplies the input list
```

A stage returning an array continues the pipeline; anything else ends it. Unknown `#` keys are **compile-time errors** with typo suggestions (`did you mean '#filter'?`).

## Expressions

For logic the built-ins can't express (arithmetic, ternaries, string juggling), specs can embed code. Two dialects ship, with distinct jobs:

| | `#mvel` (`jsonweave-mvel`) | `#js` (`jsonweave-js`) |
|---|---|---|
| Role | **JVM fast path** | **the portable standard** — the only dialect future Node/Python runtimes must implement |
| Java speed | near-native (compiled, reads the document tree directly) | interpreter-mode on stock JDKs (compat path) |
| Sandbox | ⚠️ none — can execute arbitrary Java | sandboxed by default: no host access, no IO |

```java
Transform t = Jsonweave.builder()
        .registerExpressionEngine("mvel", new MvelEngine())   // registration order = dialect preference
        .registerExpressionEngine("js", new JsEngine())
        .compile(spec);
```

```jsonc
{"gross": {"#mvel": "root.price * 1.18"}}                             // value position
{"#stream": "$.orders", "#map": {"net": {"#js": "it.total * 0.9"}}}   // per element
{"#stream": "$.orders", "#filter": {"#mvel": "it.total > 100 && it.status != 'CANCELLED'"}}  // predicate: must return boolean
```

**Bindings**: `root` (the input document), `it` (the current element; the root outside element scopes), and every `#let` variable by its plain name. Engine arguments are *code strings* — the `$.`/`@.` symbols don't apply inside them. Expressions pre-compile at `Jsonweave.compile()`; syntax errors are `SpecException`s with the spec path, `null`/`undefined` results mean *missing* (pair with `#default`).

### Named expression catalogs (`#expr`)

Store expressions separately from specs and reference them by name — one name can carry **both dialects**, and each runtime picks its native one, so the spec stays dialect-free and portable while the JVM stays fast:

```jsonc
// expressions.json — versioned and reviewed like code
{"discount":   {"mvel": "it.total > 100 ? it.total * 0.9 : it.total",
                "js":   "it.total > 100 ? it.total * 0.9 : it.total"},
 "isPriority": {"mvel": "it.tier == 'GOLD' || it.total > 500",
                "js":   "it.tier == 'GOLD' || it.total > 500"}}

// spec — never names a dialect
{"#stream": "$.orders",
 "#filter": {"#expr": "isPriority"},
 "#map": {"id": "@.id", "net": {"#expr": "discount"}}}
```

- Load catalogs via `builder.expressions(catalog)`, the CLI's `--expressions expressions.json`, or the playground's Expressions pane. A spec can also carry its own root-level `#expressions` block (spec-local names shadow external ones).
- Everything resolves at compile time: unknown names get a did-you-mean; an entry with no dialect matching a registered engine is a clear error. A plain-string entry uses the default (first-registered) dialect.
- Portable catalogs provide `js` (the baseline) and optionally `mvel` as JVM acceleration for the same logic — keeping the two dialects in agreement is the author's job.

The pecking order for custom logic: **built-ins first** (fastest and fully portable), `#expr`/`#js` when the spec must travel, `#mvel` when it's JVM-only and hot. Measured (JMH, same filter+map over 1,000 orders, stock JDK 21): built-ins 59 µs · `#mvel` 552 µs · `#js` 2,102 µs — reproduce with `java -jar jsonweave-benchmarks/target/benchmarks.jar ExpressionBenchmark`.

> ⚠️ **Security**: MVEL can execute arbitrary Java (`Runtime.exec` included) — never host the playground publicly with MVEL enabled. Off switches: `JSONWEAVE_MVEL=false` / `JSONWEAVE_JS=false` (playground), `--no-mvel` / `--no-js` (CLI). `#js` is sandboxed (no host access), making it the only dialect fit for untrusted spec authors.

## Using it

```java
// Library (jsonweave-core, only dependency: Jackson)
Transform t = Jsonweave.compile(specJson);   // validate once — SpecException points at $.the.bad.spot
JsonNode out = t.apply(input);               // reuse freely: immutable & thread-safe
```

```bash
# CLI
java -jar jsonweave-cli/target/jsonweave-cli-0.1.0-SNAPSHOT.jar -s spec.json input.json
cat input.json | java -jar ...jsonweave-cli.jar -s spec.json          # stdin → stdout
# exit codes: 0 ok · 1 I/O · 2 bad spec · 3 transform failure
```

```bash
# Playground — three live panes (input, spec, output), examples, shareable permalinks
./mvnw package -DskipTests
java -jar jsonweave-playground/target/jsonweave-playground-*.jar
# → http://localhost:7070
```

### Hosting the playground

A `Dockerfile` is included; the image runs the playground on `$PORT` (default 8080):

```bash
docker build -t jsonweave-playground .
docker run --rm -p 8080:8080 jsonweave-playground
```

`fly.toml` deploys that image to [Fly.io](https://fly.io) (`fly launch --no-deploy --copy-config`, then `fly deploy`); it scales to zero when idle, so a demo instance is free at rest. Any host that runs a container works the same way — set `PORT` and go.

> **Security — read before hosting.** `#mvel` executes arbitrary Java and **cannot** be sandboxed: a public instance with MVEL enabled is a remote-code-execution hole. Both the Dockerfile and `fly.toml` therefore set `JSONWEAVE_MVEL=false`. `#js` runs under GraalJS with host access disabled and stays on. Keep it that way on anything reachable from the internet.

## Performance

Speed falls out of the design. Work is proportional to the **output** you ask for — each value is a handful of pre-compiled path hops, with no scanning of the rest of the input. `compile()` produces a tree of evaluator lambdas (paths pre-parsed, predicates pre-compiled), so `apply()` does zero string parsing; pipelines fuse into one lazy pass and short-circuit. In our benchmark the full declarative pipeline (filter + sort + top-10 + sum over 1,000 elements) runs at the same speed as a hand-written Jackson loop — and 5–8× faster than comparable tools (numbers in the [comparison below](#compared-with-jolt)).

## Compared with Jolt

[Jolt](https://github.com/bazaarvoice/jolt) is the established JSON transformer on the JVM, and it shaped this project — by inversion. Jolt specs mirror the *input*, and every value says where it should *go*; powerful, but you cannot look at a Jolt spec and see what the output will be. A Jsonweave spec **is** the output:

```jsonc
// Jolt (read bottom-up: values are destinations)   // Jsonweave (read top-down: the spec is the output)
[{"operation": "shift",                             {
  "spec": {                                           "firstName": "$.user.first",
    "user": {"first": "firstName"},                   "orderIds": {"#stream": "$.orders",
    "orders": {"*": {"id": "orderIds[]"}}                          "#map": "@.id"}
  }}]                                               }
```

Filtering, sorting, aggregating, conditionals, lookups and variables are first-class here; in Jolt they need custom Java or extra passes. Dynamic output keys (Jolt's `*` wildcard) are covered by `#entries`/`#toObject`.

JMH head-to-head, same transforms, each engine using its natural document representation:

| Benchmark | Jolt | Jsonweave | |
|---|---|---|---|
| flat remapping (5 fields) | 1.43 µs | 0.30 µs | **4.7× faster** |
| nested array restructure (100 orders) | 108.7 µs | 16.5 µs | **6.6× faster** |
| filter+sort+top10+sum (1000 orders) | *(inexpressible in Jolt)* | 134 µs | ≈ hand-written Jackson loop |

Reproduce with `./mvnw -Pbench -pl jsonweave-benchmarks -am package -DskipTests && java -jar jsonweave-benchmarks/target/benchmarks.jar`.

## Building

```bash
./mvnw verify          # build + full test suite (fixtures in jsonweave-core/src/test/resources/fixtures)
```

Requires Java 17+. Modules: `jsonweave-core` (the engine, Jackson-only), `jsonweave-mvel` and `jsonweave-js` (expression dialects), `jsonweave-cli`, `jsonweave-playground`, `jsonweave-benchmarks` (`-Pbench`).

## Roadmap

- **Node and Python runtimes** — same spec format; they implement the built-ins plus the `js` expression dialect (`#mvel` stays JVM-only)
- `#groupBy` downstream pipelines (per-group aggregation)
- regex support in `#replace` / match objects
- arithmetic value ops (`#add`, `#multiply`, …) — pure-JSON, portable alternatives to expressions
- `ServiceLoader` discovery of custom-function and engine packs

## License

[Apache 2.0](LICENSE)
