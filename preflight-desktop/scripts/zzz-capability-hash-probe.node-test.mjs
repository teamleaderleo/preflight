import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";

const source = readFileSync(new URL("../src-tauri/src/hull_sprite_tracer.rs", import.meta.url), "utf8").replaceAll("\r\n", "\n");
const digest = createHash("sha256").update(Buffer.from(source, "utf8")).digest("hex");
throw new Error(`TRACER_SHA256=${digest}`);
