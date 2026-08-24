import { useEffect, useLayoutEffect, useRef } from "react";
import { INSTRUMENT_APPEARANCE_ATTRIBUTES } from "../flightInstrumentAppearance";
import { useInstrumentMotion } from "../useInstrumentMotion";
import {
  MAX_INSTRUMENT_ZOOM,
  MIN_INSTRUMENT_ZOOM,
  useInstrumentView,
} from "../useInstrumentView";
import type { HullSegmentKind } from "../wireframeHullGeometry";
import type { WireframeHull, WireframePoint } from "../types";
import { BUNDLED_DEFAULT_HULL } from "../bundledWireframeHulls";
import { projectHull } from "../wireframeHullGeometry";

interface FlightInstrumentProps {
  hull?: WireframeHull;
  /**
   * `badge` is the compact readout. `stage` fills its container so the ship can be the subject.
   * Neither adds a targeting reticle; the surrounding page already supplies enough structure.
   */
  variant?: "badge" | "stage";
  /** Lets the large Home and Hangar displays rotate directly under pointer or arrow-key input. */
  interactive?: boolean;
  /** Composition-specific breathing room without changing the player's saved zoom. */
  framing?: number;
  /** Static secondary readouts should not compete with the primary Home and Hangar displays. */
  animate?: boolean;
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

function normalizeYaw(yaw: number): number {
  return Math.atan2(Math.sin(yaw), Math.cos(yaw));
}

const ROTATION_RATE = 0.34;
let sharedRotationYaw: number | null = null;
let sharedRotationTime: number | null = null;
let sharedRotationDirection: "clockwise" | "counter-clockwise" = "clockwise";

function rotationSign(direction: "clockwise" | "counter-clockwise"): number {
  return direction === "clockwise" ? 1 : -1;
}

/**
 * Home and Hangar are two views of one display, so they read one clock. The angle advances by
 * wall time rather than by frames. WebKit may stop delivering animation frames to an inactive
 * window, but the first paint after returning still lands at the angle the ship has reached.
 */
function readSharedRotation(
  now: number,
  direction: "clockwise" | "counter-clockwise",
  seed: number,
): number {
  if (sharedRotationYaw === null || sharedRotationTime === null) {
    sharedRotationYaw = normalizeYaw(seed);
    sharedRotationTime = now;
    sharedRotationDirection = direction;
    return sharedRotationYaw;
  }
  const elapsed = Math.max(0, now - sharedRotationTime);
  sharedRotationYaw = normalizeYaw(
    sharedRotationYaw + elapsed / 1000 * ROTATION_RATE * rotationSign(sharedRotationDirection),
  );
  sharedRotationTime = now;
  sharedRotationDirection = direction;
  return sharedRotationYaw;
}

function writeSharedRotation(
  yaw: number,
  now: number,
  direction: "clockwise" | "counter-clockwise",
): number {
  sharedRotationYaw = normalizeYaw(yaw);
  sharedRotationTime = now;
  sharedRotationDirection = direction;
  return sharedRotationYaw;
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
 * Weight and opacity by what an edge is, on top of what depth already does to it.
 *
 * Depth alone gives every edge in the same plane the same weight, and the silhouette then has to
 * compete with the deck plating drawn a few pixels inside it. Reading the ship's outer edge first
 * and its interior second is most of what makes a wireframe legible, so the outline is drawn at
 * better than twice the interior's weight and the bracing that holds the side panels together is
 * dropped well back -- it is there to say the side is a surface, not to be read line by line.
 */
const EDGE_WEIGHT: Record<HullSegmentKind, number> = {
  outline: 2.1,
  deck: 1,
  keel: 0.85,
  structure: 0.7,
  engine: 1,
};

const EDGE_ALPHA: Record<HullSegmentKind, number> = {
  outline: 1,
  deck: 0.92,
  keel: 0.6,
  structure: 0.5,
  engine: 1,
};

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
  pitch: number,
  palette: InstrumentPalette,
  variant: "badge" | "stage",
  zoom: number,
  framing: number,
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
  const projected = projectHull(hull, yaw, detail, pitch);
  if (projected.segments.length === 0) return;

  /*
   * One fixed camera, not a fit to what happens to be on screen this frame.
   *
   * Refitting per frame was tried and it is why the ship appeared to zoom and drift while it
   * turned: a rotating hull's projected bounding box breathes, so re-deriving the scale from it
   * pumps the whole picture on every frame. The geometry is already normalised to one frame,
   * which is what makes a constant work here for a stubby Hammerhead and a long Conquest alike.
   */
  const scale = Math.min(width, height) * (variant === "stage" ? 0.7 : 0.46) * zoom * framing;
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
    context.lineWidth = (0.6 + lit * 0.85) * heavy * EDGE_WEIGHT[segment.kind];
    context.globalAlpha = (0.5 + lit * 0.5) * EDGE_ALPHA[segment.kind];
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

}

/** Draws bounded hull geometry derived locally from the user's Starsector installation. */
export function FlightInstrument({
  hull = BUNDLED_DEFAULT_HULL,
  variant = "badge",
  interactive = false,
  framing = 1,
  animate = true,
}: FlightInstrumentProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const { motion, direction } = useInstrumentMotion();
  const instrumentView = useInstrumentView();
  const directionRef = useRef(direction);

  useEffect(() => {
    directionRef.current = direction;
  }, [direction]);

  useLayoutEffect(() => {
    const canvas = canvasRef.current;
    const root = rootRef.current;
    if (!canvas || !root || typeof ResizeObserver === "undefined") return;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let frame: number | null = null;
    let visible = true;
    let lastPaint = performance.now();
    let dragging = false;
    let dragX = 0;
    let dragY = 0;
    let zoom = instrumentView.zoom;
    let palette = readPalette(canvas);

    /*
     * It turns, all the way round, at the prototype's rate: one revolution in about eighteen
     * seconds. Rocking it back and forth through a narrow arc was tried and reads as a fidget --
     * the ship looks stuck rather than displayed, and half the hull is never shown at all.
     */
    /* Every mounted display begins at the shared current angle, not at a page-local phase. */
    let yaw = readSharedRotation(
      performance.now(),
      directionRef.current,
      variant === "stage" ? instrumentView.yaw : instrumentView.yaw - 0.14,
    );
    let pitch = instrumentView.pitch;
    const drawStill = () => {
      if (!dragging && animate && motion === "rotate" && !reducedMotion.matches) {
        yaw = readSharedRotation(performance.now(), directionRef.current, yaw);
      }
      drawHull(canvas, hull, yaw, pitch, palette, variant, zoom, framing);
      lastPaint = performance.now();
    };
    const schedule = () => {
      if (frame === null && visible && animate && motion === "rotate" && !reducedMotion.matches) {
        frame = window.requestAnimationFrame(render);
      }
    };

    const render = (time: number) => {
      frame = null;
      if (!visible || dragging || !animate || motion !== "rotate" || reducedMotion.matches) return;
      yaw = readSharedRotation(time, directionRef.current, yaw);
      drawHull(canvas, hull, yaw, pitch, palette, variant, zoom, framing);
      lastPaint = performance.now();
      schedule();
    };
    const resize = new ResizeObserver(drawStill);
    resize.observe(canvas);
    let pixelRatioQuery: MediaQueryList | null = null;
    let pixelRatioListener: (() => void) | null = null;
    const clearPixelRatioListener = () => {
      if (pixelRatioQuery && pixelRatioListener) {
        pixelRatioQuery.removeEventListener("change", pixelRatioListener);
      }
      pixelRatioQuery = null;
      pixelRatioListener = null;
    };
    const watchPixelRatio = () => {
      clearPixelRatioListener();
      const ratio = window.devicePixelRatio || 1;
      const query = window.matchMedia(`(resolution: ${ratio}dppx)`);
      const onChange = () => {
        clearPixelRatioListener();
        drawStill();
        watchPixelRatio();
      };
      pixelRatioQuery = query;
      pixelRatioListener = onChange;
      query.addEventListener("change", onChange, { once: true });
    };
    watchPixelRatio();
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
    const resumeImmediately = () => {
      // WKWebView may discard a queued frame while its window is inactive. Read the shared
      // wall-time clock and paint it synchronously before returning from the focus event.
      if (dragging || !animate || motion !== "rotate" || reducedMotion.matches) return;
      const bounds = canvas.getBoundingClientRect();
      visible = (bounds.width > 0 && bounds.height > 0
          && bounds.bottom > 0 && bounds.right > 0
          && bounds.top < window.innerHeight && bounds.left < window.innerWidth)
        || (bounds.width === 0 && bounds.height === 0 && canvas.clientWidth > 0 && canvas.clientHeight > 0);
      if (!visible) return;
      if (frame !== null) window.cancelAnimationFrame(frame);
      frame = null;
      drawStill();
      schedule();
    };
    const repairStaleFrame = () => {
      if (performance.now() - lastPaint > 50) resumeImmediately();
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
    const beginDrag = (event: PointerEvent) => {
      if (!interactive || event.button !== 0) return;
      dragging = true;
      yaw = readSharedRotation(performance.now(), directionRef.current, yaw);
      dragX = event.clientX;
      dragY = event.clientY;
      root.dataset.dragging = "true";
      root.setPointerCapture?.(event.pointerId);
      event.preventDefault();
    };
    const moveDrag = (event: PointerEvent) => {
      if (!dragging) return;
      const delta = event.clientX - dragX;
      const vertical = event.clientY - dragY;
      dragX = event.clientX;
      dragY = event.clientY;
      yaw += delta * 0.012;
      pitch = Math.min(1.46, Math.max(0.08, pitch - vertical * 0.008));
      yaw = writeSharedRotation(yaw, performance.now(), directionRef.current);
      drawHull(canvas, hull, yaw, pitch, palette, variant, zoom, framing);
      event.preventDefault();
    };
    const finishDrag = (event: PointerEvent) => {
      if (!dragging) return;
      dragging = false;
      delete root.dataset.dragging;
      if (root.hasPointerCapture?.(event.pointerId)) root.releasePointerCapture?.(event.pointerId);
      yaw = writeSharedRotation(yaw, performance.now(), directionRef.current);
      instrumentView.setView({ yaw: normalizeYaw(yaw), pitch, zoom });
      schedule();
    };
    const zoomFromWheel = (event: WheelEvent) => {
      if (!interactive) return;
      const direction = Math.sign(event.deltaY);
      if (direction === 0) return;
      zoom = Math.min(MAX_INSTRUMENT_ZOOM, Math.max(MIN_INSTRUMENT_ZOOM, zoom - direction * 0.08));
      drawHull(canvas, hull, yaw, pitch, palette, variant, zoom, framing);
      instrumentView.setView({ yaw: normalizeYaw(yaw), pitch, zoom });
      event.preventDefault();
    };
    const turnFromKeyboard = (event: KeyboardEvent) => {
      if (!interactive || !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) return;
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        yaw += event.key === "ArrowLeft" ? -0.16 : 0.16;
      } else {
        pitch = Math.min(1.46, Math.max(0.08, pitch + (event.key === "ArrowUp" ? 0.1 : -0.1)));
      }
      yaw = writeSharedRotation(yaw, performance.now(), directionRef.current);
      drawHull(canvas, hull, yaw, pitch, palette, variant, zoom, framing);
      instrumentView.setView({ yaw: normalizeYaw(yaw), pitch, zoom });
      event.preventDefault();
    };
    root.addEventListener("pointerdown", beginDrag);
    root.addEventListener("wheel", zoomFromWheel, { passive: false });
    window.addEventListener("pointermove", moveDrag);
    window.addEventListener("pointerup", finishDrag);
    window.addEventListener("pointercancel", finishDrag);
    window.addEventListener("focus", resumeImmediately);
    window.addEventListener("pageshow", resumeImmediately);
    document.addEventListener("visibilitychange", resumeImmediately);
    root.addEventListener("pointerenter", repairStaleFrame);
    window.addEventListener("pointerdown", repairStaleFrame, true);
    root.addEventListener("keydown", turnFromKeyboard);
    updateMotion();
    return () => {
      if (frame !== null) window.cancelAnimationFrame(frame);
      resize.disconnect();
      clearPixelRatioListener();
      intersection?.disconnect();
      theme.disconnect();
      reducedMotion.removeEventListener("change", updateMotion);
      root.removeEventListener("pointerdown", beginDrag);
      root.removeEventListener("wheel", zoomFromWheel);
      window.removeEventListener("pointermove", moveDrag);
      window.removeEventListener("pointerup", finishDrag);
      window.removeEventListener("pointercancel", finishDrag);
      window.removeEventListener("focus", resumeImmediately);
      window.removeEventListener("pageshow", resumeImmediately);
      document.removeEventListener("visibilitychange", resumeImmediately);
      root.removeEventListener("pointerenter", repairStaleFrame);
      window.removeEventListener("pointerdown", repairStaleFrame, true);
      root.removeEventListener("keydown", turnFromKeyboard);
    };
  }, [animate, framing, hull, interactive, instrumentView.pitch, instrumentView.yaw, instrumentView.zoom, motion, variant]);

  return (
    <div
      ref={rootRef}
      className={`flight-instrument flight-instrument--${variant}${interactive ? " flight-instrument--interactive" : ""}`}
      data-motion={motion}
      data-direction={direction}
      aria-hidden={interactive ? undefined : true}
      aria-label={interactive ? "Ship display. Drag to turn, scroll to zoom, or use the arrow keys." : undefined}
      title={interactive ? "Drag to turn · scroll to zoom" : undefined}
      role={interactive ? "group" : undefined}
      tabIndex={interactive ? 0 : undefined}
    >
      <div className="flight-instrument__drift">
        <canvas ref={canvasRef} />
      </div>
    </div>
  );
}
