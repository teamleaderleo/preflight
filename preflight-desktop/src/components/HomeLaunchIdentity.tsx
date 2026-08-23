import { shortPath } from "../uiFormat";

interface HomeLaunchIdentityProps {
  installRoot: string;
  profileName: string | null;
  onOpenProfiles: () => void;
}

export function HomeLaunchIdentity({ installRoot, profileName, onOpenProfiles }: HomeLaunchIdentityProps) {
  return (
    <div className="home-launch-identity">
      {profileName ? (
        <button
          className="home-launch-profile"
          type="button"
          aria-label={`Open saved profiles. Current profile: ${profileName}`}
          title="Open saved profiles"
          onClick={onOpenProfiles}
        >
          <strong>{profileName}</strong>
        </button>
      ) : null}
      <span
        className="home-launch-path"
        tabIndex={0}
        aria-label={`Installation ${installRoot}`}
        data-full-path={installRoot}
        title={installRoot}
      >
        <span className="home-launch-path__short">{shortPath(installRoot)}</span>
      </span>
    </div>
  );
}
