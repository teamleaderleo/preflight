import { useEffect, useRef } from "react";
import type { WireframeHull, WireframePoint } from "../types";
import { ORIGINAL_HULL } from "../useInstrumentHull";
import { projectHull } from "../wireframeHullGeometry";

interface FlightInstrumentProps {
  hull?: WireframeHull;
}

export const INSTRUMENT_APPEARANCE_ATTRIBUTES = ["data-theme", "data-palette"] as const;

interface InstrumentPalette {
  line: string;
  soft: string;
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

function readPalette(canvas: HTMLCanvasElement): InstrumentPalette {
  const styles = getComputedStyle(canvas);
  return {
    line: styles.getPropertyValue("--instrument-line").trim() || "rgba(87,81,74,.55)",
    soft: styles.getPropertyValue("--instrument-soft").trim() || "rgba(87,81,74,.18)",
    accent: styles.getPropertyValue("--instrument-accent").trim() || "#a76532",
    fill: styles.getPropertyValue("--instrument-fill").trim() || "rgba(167,101,50,.06)",
  };
}

function drawHull(canvas: HTMLCanvasElement, hull: WireframeHull, yaw: number, palette: InstrumentPalette) {
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
  const points = [
    ...projected.deck,
    ...projected.segments.flatMap((segment) => [segment.from, segment.to]),
  ];
  if (points.length === 0) return;
  const minX = Math.min(...points.map((point) => point.x));
  const maxX = Math.max(...points.map((point) => point.x));
  const minY = Math.min(...points.map((point) => point.y));
  const maxY = Math.max(...points.map((point) => point.y));
  const padding = 12;
  const scale = Math.min(
    (width - padding * 2) / Math.max(maxX - minX, 1),
    (height - padding * 2) / Math.max(maxY - minY, 1),
  );
  const offsetX = (width - (maxX - minX) * scale) / 2 - minX * scale;
  const offsetY = (height - (maxY - minY) * scale) / 2 - minY * scale;
  const map = (point: WireframePoint) => ({
    x: offsetX + point.x * scale,
    y: offsetY + point.y * scale,
  });
  tracePolygon(context, projected.deck, map);
  context.fillStyle = palette.fill;
  context.fill();

  const order = ["keel", "structure", "outline", "deck", "engine"] as const;
  for (const kind of order) {
    context.beginPath();
    for (const segment of projected.segments.filter((candidate) => candidate.kind === kind)) {
      const from = map(segment.from);
      const to = map(segment.to);
      context.moveTo(from.x, from.y);
      context.lineTo(to.x, to.y);
    }
    context.strokeStyle = kind === "engine" ? palette.accent : kind === "keel" || kind === "structure" ? palette.soft : palette.line;
    context.lineWidth = kind === "outline" ? 1.8 : kind === "engine" ? 1.4 : 1;
    context.lineJoin = "round";
    context.stroke();
  }

  for (const mount of projected.mounts) {
    const point = map(mount);
    const radius = mount.size === "LARGE" ? 3.2 : 2.2;
    context.beginPath();
    context.arc(point.x, point.y, radius, 0, Math.PI * 2);
    context.strokeStyle = palette.accent;
    context.lineWidth = 1;
    context.stroke();
  }
}

/** Draws bounded hull data read from the user's own Starsector installation. */
export function FlightInstrument({ hull = ORIGINAL_HULL }: FlightInstrumentProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || typeof ResizeObserver === "undefined") return;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let frame: number | null = null;
    let visible = true;
    let previous = 0;
    let palette = readPalette(canvas);

    const drawStill = () => drawHull(canvas, hull, 0.38, palette);
    const schedule = () => {
      if (frame === null && visible && !reducedMotion.matches) frame = window.requestAnimationFrame(render);
    };

    const render = (time: number) => {
      frame = null;
      if (!visible || reducedMotion.matches) return;
      if (time - previous >= 1000 / 24) {
        previous = time;
        drawHull(canvas, hull, 0.38 + Math.sin(time / 5_500) * 0.17, palette);
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
  }, [hull]);

  return (
    <div className="flight-instrument" aria-hidden="true">
      <div className="flight-instrument__drift">
        <svg viewBox="0 0 240 150" focusable="false">
          <g className="flight-instrument__scope">
            <ellipse cx="124" cy="76" rx="96" ry="48" />
            <ellipse cx="124" cy="76" rx="70" ry="34" />
            <path d="M18 76h212M124 18v116" />
            <path className="flight-instrument__arc" d="M38 105c33 34 111 40 162-2" />
            <path className="flight-instrument__tick" d="M31 70v12m186-12v12M118 25h12m-12 102h12" />
          </g>
        </svg>
        <canvas ref={canvasRef} />
      </div>
    </div>
  );
}
