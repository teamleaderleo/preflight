import { env } from "cloudflare:workers";
import { strToU8, zipSync } from "fflate";
import { beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";
import { sha256Hex } from "../src/crypto";
import type { DailyReportQuota } from "../src/daily-quota";
import { BUNDLE_FORMAT, PROTOCOL_VERSION, ZIP_CONTENT_TYPE } from "../src/protocol";
import { runCanary } from "../scripts/canary.mjs";

type TestEnv = Env & { REPORT_SIGNING_KEY: string };
type Grant = {
  caseId: string;
  upload: { url: string; token: string };
  finalize: { url: string; token: string };
  deletion: { url: string; token: string };
};

const IncomingRequest = Request<unknown, IncomingRequestCfProperties>;
let nextClientAddress = 1;

describe("private report intake", () => {
  beforeEach(async () => {
    const listed = await env.REPORTS.list();
    await Promise.all(listed.objects.map((object) => env.REPORTS.delete(object.key)));
  });

  it("accepts a bounded diagnostics bundle, returns a signed receipt, and deletes by token", async () => {
    const archive = await bundle();
    const grant = await createGrant(archive);

    const upload = await dispatch(new IncomingRequest(grant.upload.url, {
      method: "PUT",
      headers: {
        authorization: `Bearer ${grant.upload.token}`,
        "content-length": String(archive.byteLength),
        "content-type": ZIP_CONTENT_TYPE,
      },
      body: archive,
    }));
    expect(upload.status).toBe(200);
    expect(await upload.json()).toMatchObject({
      status: "uploaded",
      caseId: grant.caseId,
      bytes: archive.byteLength,
      sha256: await sha256Hex(archive),
    });

    const finalize = await dispatch(new IncomingRequest(grant.finalize.url, {
      method: "POST",
      headers: { authorization: `Bearer ${grant.finalize.token}` },
    }));
    expect(finalize.status).toBe(200);
    const receipt = await finalize.json<Record<string, unknown>>();
    expect(receipt).toMatchObject({
      protocolVersion: PROTOCOL_VERSION,
      caseId: grant.caseId,
      objectKey: `accepted/${grant.caseId}.zip`,
      bytes: archive.byteLength,
      sha256: await sha256Hex(archive),
      productVersion: "0.1.0-test",
    });
    expect(receipt.signature).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(new Date(receipt.retentionDeadline as string).getTime())
      .toBeGreaterThan(new Date(receipt.receivedAt as string).getTime());

    const deletion = receipt.deletion as { url: string; token: string };
    const removed = await dispatch(new IncomingRequest(deletion.url, {
      method: "DELETE",
      headers: { authorization: `Bearer ${deletion.token}` },
    }));
    expect(removed.status).toBe(204);
    expect((await env.REPORTS.list()).objects).toHaveLength(0);
  });

  it("rejects a digest mismatch without storing the body", async () => {
    const archive = await bundle();
    const grant = await createGrant(archive);
    archive[10] ^= 1;

    const response = await upload(grant, archive);

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({ error: "archive SHA-256 doesn't match the grant" });
    expect((await env.REPORTS.list()).objects).toHaveLength(0);
  });

  it("rejects unexpected ZIP entries even when the outer digest matches", async () => {
    const archive = await bundle({ "save/game.sav": strToU8("private") });
    const grant = await createGrant(archive);

    const response = await upload(grant, archive);

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      error: expect.stringContaining("unexpected entry save/game.sav"),
    });
    expect((await env.REPORTS.list()).objects).toHaveLength(0);
  });

  it("rejects compressed evidence that expands beyond the per-file limit", async () => {
    const oversized = new Uint8Array(512 * 1024 + 1).fill(32);
    const archive = await bundle({ "runs/1/run.json": oversized }, false);
    const grant = await createGrant(archive);

    const response = await upload(grant, archive);

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      error: expect.stringContaining("exceeds its decompressed size limit"),
    });
    expect((await env.REPORTS.list()).objects).toHaveLength(0);
  });

  it("rejects malformed JSON evidence before storing it", async () => {
    const archive = await bundle({ "runs/1/run.json": strToU8('{"state":') });
    const grant = await createGrant(archive);

    const response = await upload(grant, archive);

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      error: expect.stringContaining("runs/1/run.json is not valid JSON"),
    });
    expect((await env.REPORTS.list()).objects).toHaveLength(0);
  });

  it("rejects non-object JSON evidence before storing it", async () => {
    const archive = await bundle({ "runs/1/run.json": strToU8('"untrusted text"') });
    const grant = await createGrant(archive);

    const response = await upload(grant, archive);

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      error: expect.stringContaining("runs/1/run.json must be a JSON object"),
    });
    expect((await env.REPORTS.list()).objects).toHaveLength(0);
  });

  it("rejects malformed JSON Lines evidence before storing it", async () => {
    const archive = await bundle({
      "benchmarks/1/results.jsonl": strToU8('{"status":"accepted"}\nnot-json\n'),
    });
    const grant = await createGrant(archive);

    const response = await upload(grant, archive);

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      error: expect.stringContaining("benchmarks/1/results.jsonl line 2 is not valid JSON"),
    });
    expect((await env.REPORTS.list()).objects).toHaveLength(0);
  });

  it("rejects bearer-token tampering and replayed uploads", async () => {
    const archive = await bundle();
    const grant = await createGrant(archive);
    const tampered = `${grant.upload.token.slice(0, -1)}${grant.upload.token.endsWith("A") ? "B" : "A"}`;

    const unauthorized = await dispatch(new IncomingRequest(grant.upload.url, {
      method: "PUT",
      headers: {
        authorization: `Bearer ${tampered}`,
        "content-length": String(archive.byteLength),
        "content-type": ZIP_CONTENT_TYPE,
      },
      body: archive,
    }));
    expect(unauthorized.status).toBe(401);

    expect((await upload(grant, archive)).status).toBe(200);
    const replay = await upload(grant, archive);
    expect(replay.status).toBe(409);
    expect((await env.REPORTS.list()).objects).toHaveLength(1);
  });

  it("requires the exact versioned create-case schema", async () => {
    const response = await dispatch(new IncomingRequest("https://intake.test/v1/cases", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ protocolVersion: 999, productVersion: "x", bytes: 1, sha256: "0".repeat(64) }),
    }));

    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "invalid case request" });
  });

  it("rate-limits repeated case creation from one client address", async () => {
    const archive = await bundle();
    const body = JSON.stringify({
      protocolVersion: PROTOCOL_VERSION,
      productVersion: "rate-limit-test",
      bytes: archive.byteLength,
      sha256: await sha256Hex(archive),
    });
    const request = () => dispatch(new IncomingRequest("https://intake.test/v1/cases", {
      method: "POST",
      headers: {
        "cf-connecting-ip": "192.0.2.250",
        "content-type": "application/json",
      },
      body,
    }));

    for (let index = 0; index < 5; index++) expect((await request()).status).toBe(201);
    const rejected = await request();
    expect(rejected.status).toBe(429);
    expect(rejected.headers.get("retry-after")).toBe("60");
    expect(await rejected.json()).toEqual({ error: "too many report cases" });
  });

  it("enforces exact byte reservations within each UTC-day quota object", async () => {
    const firstDay = quotaFor(`test-day-one-${crypto.randomUUID()}`);
    expect(await firstDay.reserve(6, 10)).toEqual({ accepted: true, usedBytes: 6 });
    expect(await firstDay.reserve(5, 10)).toEqual({ accepted: false, usedBytes: 6 });
    expect(await firstDay.reserve(4, 10)).toEqual({ accepted: true, usedBytes: 10 });
    expect(await firstDay.reserve(1, 10)).toEqual({ accepted: false, usedBytes: 10 });

    const secondDay = quotaFor(`test-day-two-${crypto.randomUUID()}`);
    expect(await secondDay.reserve(10, 10)).toEqual({ accepted: true, usedBytes: 10 });
  });

  it("runs the production canary client through upload, receipt, and verified deletion", async () => {
    const canaryFetch: typeof fetch = async (input, init) => dispatch(new IncomingRequest(
      input,
      init as RequestInit<IncomingRequestCfProperties> | undefined,
    ));
    const result = await runCanary("https://intake.test", canaryFetch);

    expect(result).toMatchObject({
      format: "preflight-report-intake-canary-v1",
      origin: "https://intake.test",
      deleted: true,
    });
    expect(result.caseId).toMatch(/^[0-9a-f-]{36}$/);
    expect((await env.REPORTS.list()).objects).toHaveLength(0);
  });

  it("refuses a production canary outside an exact HTTPS origin", async () => {
    await expect(runCanary("http://intake.test")).rejects.toThrow("exact configured HTTPS origin");
    await expect(runCanary("https://intake.test/path")).rejects.toThrow("exact configured HTTPS origin");
  });
});

async function createGrant(archive: Uint8Array): Promise<Grant> {
  const clientAddress = `192.0.2.${nextClientAddress++}`;
  const response = await dispatch(new IncomingRequest("https://intake.test/v1/cases", {
    method: "POST",
    headers: { "cf-connecting-ip": clientAddress, "content-type": "application/json" },
    body: JSON.stringify({
      protocolVersion: PROTOCOL_VERSION,
      productVersion: "0.1.0-test",
      bytes: archive.byteLength,
      sha256: await sha256Hex(archive),
    }),
  }));
  expect(response.status).toBe(201);
  return response.json<Grant>();
}

async function upload(grant: Grant, archive: Uint8Array): Promise<Response> {
  return dispatch(new IncomingRequest(grant.upload.url, {
    method: "PUT",
    headers: {
      authorization: `Bearer ${grant.upload.token}`,
      "content-length": String(archive.byteLength),
      "content-type": ZIP_CONTENT_TYPE,
    },
    body: archive,
  }));
}

async function dispatch(incoming: Request<unknown, IncomingRequestCfProperties>): Promise<Response> {
  return worker.fetch(incoming, env as TestEnv);
}

function quotaFor(name: string): DurableObjectStub<DailyReportQuota> {
  return env.DAILY_REPORT_QUOTA.getByName(name);
}

async function bundle(
  additions: Record<string, Uint8Array> = {},
  includeDefaultEvidence = true,
): Promise<Uint8Array> {
  const evidence: Record<string, Uint8Array> = includeDefaultEvidence
    ? { "runs/1/run.json": strToU8('{"state":"stopped"}\n') }
    : {};
  Object.assign(evidence, additions);
  const included = await Promise.all(Object.entries(evidence).map(async ([entry, bytes]) => ({
    entry,
    bytes: bytes.byteLength,
    sha256: await sha256Hex(bytes),
  })));
  const manifest = {
    format: BUNDLE_FORMAT,
    createdAt: new Date().toISOString(),
    platform: "mac",
    engineVersion: "0.1.0-test",
    selectedRuns: [{ rank: 1, modifiedMillis: 1 }],
    selectedBenchmarks: [],
    limits: {
      maximumRuns: 3,
      maximumBenchmarks: 2,
      maximumFileBytes: 512 * 1024,
      maximumContentBytes: 5 * 1024 * 1024,
    },
    redactions: ["current user home -> <home>"],
    included,
    skipped: [],
    excludedCategories: [
      "acceleration caches",
      "game and mod files",
      "saves",
      "logs and crash dumps",
      "JFR recordings",
      "screenshots and audio",
      "unknown filenames",
      "symbolic links",
    ],
  };
  return zipSync({
    "README.txt": strToU8("Bounded Preflight diagnostics. Review before sharing.\n"),
    "manifest.json": strToU8(`${JSON.stringify(manifest)}\n`),
    ...evidence,
  }, { level: 6 });
}
