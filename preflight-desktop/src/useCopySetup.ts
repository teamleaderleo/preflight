import { useCallback, useState } from "react";
import {
  getCacheInspection,
  getLaunchSettings,
  getProfiles,
  getSnapshot,
} from "./bridge";
import { createCopySetupText } from "./copySetup";
import { readLastInstallRoot } from "./desktopStorage";
import { lastRunForCurrentProfile } from "./lastRunApplicability";
import type { OptimizationPreset } from "./types";

export type CopySetupState = "idle" | "copying" | "copied" | "error";

export function useCopySetup(optimizationPreset: OptimizationPreset) {
  const [state, setState] = useState<CopySetupState>("idle");
  const [text, setText] = useState<string | null>(null);

  const copySetup = useCallback(async () => {
    setState("copying");
    setText(null);
    try {
      const rememberedGame = readLastInstallRoot();
      const snapshot = await getSnapshot(rememberedGame ?? undefined);
      const game = snapshot.selected?.installRoot;
      const [profiles, launchSettings, cacheInspection] = await Promise.all([
        game ? optionalRead(getProfiles(game)) : Promise.resolve(null),
        game ? optionalRead(getLaunchSettings(game)) : Promise.resolve(null),
        game ? optionalRead(getCacheInspection(game)) : Promise.resolve(null),
      ]);
      const activeProfile = profiles?.profiles.find((profile) => profile.active && profile.sameInstall) ?? null;
      const profileFingerprint = activeProfile?.profileFingerprint
        ?? cacheInspection?.cache.currentProfileFingerprint
        ?? null;
      const latestLaunch = lastRunForCurrentProfile(snapshot.lastRun, game, profileFingerprint);

      const generated = createCopySetupText({
        preflightVersion: snapshot.engineVersion,
        platform: snapshot.platform,
        starsectorReady: snapshot.ready,
        profileFingerprint,
        // A null profile read means the enabled-mod list was unavailable. Preserve [] exclusively
        // for a successfully observed vanilla/empty profile so public support text cannot confuse
        // missing evidence with an established zero-mod setup.
        mods: profiles === null ? null : profiles.enabledMods.map((id) => ({ id })),
        launchSettings: launchSettings
          ? {
              resolution: launchSettings.settings?.resolution ?? launchSettings.preferences.resolution,
              fullscreen: launchSettings.settings?.fullscreen ?? launchSettings.preferences.fullscreen,
              sound: launchSettings.settings?.sound ?? launchSettings.preferences.sound,
              maxHeapMiB: launchSettings.memory.maxHeapMiB,
              initialHeapMiB: launchSettings.memory.initialHeapMiB,
            }
          : null,
        optimizationPreset,
        preparation: cacheInspection
          ? {
              status: cacheInspection.health.status,
              profileFingerprint: cacheInspection.health.profileFingerprint,
            }
          : null,
        latestLaunch: latestLaunch
          ? {
              outcome: latestLaunch.outcome,
              startupMillis: latestLaunch.startupMillis,
              exitCode: latestLaunch.exitCode,
            }
          : null,
      });
      // Retain the exact public-safe bytes before touching the clipboard. A denied clipboard write
      // must never force another observation pass or make the already-generated summary disappear.
      setText(generated);
      try {
        await navigator.clipboard.writeText(generated);
        setState("copied");
      } catch {
        setState("error");
      }
    } catch {
      setText(null);
      setState("error");
    }
  }, [optimizationPreset]);

  const retryCopySetup = useCallback(async () => {
    if (text === null) return;
    setState("copying");
    try {
      await navigator.clipboard.writeText(text);
      setState("copied");
    } catch {
      setState("error");
    }
  }, [text]);

  return { state, text, copySetup, retryCopySetup };
}

async function optionalRead<T>(read: Promise<T>): Promise<T | null> {
  try {
    return await read;
  } catch {
    return null;
  }
}
