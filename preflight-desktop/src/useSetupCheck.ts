import { useCallback, useEffect, useRef, useState } from "react";
import { checkSetup } from "./bridge";
import type { Announce, SetupAnalysisResult } from "./types";
import { errorMessage } from "./uiFormat";

export type SetupCheckAttemptStatus = "idle" | "running" | "successful" | "failed";

export function useSetupCheck(
  game: string | undefined,
  currentSetupKey: string,
  announce: Announce,
) {
  const [result, setResult] = useState<SetupAnalysisResult | null>(null);
  const [checking, setChecking] = useState(false);
  const [status, setStatus] = useState<SetupCheckAttemptStatus>("idle");
  const [error, setError] = useState<string | null>(null);
  const checkingRef = useRef(false);
  const request = useRef(0);
  const inFlightRequest = useRef(0);
  const currentGame = useRef(game);
  currentGame.current = game;

  const run = useCallback(async () => {
    const expectedGame = game;
    if (!expectedGame || checkingRef.current) return;
    const currentRequest = ++request.current;
    inFlightRequest.current = currentRequest;
    checkingRef.current = true;
    setChecking(true);
    setStatus("running");
    setError(null);
    try {
      const next = await checkSetup(expectedGame);
      if (currentRequest === request.current && currentGame.current === expectedGame) {
        setResult(next);
        setStatus("successful");
      }
    } catch (caught) {
      if (currentRequest === request.current && currentGame.current === expectedGame) {
        const message = errorMessage(caught);
        setError(message);
        setStatus("failed");
        announce(message, "error");
      }
    } finally {
      if (currentRequest === inFlightRequest.current) {
        checkingRef.current = false;
        setChecking(false);
      }
    }
  }, [announce, game]);

  useEffect(() => {
    request.current += 1;
    setResult(null);
    setError(null);
    setStatus("idle");
  }, [game, currentSetupKey]);

  return { checking, error, result, status, run };
}
