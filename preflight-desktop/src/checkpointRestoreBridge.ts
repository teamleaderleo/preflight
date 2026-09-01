import { restoreCheckpoint } from "./bridge";
import type { CheckpointRestorePlan } from "./types";

export interface ReviewedCheckpointRestorePlan {
  plan: CheckpointRestorePlan;
  apply: () => Promise<CheckpointRestorePlan>;
}

export async function prepareReviewedCheckpointRestore(
  game: string,
  name: string,
  restoreSettings: boolean,
): Promise<ReviewedCheckpointRestorePlan> {
  const plan = await restoreCheckpoint(game, name, restoreSettings, null, false);
  return {
    plan,
    apply: async () => {
      if (!plan.canRestore) {
        throw new Error(plan.refusalReason ?? "This checkpoint cannot be restored.");
      }
      return restoreCheckpoint(
        game,
        name,
        restoreSettings,
        plan.sourceStateSha256 ?? null,
        true,
      );
    },
  };
}

export async function restoreReviewedCheckpoint(
  game: string,
  name: string,
  restoreSettings: boolean,
  expectedCheckpoint: string | null,
): Promise<CheckpointRestorePlan> {
  return restoreCheckpoint(game, name, restoreSettings, expectedCheckpoint, true);
}
