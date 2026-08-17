import type { WireframeHull } from "../types";
import { FlightInstrument } from "./FlightInstrument";

interface HangarStageProps {
  hull: WireframeHull;
  /** What this Preflight is pointed at: the mod profile, or whatever stands in for it. */
  context: string;
  /** True once an installation is chosen, which is when the stage has something to be about. */
  ready: boolean;
}

/*
 * The idle screen.
 *
 * A launcher is looked at far more often than it is used: it is open, the game is not running,
 * and nothing is happening. That state was previously an abstract flight-path doodle behind the
 * launch button, which is the sort of thing every app has.
 *
 * So the stage gives that state the one piece of artwork Preflight actually owns -- the hull,
 * traced from the player's own installation and turned slowly -- at a size where the interior
 * blocks are legible instead of a 30-pixel ornament. The layout is the prototype's, corner
 * captions and all: `docs/design/hangar-light/`.
 *
 * The two captions are the ones the prototype settled on, and they carry real state rather than
 * decoration. Top-left is what this launch will be, which is the only thing on this screen the
 * player cannot find out by looking at the game. Bottom-right names the ship, because otherwise
 * a wireframe of your own Odyssey is a wireframe of some ship.
 */
export function HangarStage({ hull, context, ready }: HangarStageProps) {
  return (
    <div className={`hangar-stage ${ready ? "" : "hangar-stage--waiting"}`}>
      <FlightInstrument hull={hull} variant="stage" />
      <span className="hangar-stage__caption hangar-stage__caption--lead">{context}</span>
      <span className="hangar-stage__caption hangar-stage__caption--ship">{hull.name}</span>
    </div>
  );
}
