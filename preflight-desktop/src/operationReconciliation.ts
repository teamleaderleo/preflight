import { getOperationState } from "./bridge";
import type { OperationSnapshot } from "./types";

interface OperationReconciliationOptions {
  apply: (snapshot: OperationSnapshot) => void;
  isActive: () => boolean;
  onError: (error: unknown) => void;
  intervalMs?: number;
  read?: () => Promise<OperationSnapshot>;
}

/**
 * Reconciles event-driven operation state with the native coordinator.
 *
 * The first read starts immediately. Later reads begin only after the previous read settles, so a
 * slow native query cannot build a queue. Cancelling also fences an in-flight result from callers
 * that have switched installation or unmounted.
 */
export function startOperationReconciliation({
  apply,
  isActive,
  onError,
  intervalMs = 1_000,
  read = getOperationState,
}: OperationReconciliationOptions): () => void {
  let cancelled = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let failureReported = false;
  const delay = Math.max(0, intervalMs);

  const reportSafely = (error: unknown) => {
    if (cancelled || failureReported) return;
    failureReported = true;
    try {
      onError(error);
    } catch {
      // Error reporting must not turn a recoverable polling failure into an unhandled rejection.
    }
  };

  const remainsActive = () => {
    if (cancelled) return false;
    try {
      return isActive();
    } catch (error) {
      reportSafely(error);
      return false;
    }
  };

  const poll = async () => {
    try {
      const snapshot = await read();
      if (!cancelled) {
        apply(snapshot);
        failureReported = false;
      }
    } catch (error) {
      reportSafely(error);
    }
    if (remainsActive()) {
      timer = setTimeout(() => {
        timer = undefined;
        void poll();
      }, delay);
    }
  };

  void poll();
  return () => {
    cancelled = true;
    if (timer !== undefined) clearTimeout(timer);
  };
}
