import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
import type { useProfiles } from "../useProfiles";
import type { useSetupCheck } from "../useSetupCheck";
import { ProfilesPage as ProfilesPageComponent } from "./ProfilesPage";

function setupCheckState(overrides: Partial<ReturnType<typeof useSetupCheck>> = {}) {
  return {
    checking: false,
    result: null,
    run: vi.fn(),
    ...overrides,
  } as ReturnType<typeof useSetupCheck>;
}

function ProfilesPage({
  setupCheck = setupCheckState(),
  ...props
}: Omit<React.ComponentProps<typeof ProfilesPageComponent>, "setupCheck"> & {
  setupCheck?: ReturnType<typeof useSetupCheck>;
}) {
  return <ProfilesPageComponent {...props} setupCheck={setupCheck} />;
}

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
    modReadiness: {
      format: "starsector-preflight-mod-readiness-v1",
      ready: true,
      counts: { blocking: 0, warning: 0, info: 0, unknown: 0 },
      findings: [],
      modDirectories: 83,
      metadataBytes: 131_072,
      elapsedMillis: 6,
    },
    modReadinessLoading: false,
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
    refreshModReadiness: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof useProfiles>;
}

test("the automatic mod check stays quiet while the deeper check remains available", () => {
  render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState()}
      operationBlocked={false}
    />,
  );

  expect(screen.queryByLabelText("Current mod setup needs attention")).not.toBeInTheDocument();
  expect(screen.queryByText("Dependencies checked")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Check setup" })).toBeEnabled();
});

test("a broken dependency is concise before review and actionable after expansion", async () => {
  const user = userEvent.setup();
  const refreshModReadiness = vi.fn();
  render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({
        refreshModReadiness,
        modReadiness: {
          format: "starsector-preflight-mod-readiness-v1",
          ready: false,
          counts: { blocking: 1, warning: 0, info: 0, unknown: 0 },
          findings: [{
            code: "mod-metadata.required-dependency-disabled",
            provider: "mod-metadata",
            severity: "blocking",
            summary: "Nexerelin requires LazyLib, but LazyLib is disabled.",
            parameters: {},
            affectedModIds: ["nexerelin", "lw_lazylib"],
            actions: ["Enable LazyLib, then check again."],
          }],
          modDirectories: 83,
          metadataBytes: 131_072,
          elapsedMillis: 6,
        },
      })}
      operationBlocked={false}
    />,
  );

  expect(screen.getByText("1 mod problem")).toBeInTheDocument();
  expect(screen.queryByText("Nexerelin requires LazyLib, but LazyLib is disabled.")).not.toBeVisible();
  await user.click(screen.getByText("1 mod problem"));
  expect(screen.getByText("Nexerelin requires LazyLib, but LazyLib is disabled.")).toBeVisible();
  expect(screen.getByRole("button", { name: "Refresh" })).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Refresh" }));
  expect(refreshModReadiness).toHaveBeenCalledOnce();
});

test("the explicit setup check reports a clean result without proof language", async () => {
  const user = userEvent.setup();
  const run = vi.fn();
  render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState()}
      setupCheck={setupCheckState({
        run,
        result: {
          format: "starsector-preflight-setup-analysis-v1",
          installationIdentity: "install-v1:test",
          profileFingerprint: "profile:test",
          ready: true,
          counts: { blocking: 0, warning: 0, info: 0, unknown: 0 },
          findings: [],
          unavailableProviders: [],
        },
      })}
      operationBlocked={false}
    />,
  );

  expect(screen.getByText("No problems found")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Check again" }));
  expect(run).toHaveBeenCalledOnce();
});

test("the explicit setup check puts broken references beside the rerun action", () => {
  render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState()}
      setupCheck={setupCheckState({
        result: {
          format: "starsector-preflight-setup-analysis-v1",
          installationIdentity: "install-v1:test",
          profileFingerprint: "profile:test",
          ready: false,
          counts: { blocking: 1, warning: 0, info: 0, unknown: 0 },
          findings: [{
            code: "variant.missing-hull",
            provider: "variant",
            severity: "blocking",
            summary: "A ship variant refers to a hull that is not installed.",
            parameters: {},
            affectedModIds: ["example"],
            actions: [],
          }],
          unavailableProviders: [],
        },
      })}
      operationBlocked={false}
    />,
  );

  expect(screen.getByText("1 problem found")).toBeVisible();
  expect(screen.getByText("A ship variant refers to a hull that is not installed.")).toBeVisible();
  expect(screen.getByRole("button", { name: "Check again" })).toBeVisible();
});

const inactiveProfiles = {
  profiles: [{
    name: "Campaign",
    active: false,
    canActivate: true,
    sameInstall: true,
    modCount: 2,
    savedAt: "2026-08-18T00:00:00Z",
    missingMods: [],
  }],
  diagnostics: [],
};

const activationPlan = {
  name: "Campaign",
  sameInstall: true,
  savedInstallRoot: "/Applications/Starsector",
  missingMods: [],
  enable: ["graphicslib"],
  disable: [],
  canActivate: true,
  active: false,
};

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

test("profile switch review receives focus when it appears", () => {
  render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({ activationPlan })}
      operationBlocked={false}
    />,
  );

  expect(screen.getByRole("region", { name: "Profile switch review" })).toHaveFocus();
});

test("profile mutation review receives focus after a menu action closes", () => {
  render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({
        mutationPlan: {
          operation: "delete",
          name: "Campaign",
          targetName: null,
          active: true,
        },
      })}
      operationBlocked={false}
    />,
  );

  expect(screen.getByRole("region", { name: "Profile change review" })).toHaveFocus();
});

test("cancelling a profile switch review returns focus to the initiating Switch control", async () => {
  const user = userEvent.setup();
  const reviewProfile = vi.fn();
  const dismissActivationPlan = vi.fn();
  const { rerender } = render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({ profiles: inactiveProfiles, reviewProfile, dismissActivationPlan })}
      operationBlocked={false}
    />,
  );

  const switchControl = screen.getByRole("button", { name: "Switch…" });
  await user.click(switchControl);
  expect(reviewProfile).toHaveBeenCalledWith("Campaign");

  rerender(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({
        profiles: inactiveProfiles,
        reviewProfile,
        dismissActivationPlan,
        activationPlan,
      })}
      operationBlocked={false}
    />,
  );
  expect(screen.getByRole("region", { name: "Profile switch review" })).toHaveFocus();

  await user.click(screen.getByRole("button", { name: "Cancel" }));
  expect(dismissActivationPlan).toHaveBeenCalledOnce();
  rerender(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({ profiles: inactiveProfiles, reviewProfile, dismissActivationPlan })}
      operationBlocked={false}
    />,
  );
  expect(screen.getByRole("button", { name: "Switch…" })).toHaveFocus();
});

test("cancelling a profile mutation review returns focus to its closed Manage disclosure", async () => {
  const user = userEvent.setup();
  const beginRename = vi.fn();
  const submitRename = vi.fn();
  const dismissMutationPlan = vi.fn();
  const { rerender } = render(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({ beginRename, submitRename, dismissMutationPlan })}
      operationBlocked={false}
    />,
  );

  const manage = screen.getByLabelText("Manage Campaign");
  await user.click(manage);
  await user.click(screen.getByRole("button", { name: "Rename" }));
  expect(beginRename).toHaveBeenCalledWith("Campaign");
  expect(manage.closest("details")).not.toHaveAttribute("open");

  rerender(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({
        beginRename,
        submitRename,
        dismissMutationPlan,
        renameTarget: "Campaign",
        renameDraft: "Campaign 2",
      })}
      operationBlocked={false}
    />,
  );
  await user.click(screen.getByRole("button", { name: "Review rename" }));
  expect(submitRename).toHaveBeenCalledOnce();

  rerender(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({
        beginRename,
        submitRename,
        dismissMutationPlan,
        mutationPlan: {
          operation: "rename",
          name: "Campaign",
          targetName: "Campaign 2",
          active: true,
        },
      })}
      operationBlocked={false}
    />,
  );
  expect(screen.getByRole("region", { name: "Profile change review" })).toHaveFocus();

  await user.click(screen.getByRole("button", { name: "Cancel" }));
  expect(dismissMutationPlan).toHaveBeenCalledOnce();
  rerender(
    <ProfilesPage
      message=""
      messageTone="info"
      profilesState={profilesState({ beginRename, submitRename, dismissMutationPlan })}
      operationBlocked={false}
    />,
  );
  expect(screen.getByLabelText("Manage Campaign")).toHaveFocus();
  expect(screen.getByLabelText("Manage Campaign").closest("details")).not.toHaveAttribute("open");
});
