import { shortPath } from "../uiFormat";

interface HomeLaunchIdentityProps {
  installRoot: string;
  profileName: string | null;
}

export function HomeLaunchIdentity({ installRoot, profileName }: HomeLaunchIdentityProps) {
  const name = profileName ?? "Current mod setup";
  return (
    <div className="home-launch-identity">
      <strong title={profileName ?? undefined}>{name}</strong>
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
