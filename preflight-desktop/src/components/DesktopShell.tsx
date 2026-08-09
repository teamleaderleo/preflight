import type { AppStatus } from "../types";
import {
  HomeIcon,
  LayersIcon,
  MoonIcon,
  RefreshIcon,
  SettingsIcon,
  ShieldIcon,
  SparklesIcon,
  SunIcon,
  SystemThemeIcon,
} from "../icons";
import type { ThemePreference } from "../useTheme";
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
  theme: ThemePreference;
  children: ReactNode;
  onPageChange: (page: Page) => void;
  onRefresh: () => void;
  onThemeChange: (theme: ThemePreference) => void;
}

export function DesktopShell({
  page,
  title,
  status,
  isReady,
  updateAvailable,
  engineVersion,
  refreshDisabled,
  theme,
  children,
  onPageChange,
  onRefresh,
  onThemeChange,
}: DesktopShellProps) {
  const homeActive = page === "home" || page === "launch";
  const pageViewport = useRef<HTMLDivElement>(null);
  const pageTitle = useRef<HTMLHeadingElement>(null);
  const previousPage = useRef(page);
  useEffect(() => {
    if (pageViewport.current) pageViewport.current.scrollTop = 0;
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
    if (previousPage.current !== page) pageTitle.current?.focus({ preventScroll: true });
    previousPage.current = page;
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
      <a className="skip-link" href="#main-content">Skip to workspace</a>
      <aside className="sidebar">
        <Logo />
        <nav className="nav" aria-label="Main navigation">
          <button className={`nav__item ${homeActive ? "nav__item--active" : ""}`} type="button" title="Home" aria-current={homeActive ? "page" : undefined} onClick={() => onPageChange("home")}>
            <HomeIcon /><span>Home</span>
          </button>
          <button className={`nav__item ${page === "prepare" ? "nav__item--active" : ""}`} type="button" title="Preflight" aria-current={page === "prepare" ? "page" : undefined} onClick={() => onPageChange("prepare")} disabled={!isReady}>
            <SparklesIcon /><span>Preflight</span>
          </button>
          <button className={`nav__item ${page === "reports" ? "nav__item--active" : ""}`} type="button" title="Benchmark" aria-current={page === "reports" ? "page" : undefined} onClick={() => onPageChange("reports")}>
            <ShieldIcon /><span>Benchmark</span>
          </button>
          <button className={`nav__item ${page === "profiles" ? "nav__item--active" : ""}`} type="button" title="Profiles" aria-current={page === "profiles" ? "page" : undefined} onClick={() => onPageChange("profiles")} disabled={!isReady}>
            <LayersIcon /><span>Profiles</span>
          </button>
        </nav>
        <div className="sidebar__footer">
          <button className={`nav__item ${page === "settings" ? "nav__item--active" : ""}`} type="button" title="Settings" aria-current={page === "settings" ? "page" : undefined} onClick={() => onPageChange("settings")}>
            <SettingsIcon /><span>Settings</span>
            {updateAvailable ? <span className="nav__badge">Update</span> : null}
          </button>
        </div>
      </aside>

      <main className="main" id="main-content" tabIndex={-1}>
        <header className="topbar">
          <h1 className="page-title" ref={pageTitle} tabIndex={-1}>{title}</h1>
          <div className="topbar__actions">
            <div className="theme-switch" role="group" aria-label="Color theme">
              <button className={theme === "system" ? "theme-switch__button theme-switch__button--active" : "theme-switch__button"} type="button" title="Use system theme" aria-label="Use system theme" aria-pressed={theme === "system"} onClick={() => onThemeChange("system")}><SystemThemeIcon /></button>
              <button className={theme === "light" ? "theme-switch__button theme-switch__button--active" : "theme-switch__button"} type="button" title="Use light theme" aria-label="Use light theme" aria-pressed={theme === "light"} onClick={() => onThemeChange("light")}><SunIcon /></button>
              <button className={theme === "dark" ? "theme-switch__button theme-switch__button--active" : "theme-switch__button"} type="button" title="Use dark theme" aria-label="Use dark theme" aria-pressed={theme === "dark"} onClick={() => onThemeChange("dark")}><MoonIcon /></button>
            </div>
            <button className="icon-button" type="button" onClick={onRefresh} title="Refresh installation and mod status" aria-label="Refresh installation status" disabled={status === "loading" || refreshDisabled}>
              <RefreshIcon className={status === "loading" ? "spin" : ""} />
            </button>
          </div>
        </header>
        <div key={page} ref={pageViewport} className={`page-viewport page-viewport--${page}`}>{children}</div>
        <footer>
          <span>Preflight {engineVersion}</span>
          <span>Unofficial · Not affiliated with Fractal Softworks</span>
        </footer>
      </main>
    </div>
  );
}
