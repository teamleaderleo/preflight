import { activateProfile, isDesktopHost } from "./bridge";
import type { ProfileActivationPlan } from "./types";

export interface ReviewedProfileActivationPlan extends ProfileActivationPlan {
  sourceStateSha256: string;
  sourceChanged: boolean;
  profileChanged: boolean;
  reviewChanged: boolean;
}

function browserPlan(plan: ProfileActivationPlan): ReviewedProfileActivationPlan {
  return {
    ...plan,
    sourceStateSha256: "0".repeat(64),
    sourceChanged: false,
    profileChanged: false,
    reviewChanged: false,
  };
}

export async function activateReviewedProfile(
  game: string,
  name: string,
  confirmed: boolean,
): Promise<ReviewedProfileActivationPlan> {
  const plan = await activateProfile(game, name, confirmed);
  if (!isDesktopHost()) return browserPlan(plan);
  return plan as ReviewedProfileActivationPlan;
}
