import { verifyEngineBoundary } from "./engine-boundary.mjs";

const report = verifyEngineBoundary();
console.log(JSON.stringify(report));
