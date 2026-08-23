import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement>;

const defaults = {
  width: 20,
  height: 20,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.8,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
};

export function HomeIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="m4 10 8-6 8 6v9a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z" />
    </svg>
  );
}

export function SparklesIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="m12 3 1.05 3.15L16 7.5l-2.95 1.35L12 12l-1.05-3.15L8 7.5l2.95-1.35z" />
      <path d="m18.5 13 .65 1.85L21 15.5l-1.85.65L18.5 18l-.65-1.85L16 15.5l1.85-.65z" />
      <path d="m6 14 .8 2.2L9 17l-2.2.8L6 20l-.8-2.2L3 17l2.2-.8z" />
    </svg>
  );
}

export function LayersIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="m12 3 8 4.5-8 4.5-8-4.5z" />
      <path d="m4 12 8 4.5 8-4.5" />
      <path d="m4 16.5 8 4.5 8-4.5" />
    </svg>
  );
}

export function ShipIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M12 3 16.5 9 19 19l-7-3-7 3L7.5 9z" />
      <path d="M7.5 9 12 12l4.5-3M12 12v4" />
    </svg>
  );
}

export function SidebarIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="m15 5-7 7 7 7" />
    </svg>
  );
}

export function QuestionIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <circle cx="12" cy="12" r="9" />
      <path d="M9.8 9a2.4 2.4 0 0 1 4.7.7c0 2-2.5 2.1-2.5 4" />
      <path d="M12 18h.01" />
    </svg>
  );
}

export function ClockIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </svg>
  );
}

export function FocusIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M8 4H4v4M16 4h4v4M20 16v4h-4M8 20H4v-4" />
    </svg>
  );
}

export function SettingsIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <circle cx="12" cy="12" r="3" />
      <path d="M19 12a7 7 0 0 0-.1-1.2l2-1.6-2-3.4-2.5 1a7 7 0 0 0-2-1.2L14 3h-4l-.4 2.6a7 7 0 0 0-2 1.2l-2.5-1-2 3.4 2 1.6A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.6 2 3.4 2.5-1a7 7 0 0 0 2 1.2L10 21h4l.4-2.6a7 7 0 0 0 2-1.2l2.5 1 2-3.4-2-1.6c.1-.4.1-.8.1-1.2Z" />
    </svg>
  );
}

export function FolderIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M3.5 7.5h6l2-2h9v13h-17z" />
      <path d="M3.5 9.5h17" />
    </svg>
  );
}

export function RefreshIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M20 11a8 8 0 0 0-14.5-4L4 9" />
      <path d="M4 4v5h5" />
      <path d="M4 13a8 8 0 0 0 14.5 4L20 15" />
      <path d="M20 20v-5h-5" />
    </svg>
  );
}

export function RotateClockwiseIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M20 11a8 8 0 1 0-2.3 5.7" />
      <path d="M20 6v5h-5" />
    </svg>
  );
}

export function RotateCounterClockwiseIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M4 11a8 8 0 1 1 2.3 5.7" />
      <path d="M4 6v5h5" />
    </svg>
  );
}

export function SystemThemeIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <rect x="3" y="4" width="18" height="13" rx="1.5" />
      <path d="M8 21h8M12 17v4" />
    </svg>
  );
}

export function SunIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <circle cx="12" cy="12" r="3.5" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}

export function MoonIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M20 15.2A8.2 8.2 0 0 1 8.8 4 8.2 8.2 0 1 0 20 15.2Z" />
    </svg>
  );
}

export function PlayIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path fill="currentColor" stroke="none" d="M8 5.5v13l10-6.5z" />
    </svg>
  );
}

export function PauseIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M9 6v12M15 6v12" />
    </svg>
  );
}

export function CopyIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <rect x="8" y="8" width="11" height="11" rx="2" />
      <path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" />
    </svg>
  );
}

export function CheckIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="m5 12.5 4.2 4.2L19 7" />
    </svg>
  );
}

export function ShieldIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M12 3 5 6v5c0 4.5 2.8 8 7 10 4.2-2 7-5.5 7-10V6z" />
      <path d="m9 12 2 2 4-4" />
    </svg>
  );
}

export function LifebuoyIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="3.6" />
      <path d="m5.6 5.6 3.9 3.9M18.4 5.6l-3.9 3.9M18.4 18.4l-3.9-3.9M5.6 18.4l3.9-3.9" />
    </svg>
  );
}

export function GaugeIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M4 17a8 8 0 1 1 16 0" />
      <path d="m12 13 4-4" />
      <circle cx="12" cy="17" r="1.4" />
      <path d="M7 17h10" />
    </svg>
  );
}

export function ArrowIcon(props: IconProps) {
  return (
    <svg {...defaults} {...props}>
      <path d="M5 12h13" />
      <path d="m14 8 4 4-4 4" />
    </svg>
  );
}
