---
title: Jsonweave Playground
emoji: 🧵
colorFrom: indigo
colorTo: blue
sdk: docker
app_port: 7860
pinned: false
license: apache-2.0
---

# Jsonweave Playground

Live playground for [Jsonweave](https://github.com/curioworks/jsonweave) — a
**destination-driven** JSON-to-JSON transformation library for the JVM. The spec you
write is shaped like the output document, so reading a spec *is* reading the output.

Edit the **input** and **spec** panes and the **output** updates live. Load an example
from the gallery to see pipelines, match objects, dynamic keys and expressions, and use
the permalink button to share any transformation as a URL.

```jsonc
{
  "customerName": "$.user.firstName",
  "shippedOrderIds": {
    "#stream": "$.orders",
    "#filter": {"@.status": "SHIPPED"},
    "#map": "@.id"
  }
}
```

Install it from Maven Central:

```xml
<dependency>
  <groupId>io.github.curioworks</groupId>
  <artifactId>jsonweave-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

## About this instance

`#js` expressions (GraalJS, sandboxed with host access disabled) are enabled.
**`#mvel` is disabled**: it executes arbitrary Java and cannot be sandboxed, so it must
never be enabled on a public instance. Run the playground locally if you want it.
