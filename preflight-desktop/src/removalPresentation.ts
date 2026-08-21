export interface RemovalRefusalPresentation {
  summary: string;
  detail: string;
}

const ALL_DATA_REFUSAL_SUFFIX = "All-data removal is refused.";
const INTEGRATION_OWNERSHIP_REFUSAL = /^Existing (.+?) at .+ is not proven Preflight-owned and is preserved untouched\.$/;

export function presentRemovalRefusal(refusal: string): RemovalRefusalPresentation {
  if (refusal.startsWith("Preflight home directory is a symlink or alias")
      && refusal.endsWith(ALL_DATA_REFUSAL_SUFFIX)) {
    return {
      summary: "Preflight can’t safely remove its data because the Preflight data folder isn’t a normal directory. Nothing was removed.",
      detail: refusal,
    };
  }

  const integrationOwnership = INTEGRATION_OWNERSHIP_REFUSAL.exec(refusal);
  if (integrationOwnership) {
    return {
      summary: `Preflight left ${integrationOwnership[1]} alone because it couldn’t verify that it created this launcher entry.`,
      detail: refusal,
    };
  }

  return {
    summary: "Preflight left part of this removal plan alone because it couldn’t verify that it was safe to remove.",
    detail: refusal,
  };
}
