import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import worker from "../src/index";
import { PROTOCOL_VERSION } from "../src/protocol";

type TestEnv = Env & { REPORT_SIGNING_KEY: string };
type Grant = {
  caseId: string;
  upload: { url: string; token: string };
  finalize: { url: string; token: string };
  deletion: { url: string; token: string };
};

const IncomingRequest = Request<unknown, IncomingRequestCfProperties>;
let client = 100;

async function create(transactionId: string, bytes = 321, sha256 = "a".repeat(64)): Promise<Response> {
  return worker.fetch(new IncomingRequest("https://intake.test/v1/cases", {
    method: "POST",
    headers: {
      "cf-connecting-ip": `198.51.100.${client++}`,
      "content-type": "application/json",
      "preflight-report-transaction": transactionId,
    },
    body: JSON.stringify({
      protocolVersion: PROTOCOL_VERSION,
      productVersion: "0.1.0-test",
      bytes,
      sha256,
    }),
  }), env as TestEnv);
}

describe("report transaction recovery", () => {
  it("replays case creation to the same keyed case without changing the v1 request body", async () => {
    const transactionId = "6f71f310-24a3-4ae8-933a-4942047bed0a";

    const firstResponse = await create(transactionId);
    const secondResponse = await create(transactionId);
    expect(firstResponse.status).toBe(201);
    expect(secondResponse.status).toBe(201);
    const first = await firstResponse.json<Grant>();
    const second = await secondResponse.json<Grant>();

    expect(first.caseId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(second.caseId).toBe(first.caseId);
    expect(second.upload.url).toBe(first.upload.url);
    expect(second.finalize.url).toBe(first.finalize.url);
    expect(second.deletion.url).toBe(first.deletion.url);
  });

  it("binds the keyed case identity to the disclosed report identity", async () => {
    const transactionId = "7ad5d058-f2e6-45ce-8f1d-50bb094ee676";
    const first = await create(transactionId, 321, "a".repeat(64));
    const second = await create(transactionId, 322, "b".repeat(64));

    expect(first.status).toBe(201);
    expect(second.status).toBe(201);
    expect((await second.json<Grant>()).caseId).not.toBe((await first.json<Grant>()).caseId);
  });

  it("rejects malformed transaction identities before reserving a case", async () => {
    const response = await create("../../shared-case");

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      error: "invalid preflight-report-transaction header",
    });
  });
});
