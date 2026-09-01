import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflowDirectory = resolve(repository, ".github/workflows");
const expectedEngine = ">=24.15.0 <25";

function workflowJobs(source) {
  const jobsStart = source.indexOf("\njobs:\n");
  assert.notEqual(jobsStart, -1, "workflow has no jobs section");
  const jobs = source.slice(jobsStart + 1);
  const markers = [...jobs.matchAll(/^  ([A-Za-z0-9_-]+):\n/gm)];
  return markers.map((marker, index) => ({
    name: marker[1],
    source: jobs.slice(marker.index, markers[index + 1]?.index ?? jobs.length),
  }));
}

function commandSteps(jobSource) {
  return jobSource
    .split(/^      - /m)
    .slice(1)
    .map((step) => `      - ${step}`);
}

test("the repository pins one supported Node 24 runtime", () => {
  const version = readFileSync(resolve(repository, ".node-version"), "utf8").trim();
  assert.match(version, /^24\.\d+\.\d+$/);

  for (const relative of ["preflight-desktop/package.json", "report-intake/package.json"]) {
    const manifest = JSON.parse(readFileSync(resolve(repository, relative), "utf8"));
    assert.equal(manifest.engines?.node, expectedEngine, `${relative} has a different Node contract`);
  }
});

test("every workflow command uses the pinned runtime before invoking Node tooling", () => {
  for (const name of readdirSync(workflowDirectory).filter((entry) => entry.endsWith(".yml"))) {
    const source = readFileSync(resolve(workflowDirectory, name), "utf8").replaceAll("\r\n", "\n");
    const setupCount = (source.match(/uses: actions\/setup-node@/g) ?? []).length;
    const pinCount = (source.match(/node-version-file: \.node-version/g) ?? []).length;
    assert.equal(pinCount, setupCount, `${name} has a setup-node step outside the root pin`);
    assert.doesNotMatch(source, /\bnode-version:/, `${name} carries a second Node version source`);

    for (const job of workflowJobs(source)) {
      let setupSeen = false;
      for (const step of commandSteps(job.source)) {
        if (step.includes("uses: actions/setup-node@")) setupSeen = true;
        const run = step.match(/\n\s+run:\s*(?:\|\s*\n)?([\s\S]*)/)?.[1] ?? "";
        const shell = step.match(/\n\s+shell:\s*([^\n]+)/)?.[1] ?? "";
        const invokesNode = /(^|[^A-Za-z0-9_.-])(node|npm|npx|pnpm)([^A-Za-z0-9_.-]|$)/m.test(run)
          || shell.startsWith("node");
        assert.ok(!invokesNode || setupSeen, `${name}:${job.name} invokes Node tooling before setup-node`);
      }
    }
  }
});
