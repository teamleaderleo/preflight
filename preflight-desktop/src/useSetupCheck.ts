import { useCallback, useEffect, useRef, useState } from "react";
import { checkSetup } from "./bridge";
import type { Announce, SetupAnalysisResult } from "./types";
import { errorMessage } from "./uiFormat";

export function useSetupCheck(
  game: string | undefined,
  currentSetupKey: string,
  announce: Announce,
) {
  const [result, setResult] = useState<SetupAnalysisResult | null>(null);
  const [checking, setChecking] = useState(false);
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
    try {
      const next = await checkSetup(expectedGame);
      if (currentRequest === request.current && currentGame.current === expectedGame) {
        setResult(next);
      }
    } catch (error) {
      if (currentRequest === request.current && currentGame.current === expectedGame) {
        announce(errorMessage(error), "error");
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
  }, [game, currentSetupKey]);

  useEffect(() => {
    if (!game) return;
    const discardStaleResult = () => {
      request.current += 1;
      setResult(null);
    };
    window.addEventListener("focus", discardStaleResult);
    return () => window.removeEventListener("focus", discardStaleResult);
  }, [game]);

  return { checking, result, run };
}
