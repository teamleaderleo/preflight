import { DurableObject } from "cloudflare:workers";

export type QuotaReservation = {
  accepted: boolean;
  usedBytes: number;
};

export type QuotaLeaseState = "active" | "committed" | "missing";

type ReservationRow = {
  bytes: number;
  expires_at: number;
  committed: number;
};

export class DailyReportQuota extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.ctx.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS quota (
          singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
          used_bytes INTEGER NOT NULL CHECK (used_bytes >= 0)
        );
        INSERT OR IGNORE INTO quota (singleton, used_bytes) VALUES (1, 0);
        CREATE TABLE IF NOT EXISTS reservations (
          case_id TEXT PRIMARY KEY,
          bytes INTEGER NOT NULL CHECK (bytes > 0),
          expires_at INTEGER NOT NULL CHECK (expires_at > 0),
          committed INTEGER NOT NULL DEFAULT 0 CHECK (committed IN (0, 1))
        );
        CREATE INDEX IF NOT EXISTS reservations_expiry
          ON reservations (committed, expires_at);
      `);
      if (await this.ctx.storage.getAlarm() === null) {
        await this.ctx.storage.setAlarm(Date.now() + 32 * 86400_000);
      }
    });
  }

  reserve(
    caseId: string,
    bytes: number,
    expiresAtMillis: number,
    limitBytes: number,
    nowMillis = Date.now(),
  ): QuotaReservation {
    validateCaseId(caseId);
    validateBytes(bytes, "bytes");
    validateBytes(limitBytes, "limitBytes");
    validateMillis(nowMillis, "nowMillis");
    validateMillis(expiresAtMillis, "expiresAtMillis");
    if (expiresAtMillis <= nowMillis) throw new Error("reservation expiry must be in the future");

    this.reclaimExpired(nowMillis);
    const existing = this.reservation(caseId);
    const usedBytes = this.usedBytes();
    if (existing) {
      if (existing.bytes !== bytes) throw new Error("case already has a different quota reservation");
      return { accepted: true, usedBytes };
    }
    if (usedBytes > limitBytes - bytes) return { accepted: false, usedBytes };

    this.ctx.storage.sql.exec(
      "INSERT INTO reservations (case_id, bytes, expires_at, committed) VALUES (?, ?, ?, 0)",
      caseId,
      bytes,
      expiresAtMillis,
    );
    return { accepted: true, usedBytes: usedBytes + bytes };
  }

  beginUpload(
    caseId: string,
    leaseExpiresAtMillis: number,
    nowMillis = Date.now(),
  ): QuotaLeaseState {
    validateCaseId(caseId);
    validateMillis(nowMillis, "nowMillis");
    validateMillis(leaseExpiresAtMillis, "leaseExpiresAtMillis");
    if (leaseExpiresAtMillis <= nowMillis) throw new Error("upload lease expiry must be in the future");

    this.reclaimExpired(nowMillis, caseId);
    const existing = this.reservation(caseId);
    if (!existing) return "missing";
    if (existing.committed === 1) return "committed";
    if (existing.expires_at <= nowMillis) {
      this.ctx.storage.sql.exec(
        "DELETE FROM reservations WHERE case_id = ? AND committed = 0",
        caseId,
      );
      return "missing";
    }
    if (existing.expires_at < leaseExpiresAtMillis) {
      this.ctx.storage.sql.exec(
        "UPDATE reservations SET expires_at = ? WHERE case_id = ? AND committed = 0",
        leaseExpiresAtMillis,
        caseId,
      );
    }
    return "active";
  }

  commit(caseId: string): boolean {
    validateCaseId(caseId);
    const updated = this.ctx.storage.sql.exec(
      "UPDATE reservations SET committed = 1 WHERE case_id = ? AND committed = 0",
      caseId,
    );
    if (updated.rowsWritten === 1) return true;
    return this.reservation(caseId)?.committed === 1;
  }

  release(caseId: string): boolean {
    validateCaseId(caseId);
    return this.ctx.storage.sql.exec(
      "DELETE FROM reservations WHERE case_id = ? AND committed = 0",
      caseId,
    ).rowsWritten === 1;
  }

  async alarm(): Promise<void> {
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
  }

  private reclaimExpired(nowMillis: number, exceptCaseId?: string): void {
    if (exceptCaseId) {
      this.ctx.storage.sql.exec(
        "DELETE FROM reservations WHERE committed = 0 AND expires_at <= ? AND case_id <> ?",
        nowMillis,
        exceptCaseId,
      );
      return;
    }
    this.ctx.storage.sql.exec(
      "DELETE FROM reservations WHERE committed = 0 AND expires_at <= ?",
      nowMillis,
    );
  }

  private reservation(caseId: string): ReservationRow | undefined {
    return this.ctx.storage.sql.exec<ReservationRow>(
      "SELECT bytes, expires_at, committed FROM reservations WHERE case_id = ?",
      caseId,
    ).toArray()[0];
  }

  private usedBytes(): number {
    // `quota.used_bytes` is retained as a conservative legacy bucket for reservations made by the
    // pre-lease Worker before this schema was deployed. New cases live in `reservations` instead.
    const legacy = this.ctx.storage.sql.exec<{ used_bytes: number }>(
      "SELECT used_bytes FROM quota WHERE singleton = 1",
    ).one().used_bytes;
    const reservations = this.ctx.storage.sql.exec<{ bytes: number }>(
      "SELECT COALESCE(SUM(bytes), 0) AS bytes FROM reservations",
    ).one().bytes;
    return legacy + reservations;
  }
}

function validateCaseId(caseId: string): void {
  if (typeof caseId !== "string" || caseId.length < 1 || caseId.length > 128) {
    throw new Error("caseId must be bounded text");
  }
}

function validateBytes(value: number, name: string): void {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${name} must be a positive safe integer`);
  }
}

function validateMillis(value: number, name: string): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${name} must be a non-negative safe integer`);
  }
}
