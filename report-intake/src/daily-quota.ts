import { DurableObject } from "cloudflare:workers";

export type QuotaReservation = {
  accepted: boolean;
  usedBytes: number;
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
      `);
      if (await this.ctx.storage.getAlarm() === null) {
        await this.ctx.storage.setAlarm(Date.now() + 32 * 86400_000);
      }
    });
  }

  reserve(bytes: number, limitBytes: number): QuotaReservation {
    if (!Number.isSafeInteger(bytes) || bytes < 1) throw new Error("bytes must be a positive safe integer");
    if (!Number.isSafeInteger(limitBytes) || limitBytes < 1) {
      throw new Error("limitBytes must be a positive safe integer");
    }

    const update = this.ctx.storage.sql.exec(
      "UPDATE quota SET used_bytes = used_bytes + ? WHERE singleton = 1 AND used_bytes <= ?",
      bytes,
      limitBytes - bytes,
    );
    const used = this.ctx.storage.sql.exec<{ used_bytes: number }>(
      "SELECT used_bytes FROM quota WHERE singleton = 1",
    ).one().used_bytes;
    return { accepted: update.rowsWritten === 1, usedBytes: used };
  }

  async alarm(): Promise<void> {
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
  }
}
