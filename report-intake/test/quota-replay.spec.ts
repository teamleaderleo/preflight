import { env } from "cloudflare:workers";
import { expect, it } from "vitest";
import type { DailyReportQuota } from "../src/daily-quota";

function quotaFor(name: string): DurableObjectStub<DailyReportQuota> {
  return env.DAILY_REPORT_QUOTA.getByName(name);
}

it("extends an uncommitted reservation when keyed case creation is replayed", async () => {
  const quota = quotaFor(`test-replay-expiry-${crypto.randomUUID()}`);
  const caseId = crypto.randomUUID();
  const now = 3_000_000;

  expect(await quota.reserve(caseId, 6, now + 100, 10, now))
    .toEqual({ accepted: true, usedBytes: 6 });

  // A lost create response can be retried while the original reservation is still active. The
  // service signs a fresh grant for the replay, so the existing reservation must follow that new
  // expiry without charging the same deterministic case twice.
  expect(await quota.reserve(caseId, 6, now + 500, 10, now + 50))
    .toEqual({ accepted: true, usedBytes: 6 });

  expect(await quota.beginUpload(caseId, now + 600, now + 200)).toBe("active");
});

it("does not revive a replayed reservation after its original lease already expired", async () => {
  const quota = quotaFor(`test-replay-expired-${crypto.randomUUID()}`);
  const caseId = crypto.randomUUID();
  const now = 4_000_000;

  expect(await quota.reserve(caseId, 6, now + 100, 10, now))
    .toEqual({ accepted: true, usedBytes: 6 });

  // Once the old uncommitted lease is already reclaimable, replay creates one fresh reservation
  // with the same deterministic case ID and fresh grant lifetime.
  expect(await quota.reserve(caseId, 6, now + 500, 10, now + 101))
    .toEqual({ accepted: true, usedBytes: 6 });
  expect(await quota.beginUpload(caseId, now + 600, now + 200)).toBe("active");
});
