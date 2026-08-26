import type { OptimizationPreset } from "./types";

export const optimizationPresets: Array<{
  id: OptimizationPreset;
  label: string;
  description: string;
  badge: string;
}> = [
  {
    id: "recommended",
    label: "Recommended",
    description: "Every optimization Preflight recognizes for this game and mod setup.",
    badge: "Default",
  },
  {
    id: "conservative",
    label: "Conservative",
    description: "Startup caches and padded textures, without changing gameplay code.",
    badge: "Compatibility",
  },
  {
    id: "off",
    label: "Off",
    description: "No optimizations. Launch and support tools still work.",
    badge: "Troubleshoot",
  },
];

const storageGroups: Record<string, { label: string; detail: string }> = {
  acceleration: {
    label: "Prepared game data",
    detail: "Textures, audio, and startup caches Preflight built. Rebuilt by preparing again.",
  },
  evidence: {
    label: "Reports and recordings",
    detail: "Launch timings, benchmarks, and support files.",
  },
  discardable: {
    label: "Replaced cache data",
    detail: "Rejected cache files that Preflight can remove safely.",
  },
  configuration: {
    label: "Profiles and backups",
    detail: "Saved mod profiles, and the backup taken each time one is switched.",
  },
  application: {
    label: "Preflight itself",
    detail: "The installed copy of preflight.jar.",
  },
};

export function storageGroupLabel(id: string): { label: string; detail: string } {
  return storageGroups[id] ?? { label: id.replaceAll("-", " "), detail: "" };
}
