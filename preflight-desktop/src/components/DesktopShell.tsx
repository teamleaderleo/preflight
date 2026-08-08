import type { AppStatus } from "../types";
import {
  HomeIcon,
  LayersIcon,
  RefreshIcon,
  SettingsIcon,
  ShieldIcon,
  SparklesIcon,
} from "../icons";
import Logo from "../Logo";
import { useEffect, useRef, type ReactNode } from "react";

export type Page = "home" | "launch" | "prepare" | "reports" | "profiles" | "settings";

interface DesktopShellProps {
  page: Page;
  title: string;
  status: AppStatus;
  isReady: boolean;
  updateAvailable: boolean;
  engineVersion: string;
  refreshDisabled: boolean;
  children: ReactNode;
  onPageChange: (page: Page) => void;
  onRefresh: () => void;
}

export function DesktopShell({
  page,
  title,
  status,
  isReady,
  updateAvailable,
  engineVersion,
  refreshDisabled,
  children,
  onPageChange,
  onRefresh,
}: DesktopShellProps) {
  const homeActive = page === "home" || page === "launch";
  const pageViewport = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (pageViewport.current) pageViewport.current.scrollTop = 0;
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
  }, [page]);
  return (
    <div
      className="app-shell"
      onPointerMove={(event) => {
        event.currentTarget.style.setProperty("--grid-x", `${event.clientX}px`);
        event.currentTarget.style.setProperty("--grid-y", `${event.clientY}px`);
      }}
      onPointerLeave={(event) => {
        event.currentTarget.style.setProperty("--grid-x", "-1000px");
        event.currentTarget.style.setProperty("--grid-y", "-1000px");
      }}
    >
      <aside className="sidebar">
        <Logo />
        <nav className="nav" aria-label="Main navigation">
          <button className={`nav__item ${homeActive ? "nav__item--active" : ""}`} type="button" aria-current={homeActive ? "page" : undefined} onClick={() => onPageChange("home")}>
            <HomeIcon /><span>Home</span>
          </button>
          <button className={`nav__item ${page === "prepare" ? "nav__item--active" : ""}`} type="button" aria-current={page === "prepare" ? "page" : undefined} onClick={() => onPageChange("prepare")} disabled={!isReady}>
            <SparklesIcon /><span>Preflight</span>
          </button>
          <button className={`nav__item ${page === "reports" ? "nav__item--active" : ""}`} type="button" aria-current={page === "reports" ? "page" : undefined} onClick={() => onPageChange("reports")}>
            <ShieldIcon /><span>Run reports</span>
          </button>
          <button className={`nav__item ${page === "profiles" ? "nav__item--active" : ""}`} type="button" aria-current={page === "profiles" ? "page" : undefined} onClick={() => onPageChange("profiles")} disabled={!isReady}>
            <LayersIcon /><span>Profiles</span>
          </button>
        </nav>
        <div className="sidebar__footer">
          <button className={`nav__item ${page === "settings" ? "nav__item--active" : ""}`} type="button" aria-current={page === "settings" ? "page" : undefined} onClick={() => onPageChange("settings")}>
            <SettingsIcon /><span>Settings</span>
            {updateAvailable ? <span className="nav__badge">Update</span> : null}
          </button>
          <div className="alpha-pill"><span /> Desktop alpha</div>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <h1>{title}</h1>
          <button className="icon-button" type="button" onClick={onRefresh} aria-label="Refresh installation status" disabled={status === "loading" || refreshDisabled}>
            <RefreshIcon className={status === "loading" ? "spin" : ""} />
          </button>
        </header>
        <div ref={pageViewport} className={`page-viewport page-viewport--${page}`}>{children}</div>
        <footer>
          <span>Preflight {engineVersion}</span>
          <span>Unofficial · Not affiliated with Fractal Softworks</span>
        </footer>
      </main>
    </div>
  );
}
