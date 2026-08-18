import { render, screen } from "@testing-library/react";
import { expect, test, vi } from "vitest";
import type { useProfiles } from "../useProfiles";
import { ProfilesPage } from "./ProfilesPage";

function profilesState(overrides: Record<string, unknown> = {}) {
  return {
    activationPlan: null,
    mutationPlan: null,
    profileBusy: false,
    profileName: "",
    profiles: {
      profiles: [{
        name: "Campaign",
        active: true,
        canActivate: true,
        sameInstall: true,
        modCount: 2,
        savedAt: "2026-08-18T00:00:00Z",
        missingMods: [],
      }],
      diagnostics: [],
    },
    profilesLoading: false,
    renameDraft: "",
    renameTarget: null,
    duplicateDraft: "",
    duplicateTarget: null,
    applyProfile: vi.fn(),
    applyProfileMutation: vi.fn(),
    beginRename: vi.fn(),
    cancelRename: vi.fn(),
    beginDuplicate: vi.fn(),
    cancelDuplicate: vi.fn(),
    reviewDeleteProfile: vi.fn(),
    reviewProfile: vi.fn(),
    saveCurrentProfile: vi.fn(),
    dismissActivationPlan: vi.fn(),
    dismissMutationPlan: vi.fn(),
    setProfileName: vi.fn(),
    setRenameDraft: vi.fn(),
    submitRename: vi.fn(),
    setDuplicateDraft: vi.fn(),
    submitDuplicate: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof useProfiles>;
}

test("profile cards keep the established copy wrapper used by the live stylesheet", () => {
  const { container } = render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState()}
      operationBlocked={false}
    />,
  );

  expect(container.querySelector(".profile-card__copy")).toBeInTheDocument();
  expect(container.querySelector(".profile-card__content")).toBeNull();
});

test("the existing saved-profile empty state remains intact", () => {
  const { container } = render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({ profiles: { profiles: [], diagnostics: [] } })}
      operationBlocked={false}
    />,
  );

  expect(container.querySelector(".profile-empty")).toHaveTextContent(
    "Save your current mod list, then switch profiles without toggling every mod by hand.",
  );
});

test("profile creation names its create-only boundary before the player clicks", () => {
  render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({ profileName: "Campaign" })}
      operationBlocked={false}
    />,
  );

  expect(screen.getByRole("heading", { name: "Save as new profile" })).toBeInTheDocument();
  expect(screen.getByText("“Campaign” is already a saved profile. Choose a new name to save this mod setup.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Create profile" })).toBeDisabled();
});

test("profile creation waits for the saved-name list and the current app-wide operation", () => {
  const { rerender } = render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({
        profileName: "Experiment",
        profilesLoading: true,
      })}
      operationBlocked={false}
    />,
  );

  expect(screen.getByText("Checking saved profile names…")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Create profile" })).toBeDisabled();

  rerender(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({ profileName: "Experiment" })}
      operationBlocked
    />,
  );

  expect(screen.getByRole("button", { name: "Create profile" })).toBeDisabled();
});
