/* Jsonweave playground front-end: three CodeMirror panes, auto-transform,
 * example gallery, and shareable permalinks (gzip+base64url in the URL hash). */
(function () {
  "use strict";

  const editorConfig = {
    mode: { name: "javascript", json: true },
    theme: "material-darker",
    lineNumbers: true,
    matchBrackets: true,
    tabSize: 2,
  };

  const input = CodeMirror.fromTextArea(document.getElementById("input"), editorConfig);
  const spec = CodeMirror.fromTextArea(document.getElementById("spec"), editorConfig);
  const expressions = CodeMirror.fromTextArea(document.getElementById("expressions"), editorConfig);
  const output = CodeMirror.fromTextArea(document.getElementById("output"),
      Object.assign({}, editorConfig, { readOnly: true }));

  const exprWrap = document.getElementById("exprWrap");

  function showExpressions(show) {
    exprWrap.hidden = !show;
    if (show) {
      setTimeout(() => expressions.refresh(), 0);
    }
  }

  document.getElementById("exprToggle").addEventListener("click", () => showExpressions(exprWrap.hidden));

  const errorBox = document.getElementById("error");
  const runButton = document.getElementById("run");
  const autorun = document.getElementById("autorun");
  const examplesSelect = document.getElementById("examples");

  let examples = [];
  let debounceTimer = null;

  // ------------------------------------------------------------ transform

  async function transform() {
    let inputJson, specJson, expressionsJson = null;
    try {
      inputJson = JSON.parse(input.getValue());
    } catch (e) {
      return showError("Input pane is not valid JSON: " + e.message);
    }
    try {
      specJson = JSON.parse(spec.getValue());
    } catch (e) {
      return showError("Spec pane is not valid JSON: " + e.message);
    }
    const exprText = expressions.getValue().trim();
    if (exprText) {
      try {
        expressionsJson = JSON.parse(exprText);
      } catch (e) {
        return showError("Expressions pane is not valid JSON: " + e.message);
      }
    }
    try {
      const body = { input: inputJson, spec: specJson };
      if (expressionsJson) body.expressions = expressionsJson;
      const res = await fetch("api/transform", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const body = await res.json();
      if (!res.ok) {
        const phase = body.phase === "apply" ? "runtime error" : "spec error";
        return showError(phase + ": " + (body.error || "unknown"));
      }
      clearError();
      output.setValue(JSON.stringify(body.output, null, 2));
    } catch (e) {
      showError("Cannot reach the playground server: " + e.message);
    }
  }

  function showError(message) {
    errorBox.textContent = message;
    errorBox.hidden = false;
  }

  function clearError() {
    errorBox.hidden = true;
  }

  function scheduleTransform() {
    if (!autorun.checked) return;
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(transform, 400);
  }

  input.on("change", scheduleTransform);
  spec.on("change", scheduleTransform);
  runButton.addEventListener("click", transform);
  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
      e.preventDefault();
      transform();
    }
  });

  // ------------------------------------------------------------ examples

  async function loadExamples() {
    try {
      const res = await fetch("api/examples");
      examples = await res.json();
    } catch (e) {
      examples = [];
    }
    examplesSelect.innerHTML = "";
    const placeholder = document.createElement("option");
    placeholder.textContent = "Examples…";
    placeholder.value = "";
    examplesSelect.appendChild(placeholder);
    examples.forEach((ex, i) => {
      const opt = document.createElement("option");
      opt.value = String(i);
      opt.textContent = ex.name;
      examplesSelect.appendChild(opt);
    });
  }

  examplesSelect.addEventListener("change", () => {
    const i = examplesSelect.value;
    if (i === "") return;
    const ex = examples[Number(i)];
    input.setValue(JSON.stringify(ex.input, null, 2));
    spec.setValue(JSON.stringify(ex.spec, null, 2));
    expressions.setValue(ex.expressions ? JSON.stringify(ex.expressions, null, 2) : "");
    showExpressions(!!ex.expressions);
    history.replaceState(null, "", location.pathname);
    transform();
  });

  // ------------------------------------------------------------ permalinks

  const b64url = {
    encode(bytes) {
      let s = "";
      bytes.forEach(b => s += String.fromCharCode(b));
      return btoa(s).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
    },
    decode(text) {
      const s = atob(text.replaceAll("-", "+").replaceAll("_", "/"));
      return Uint8Array.from(s, c => c.charCodeAt(0));
    },
  };

  async function pipeThrough(bytes, stream) {
    const out = new Response(new Blob([bytes]).stream().pipeThrough(stream));
    return new Uint8Array(await out.arrayBuffer());
  }

  async function makePermalink() {
    const payload = JSON.stringify({
      input: input.getValue(),
      spec: spec.getValue(),
      expressions: expressions.getValue(),
    });
    const raw = new TextEncoder().encode(payload);
    let hash;
    if (typeof CompressionStream !== "undefined") {
      hash = "#z=" + b64url.encode(await pipeThrough(raw, new CompressionStream("gzip")));
    } else {
      hash = "#j=" + b64url.encode(raw);
    }
    const url = location.origin + location.pathname + hash;
    history.replaceState(null, "", hash);
    await navigator.clipboard.writeText(url);
    toast("Link copied to clipboard");
  }

  async function loadPermalink() {
    const h = location.hash;
    try {
      let raw = null;
      if (h.startsWith("#z=")) {
        raw = await pipeThrough(b64url.decode(h.slice(3)), new DecompressionStream("gzip"));
      } else if (h.startsWith("#j=")) {
        raw = b64url.decode(h.slice(3));
      }
      if (!raw) return false;
      const payload = JSON.parse(new TextDecoder().decode(raw));
      input.setValue(payload.input);
      spec.setValue(payload.spec);
      expressions.setValue(payload.expressions || "");
      showExpressions(!!(payload.expressions || "").trim());
      return true;
    } catch (e) {
      return false;
    }
  }

  document.getElementById("share").addEventListener("click", makePermalink);

  function toast(message) {
    let el = document.querySelector(".toast");
    if (!el) {
      el = document.createElement("div");
      el.className = "toast";
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.classList.add("show");
    setTimeout(() => el.classList.remove("show"), 1800);
  }

  // ------------------------------------------------------------ boot

  (async function boot() {
    await loadExamples();
    if (!(await loadPermalink())) {
      if (examples.length > 0) {
        input.setValue(JSON.stringify(examples[0].input, null, 2));
        spec.setValue(JSON.stringify(examples[0].spec, null, 2));
      } else {
        input.setValue("{\n  \"greeting\": \"hello\"\n}");
        spec.setValue("{\n  \"shout\": {\"#upper\": \"$.greeting\"}\n}");
      }
    }
    transform();
  })();
})();
