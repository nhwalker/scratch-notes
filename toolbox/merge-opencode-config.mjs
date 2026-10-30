// Build-time helper: deep-merge config fragments into one opencode.json.
// Objects merge key by key, arrays are concatenated, other values are replaced.
// Usage: node merge-opencode-config.mjs <out.json> <base.json> [fragment.json ...]
import { readFileSync, writeFileSync } from "node:fs";

const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

function merge(a, b) {
  if (Array.isArray(a) && Array.isArray(b)) return [...a, ...b];
  if (isObject(a) && isObject(b)) {
    const out = { ...a };
    for (const [k, v] of Object.entries(b)) out[k] = k in a ? merge(a[k], v) : v;
    return out;
  }
  return b;
}

const [out, ...inputs] = process.argv.slice(2);
const merged = inputs.map((f) => JSON.parse(readFileSync(f, "utf8"))).reduce(merge);
writeFileSync(out, JSON.stringify(merged, null, 2) + "\n");
