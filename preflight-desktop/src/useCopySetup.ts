import { useCallback, useState } from "react";
import { save as saveFile } from "@tauri-apps/plugin-dialog";
import {
  getCacheInspection,
  getLaunchSettings,
  getProfiles,
  getSnapshot,
  isDesktopHost,
  saveSetupSummary as saveSetupSummaryFile,
} from "./bridge";
import { createCopySetupText } from "./copySetup";
import { readLastInstallRoot } from "./desktopStorage";
import { lastRunForCurrentProfile } from "./lastRunApplicability";
import type { OptimizationPreset } from "./types";
import { errorMessage, localDateStamp } from "./uiFormat";

export type CopySetupState = "idle" | "copying" | "copied" | "error";
export type SaveSetupState = "idle" | "saving" | "saved" | "error";

export function useCopySetup(optimizationPreset: OptimizationPreset) {
  const [copyState, setCopyState] = useState<CopySetupState>("idle");
  const [saveState, setSaveState] = useState<SaveSetupState>("idle");
  const [saveError, setSaveError] = useState("");
  const [savedOutput, setSavedOutput] = useState<string | null>(null);
  const [text, setText] = useState<string | null>(null);

  const buildSetupText = useCallback(async () => {
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
    setText(generated);
    return generated;
  }, [optimizationPreset]);

  const copyText = useCallback(async (generated: string) => {
    setCopyState("copying");
    try {
      // Retain the exact public-safe bytes before touching the clipboard. A denied clipboard write
      // must never force another observation pass or make the already-generated summary disappear.
      setText(generated);
      await navigator.clipboard.writeText(generated);
      setCopyState("copied");
    } catch {
      setCopyState("error");
    }
  }, []);

  const copySetup = useCallback(async () => {
    setCopyState("copying");
    setText(null);
    try {
      await copyText(await buildSetupText());
    } catch {
      setText(null);
      setCopyState("error");
    }
  }, [buildSetupText, copyText]);

  const retryCopySetup = useCallback(async () => {
    if (text === null) return;
    await copyText(text);
  }, [copyText, text]);

  const saveText = useCallback(async (generated: string) => {
    setSaveState("saving");
    setSaveError("");
    try {
      const stamp = localDateStamp();
      const destination = isDesktopHost()
        ? await saveFile({
          title: "Save Preflight setup summary",
          defaultPath: `preflight-setup-${stamp}.txt`,
          filters: [{ name: "Text file", extensions: ["txt"] }],
        })
        : `/Users/captain/Desktop/preflight-setup-${stamp}.txt`;
      if (!destination) {
        setSaveState("idle");
        return;
      }
      const receipt = await saveSetupSummaryFile(destination, generated);
      setSavedOutput(receipt.output);
      setSaveState("saved");
    } catch (error) {
      setSaveError(errorMessage(error));
      setSaveState("error");
    }
  }, []);

  const saveSetupSummary = useCallback(async () => {
    setSaveState("saving");
    setSaveError("");
    setSavedOutput(null);
    setText(null);
    try {
      await saveText(await buildSetupText());
    } catch (error) {
      setText(null);
      setSaveError(errorMessage(error));
      setSaveState("error");
    }
  }, [buildSetupText, saveText]);

  const retrySaveSetup = useCallback(async () => {
    if (text === null) return;
    await saveText(text);
  }, [saveText, text]);

  return {
    // Compatibility alias for the launch-failure recovery action, which only exposes Copy setup.
    state: copyState,
    copyState,
    saveState,
    saveError,
    savedOutput,
    text,
    copySetup,
    retryCopySetup,
    saveSetupSummary,
    retrySaveSetup,
  };
}

async function optionalRead<T>(read: Promise<T>): Promise<T | null> {
  try {
    return await read;
  } catch {
    return null;
  }
}
