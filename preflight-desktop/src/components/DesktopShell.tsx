import type { AppStatus } from "../types";
import { openProjectLink } from "../bridge";
import {
  HomeIcon,
  LayersIcon,
  LifebuoyIcon,
  MoonIcon,
  SettingsIcon,
  SparklesIcon,
  SunIcon,
  SystemThemeIcon,
} from "../icons";
import type { ThemePreference } from "../useTheme";
import Logo from "../Logo";
import { useEffect, useRef, type ReactNode } from "react";

/*
 * Destinations are named for the errand a player arrives with, not for the part of this
 * repository that serves it. The previous set spent three of five primary slots on internal
 * vocabulary -- a page called "Preflight" inside Preflight, a "Benchmark" instrument nobody
 * arrives wanting to run, and "Profiles", which means accounts in most software a player has
 * used. Recovery, the errand behind every "it won't start", was an accordion under the
 * instrument.
 *
 * `benchmark` deliberately has no nav item. It is load-bearing for the project's own claims and
 * still reachable from Speed, where someone asking "is this actually doing anything" is already
 * standing. `launch` works the same way, from Home.
 */
export type Page = "home" | "launch" | "speed" | "mods" | "benchmark" | "help" | "settings";

interface DesktopShellProps {
  page: Page;
  title: string;
  status: AppStatus;
  isReady: boolean;
  updateAvailable: boolean;
  engineVersion: string;
  theme: ThemePreference;
  children: ReactNode;
  onPageChange: (page: Page) => void;
  onThemeChange: (theme: ThemePreference) => void;
}

export function DesktopShell({
  page,
  title,
  status,
  isReady,
  updateAvailable,
  engineVersion,
  theme,
  children,
  onPageChange,
  onThemeChange,
}: DesktopShellProps) {
  const homeActive = page === "home" || page === "launch";
  const speedActive = page === "speed" || page === "benchmark";
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
          <button className={`nav__item ${speedActive ? "nav__item--active" : ""}`} type="button" title="Speed" aria-current={speedActive ? "page" : undefined} onClick={() => onPageChange("speed")} disabled={!isReady}>
            <SparklesIcon /><span>Speed</span>
          </button>
          <button className={`nav__item ${page === "mods" ? "nav__item--active" : ""}`} type="button" title="Mods" aria-current={page === "mods" ? "page" : undefined} onClick={() => onPageChange("mods")} disabled={!isReady}>
            <LayersIcon /><span>Mods</span>
          </button>
          <button className={`nav__item ${page === "help" ? "nav__item--active" : ""}`} type="button" title="Help" aria-current={page === "help" ? "page" : undefined} onClick={() => onPageChange("help")}>
            <LifebuoyIcon /><span>Help</span>
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
          </div>
        </header>
        <div key={page} ref={pageViewport} className={`page-viewport page-viewport--${page}`}>{children}</div>
        <footer>
          <span>Preflight {engineVersion}</span>
          {/*
            * Quiet, unlabelled, and at the very bottom, which is where mod authors put this and
            * where it belongs. Preflight is free and stays free, so a sentence about supporting
            * development would be asking for something the app does not need in order to work --
            * the platform names alone say what the links are.
            */}
          <span className="footer__links">
            <button type="button" onClick={() => void openProjectLink("tip-coffee")}>Buy me a coffee</button>
            <button type="button" onClick={() => void openProjectLink("tip-kofi")}>Ko-fi</button>
            <button type="button" onClick={() => void openProjectLink("tip-patreon")}>Patreon</button>
          </span>
          <span>Unofficial · Not affiliated with Fractal Softworks</span>
        </footer>
      </main>
    </div>
  );
}
