import tracedHulls from "./previewTracedHulls.json";
import type { WireframeHull, WireframeHullCatalog } from "./types";

/** Six ready-to-draw hulls keep the Hangar useful before the local catalog finishes loading. */
export const BUNDLED_WIREFRAME_HULLS = tracedHulls as unknown as WireframeHullCatalog;

export const BUNDLED_DEFAULT_HULL: WireframeHull =
  BUNDLED_WIREFRAME_HULLS.hulls.find((hull) => hull.id === "odyssey")
  ?? BUNDLED_WIREFRAME_HULLS.hulls[0];
