import { useState, useEffect, useCallback } from "react";
import type { BisectSessionSnapshot, BisectVerdict } from "./types";
import {
  getBisectStatus,
  startModBisect,
  recordBisectVerdict,
  resetModBisect,
} from "./bridge";

export interface UseModBisectResult {
  session: BisectSessionSnapshot | null;
  loading: boolean;
  error: string | null;
  startBisect: (badMods?: string[]) => Promise<BisectSessionSnapshot | null>;
  recordVerdict: (verdict: "PASS" | "FAIL" | "SKIP") => Promise<BisectSessionSnapshot | null>;
  applyResolution: () => Promise<void>;
  abortSession: () => Promise<void>;
  refresh: () => Promise<void>;
}

export function useModBisect(gameDirectory: string | null): UseModBisectResult {
  const [session, setSession] = useState<BisectSessionSnapshot | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!gameDirectory) {
      setSession(null);
      return;
    }
    try {
      setLoading(true);
      setError(null);
      const status = await getBisectStatus(gameDirectory);
      setSession(status.active ? status : null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [gameDirectory]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const startBisect = useCallback(
    async (badMods?: string[]): Promise<BisectSessionSnapshot | null> => {
      if (!gameDirectory) return null;
      try {
        setLoading(true);
        setError(null);
        const newSession = await startModBisect(gameDirectory, badMods);
        setSession(newSession);
        return newSession;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        setError(msg);
        throw err;
      } finally {
        setLoading(false);
      }
    },
    [gameDirectory]
  );

  const recordVerdictAction = useCallback(
    async (verdict: "PASS" | "FAIL" | "SKIP"): Promise<BisectSessionSnapshot | null> => {
      if (!gameDirectory) return null;
      try {
        setLoading(true);
        setError(null);
        const updated = await recordBisectVerdict(gameDirectory, verdict);
        setSession(updated);
        return updated;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        setError(msg);
        throw err;
      } finally {
        setLoading(false);
      }
    },
    [gameDirectory]
  );

  const applyResolution = useCallback(async () => {
    if (!gameDirectory) return;
    try {
      setLoading(true);
      setError(null);
      await resetModBisect(gameDirectory);
      setSession(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [gameDirectory]);

  const abortSession = useCallback(async () => {
    if (!gameDirectory) return;
    try {
      setLoading(true);
      setError(null);
      await resetModBisect(gameDirectory);
      setSession(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [gameDirectory]);

  return {
    session,
    loading,
    error,
    startBisect,
    recordVerdict: recordVerdictAction,
    applyResolution,
    abortSession,
    refresh,
  };
}
