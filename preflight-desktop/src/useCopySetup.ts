import { useCallback, useState } from "react";
import {
  getCacheInspection,
  getLaunchSettings,
  getProfiles,
  getSnapshot,
} from "./bridge";
import { writeCopySetupToClipboard } from "./copySetup";
import { readLastInstallRoot } from "./desktopStorage";
import type { OptimizationPreset } from "./types";

export type CopySetupState = "idle" | "copying" | "copied" | "error";

export function useCopySetup(optimizationPreset: OptimizationPreset) {
  const [state, setState] = useState<CopySetupState>("idle");

  const copySetup = useCallback(async () => {
    setState("copying");
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

      await writeCopySetupToClipboard({
        preflightVersion: snapshot.engineVersion,
        platform: snapshot.platform,
        starsectorReady: snapshot.ready,
        profileFingerprint: activeProfile?.profileFingerprint
          ?? cacheInspection?.cache.currentProfileFingerprint
          ?? null,
        mods: (profiles?.enabledMods ?? []).map((id) => ({ id })),
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
        latestLaunch: snapshot.lastRun
          ? {
              outcome: snapshot.lastRun.outcome,
              startupMillis: snapshot.lastRun.startupMillis,
              exitCode: snapshot.lastRun.exitCode,
            }
          : null,
      });
      setState("copied");
    } catch {
      setState("error");
    }
  }, [optimizationPreset]);

  return { state, copySetup };
}

async function optionalRead<T>(read: Promise<T>): Promise<T | null> {
  try {
    return await read;
  } catch {
    return null;
  }
}
