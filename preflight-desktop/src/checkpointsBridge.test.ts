import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  getCheckpoints,
  createCheckpoint,
  compareCheckpoint,
  restoreCheckpoint,
  renameCheckpoint,
  deleteCheckpoint,
} from "./bridge";
import {
  prepareReviewedCheckpointRestore,
  restoreReviewedCheckpoint,
} from "./checkpointRestoreBridge";

describe("checkpointsBridge unit tests", () => {
  const mockGame = "/Applications/Starsector";

  it("fetches preview checkpoints list when outside Tauri host", async () => {
    const result = await getCheckpoints(mockGame);
    expect(result.format).toBe("starsector-preflight-checkpoint-list-v1");
    expect(result.checkpoints.length).toBeGreaterThan(0);
    expect(result.checkpoints[0].name).toBe("Cycle 214 Heavy Fleet");
  });

  it("creates a checkpoint in preview mode", async () => {
    const result = await createCheckpoint(mockGame, "New Campaign", "Clean setup", true);
    expect(result.format).toBe("starsector-preflight-checkpoint-v1");
    expect(result.name).toBe("New Campaign");
    expect(result.description).toBe("Clean setup");
    expect(result.enabledMods.length).toBeGreaterThan(0);
    expect(result.modSignatures.length).toBeGreaterThan(0);
  });

  it("compares a checkpoint with live state in preview mode", async () => {
    const result = await compareCheckpoint(mockGame, "Cycle 214 Heavy Fleet");
    expect(result.format).toBe("starsector-preflight-checkpoint-diff-v1");
    expect(result.checkpointName).toBe("Cycle 214 Heavy Fleet");
    expect(result.matched).toBe(true);
    expect(result.status).toBe("MATCHED");
  });

  it("executes two-phase review and restore", async () => {
    // Phase 1 preview
    const preview = await restoreCheckpoint(mockGame, "Cycle 214 Heavy Fleet", true, null, false);
    expect(preview.applied).toBe(false);
    expect(preview.canRestore).toBe(true);

    // Two-phase review helper
    const reviewed = await prepareReviewedCheckpointRestore(mockGame, "Cycle 214 Heavy Fleet", true);
    expect(reviewed.plan.canRestore).toBe(true);

    const applied = await reviewed.apply();
    expect(applied.applied).toBe(true);
  });

  it("renames and deletes checkpoints in preview mode", async () => {
    const rename = await renameCheckpoint(mockGame, "Cycle 214 Heavy Fleet", "Renamed Fleet", "cp-fingerprint-1", true);
    expect(rename.format).toBe("starsector-preflight-checkpoint-mutation-v1");
    expect(rename.operation).toBe("rename");
    expect(rename.targetName).toBe("Renamed Fleet");
    expect(rename.applied).toBe(true);

    const deletion = await deleteCheckpoint(mockGame, "Cycle 214 Heavy Fleet", "cp-fingerprint-1", true);
    expect(deletion.format).toBe("starsector-preflight-checkpoint-mutation-v1");
    expect(deletion.operation).toBe("delete");
    expect(deletion.applied).toBe(true);
  });
});
