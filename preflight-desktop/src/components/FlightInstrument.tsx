import { useEffect, useRef } from "react";
import { INSTRUMENT_APPEARANCE_ATTRIBUTES } from "../flightInstrumentAppearance";
import type { WireframeHull, WireframePoint } from "../types";
import { BUNDLED_DEFAULT_HULL } from "../bundledWireframeHulls";
import { projectHull } from "../wireframeHullGeometry";

interface FlightInstrumentProps {
  hull?: WireframeHull;
  /**
   * `badge` is the small one pinned beside a number, and keeps the targeting reticle it is read
   * against. `stage` fills its container and drops the reticle: at that size the ship is the
   * subject rather than a readout, and the chrome would be competing with it.
   */
  variant?: "badge" | "stage";
}

interface InstrumentPalette {
  near: [number, number, number];
  far: [number, number, number];
  grid: string;
  accent: string;
  fill: string;
}

function tracePolygon(context: CanvasRenderingContext2D, points: WireframePoint[], map: (point: WireframePoint) => WireframePoint) {
  if (points.length === 0) return;
  const first = map(points[0]);
  context.beginPath();
  context.moveTo(first.x, first.y);
  for (const point of points.slice(1)) {
    const next = map(point);
    context.lineTo(next.x, next.y);
  }
  context.closePath();
}

/*
 * The instrument's colours come from the palette that is on, so a blue app draws a blue ship.
 * They are declared over the palette's own ink and accent, which means the value stored in the
 * custom property is an unresolved expression a canvas cannot parse. Assigning it to a real
 * colour property and reading that back makes the browser resolve it to an rgb() first.
 */
function resolveColour(probe: HTMLElement, token: string, fallback: string): string {
  probe.style.color = fallback;
  probe.style.color = `var(${token})`;
  return getComputedStyle(probe).color || fallback;
}

function toRgb(colour: string, fallback: [number, number, number]): [number, number, number] {
  const parts = colour.match(/[\d.]+/g);
  return parts && parts.length >= 3
    ? [Number(parts[0]), Number(parts[1]), Number(parts[2])]
    : fallback;
}

function readPalette(canvas: HTMLCanvasElement): InstrumentPalette {
  const probe = canvas.ownerDocument.createElement("span");
  probe.style.display = "none";
  (canvas.parentElement ?? canvas.ownerDocument.body).appendChild(probe);
  const palette: InstrumentPalette = {
    near: toRgb(resolveColour(probe, "--instrument-near", "#3f3a35"), [63, 58, 53]),
    far: toRgb(resolveColour(probe, "--instrument-far", "#a89e90"), [168, 158, 144]),
    grid: resolveColour(probe, "--instrument-grid", "rgba(87,81,74,.14)"),
    accent: resolveColour(probe, "--instrument-accent", "#a76532"),
    fill: resolveColour(probe, "--instrument-fill", "rgba(167,101,50,.06)"),
  };
  probe.remove();
  return palette;
}

/*
 * The paint, carried over from the prototype rather than reinvented.
 *
 * Every edge is drawn on its own, sorted back to front, and its depth in the view decides three
 * things at once: colour between a far tone and a near one, line weight, and opacity. That is
 * what makes a flat set of lines read as a solid object -- not the geometry, which is the same
 * either way. Batching the edges by kind and giving each kind a flat colour was tried, and the
 * ship came out looking like a diagram of itself.
 */
function drawHull(
  canvas: HTMLCanvasElement,
  hull: WireframeHull,
  yaw: number,
  palette: InstrumentPalette,
  variant: "badge" | "stage",
) {
  const context = canvas.getContext("2d");
  if (!context) return;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  if (width <= 0 || height <= 0) return;
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  const pixelWidth = Math.round(width * ratio);
  const pixelHeight = Math.round(height * ratio);
  if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
    canvas.width = pixelWidth;
    canvas.height = pixelHeight;
  }
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  context.clearRect(0, 0, width, height);

  const detail = width < 170 ? "small" : width < 300 ? "medium" : "showcase";
  const projected = projectHull(hull, yaw, detail);
  if (projected.segments.length === 0) return;

  /*
   * One fixed camera, not a fit to what happens to be on screen this frame.
   *
   * Refitting per frame was tried and it is why the ship appeared to zoom and drift while it
   * turned: a rotating hull's projected bounding box breathes, so re-deriving the scale from it
   * pumps the whole picture on every frame. The geometry is already normalised to one frame,
   * which is what makes a constant work here for a stubby Hammerhead and a long Conquest alike.
   */
  const scale = Math.min(width, height) * (variant === "stage" ? 0.56 : 0.46);
  const map = (point: WireframePoint) => ({
    x: width / 2 + point.x * scale,
    y: height / 2 + point.y * scale,
  });

  context.lineJoin = "round";
  context.lineCap = "round";

  // The floor first, and underneath everything.
  if (projected.ground.length > 0) {
    context.beginPath();
    for (const line of projected.ground) {
      const from = map(line.from);
      const to = map(line.to);
      context.moveTo(from.x, from.y);
      context.lineTo(to.x, to.y);
    }
    context.strokeStyle = palette.grid;
    context.lineWidth = 1;
    context.stroke();
  }

  tracePolygon(context, projected.deck, map);
  context.fillStyle = palette.fill;
  context.fill();

  let nearest = -Infinity;
  let furthest = Infinity;
  for (const segment of projected.segments) {
    const mid = (segment.from.depth + segment.to.depth) / 2;
    if (mid > nearest) nearest = mid;
    if (mid < furthest) furthest = mid;
  }
  const range = Math.max(nearest - furthest, 1e-6);
  const heavy = detail === "small" ? 0.6 : 1;

  const sorted = projected.segments
    .map((segment) => ({ segment, depth: (segment.from.depth + segment.to.depth) / 2 }))
    .sort((left, right) => left.depth - right.depth);

  for (const { segment, depth } of sorted) {
    // 0 at the far edge of this hull, 1 at the near one.
    const lit = (depth - furthest) / range;
    const channel = (index: 0 | 1 | 2) =>
      Math.round(palette.far[index] + (palette.near[index] - palette.far[index]) * lit);
    const from = map(segment.from);
    const to = map(segment.to);
    context.strokeStyle = `rgb(${channel(0)}, ${channel(1)}, ${channel(2)})`;
    context.lineWidth = (0.6 + lit * 0.85) * heavy * (segment.kind === "outline" ? 1.5 : 1);
    context.globalAlpha = (0.5 + lit * 0.5) * (segment.kind === "structure" || segment.kind === "keel" ? 0.72 : 1);
    context.beginPath();
    context.moveTo(from.x, from.y);
    context.lineTo(to.x, to.y);
    context.stroke();
  }
  context.globalAlpha = 1;

  for (const mount of projected.mounts) {
    const point = map(mount);
    context.beginPath();
    context.arc(point.x, point.y, mount.size === "LARGE" ? 3.2 : 2.2, 0, Math.PI * 2);
    context.strokeStyle = palette.accent;
    context.lineWidth = 1;
    context.stroke();
  }

  // The bow: the one bright point, so which way the ship is facing is never in question.
  if (projected.nose) {
    const nose = map(projected.nose);
    context.fillStyle = palette.accent;
    context.beginPath();
    context.arc(nose.x, nose.y, detail === "small" ? 1.8 : 2.4, 0, Math.PI * 2);
    context.fill();
  }
}

/** Draws bounded hull geometry derived locally from the user's Starsector installation. */
export function FlightInstrument({ hull = BUNDLED_DEFAULT_HULL, variant = "badge" }: FlightInstrumentProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || typeof ResizeObserver === "undefined") return;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let frame: number | null = null;
    let visible = true;
    let previous = 0;
    let palette = readPalette(canvas);

    /*
     * It turns, all the way round, at the prototype's rate: one revolution in about eighteen
     * seconds. Rocking it back and forth through a narrow arc was tried and reads as a fidget --
     * the ship looks stuck rather than displayed, and half the hull is never shown at all.
     */
    const RATE = 0.34;
    /* The angle a still frame is parked at, for reduced motion and the first paint. */
    const RESTING = variant === "stage" ? 0.52 : 0.38;
    let yaw = RESTING;
    const drawStill = () => drawHull(canvas, hull, yaw, palette, variant);
    const schedule = () => {
      if (frame === null && visible && !reducedMotion.matches) frame = window.requestAnimationFrame(render);
    };

    const render = (time: number) => {
      frame = null;
      if (!visible || reducedMotion.matches) return;
      if (previous === 0) previous = time;
      if (time - previous >= 1000 / 24) {
        // Advance by elapsed time rather than per frame, so a dropped frame or a background tab
        // does not change how fast the ship appears to turn.
        yaw += Math.min(time - previous, 250) / 1000 * RATE;
        previous = time;
        drawHull(canvas, hull, yaw, palette, variant);
      }
      schedule();
    };
    const resize = new ResizeObserver(drawStill);
    resize.observe(canvas);
    const intersection = typeof IntersectionObserver === "undefined" ? null : new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      if (!visible && frame !== null) {
        window.cancelAnimationFrame(frame);
        frame = null;
      } else if (visible) {
        drawStill();
        schedule();
      }
    });
    intersection?.observe(canvas);
    const updateMotion = () => {
      if (frame !== null) window.cancelAnimationFrame(frame);
      frame = null;
      drawStill();
      schedule();
    };
    const theme = new MutationObserver(() => {
      palette = readPalette(canvas);
      drawStill();
    });
    theme.observe(document.documentElement, {
      attributes: true,
      attributeFilter: [...INSTRUMENT_APPEARANCE_ATTRIBUTES],
    });
    reducedMotion.addEventListener("change", updateMotion);
    updateMotion();
    return () => {
      if (frame !== null) window.cancelAnimationFrame(frame);
      resize.disconnect();
      intersection?.disconnect();
      theme.disconnect();
      reducedMotion.removeEventListener("change", updateMotion);
    };
  }, [hull, variant]);

  return (
    <div className={`flight-instrument flight-instrument--${variant}`} aria-hidden="true">
      <div className="flight-instrument__drift">
        {variant === "badge" ? (
          <svg viewBox="0 0 240 150" focusable="false">
            <g className="flight-instrument__scope">
              <ellipse cx="124" cy="76" rx="96" ry="48" />
              <ellipse cx="124" cy="76" rx="70" ry="34" />
              <path d="M18 76h212M124 18v116" />
              <path className="flight-instrument__arc" d="M38 105c33 34 111 40 162-2" />
              <path className="flight-instrument__tick" d="M31 70v12m186-12v12M118 25h12m-12 102h12" />
            </g>
          </svg>
        ) : null}
        <canvas ref={canvasRef} />
      </div>
    </div>
  );
}
