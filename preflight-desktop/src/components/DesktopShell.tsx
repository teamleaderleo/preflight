import "../desktopShell.css";
import type { AppStatus } from "../types";
import { openProjectLink } from "../bridge";
import { SIDEBAR_STORAGE_KEY } from "../desktopStorage";
import {
  HomeIcon,
  LayersIcon,
  MoonIcon,
  QuestionIcon,
  SettingsIcon,
  ShipIcon,
  SidebarIcon,
  SparklesIcon,
  SunIcon,
  SystemThemeIcon,
} from "../icons";
import { PALETTES, type PalettePreference, type ThemePreference } from "../useTheme";
import Logo from "../Logo";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from "react";

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
/* The swatches stay compact, so the readable names live here and in their tooltips. */
const PALETTE_NAMES: Record<PalettePreference, string> = {
  blueprint: "Blueprint",
  hangar: "Hangar",
  ultraviolet: "Ultraviolet",
  airglow: "Airglow",
  phosphor: "Phosphor",
};

const WORKSPACE_SCROLL_KEYS = new Set(["ArrowDown", "ArrowUp", "PageDown", "PageUp", "Home", "End", " "]);

export type Page = "home" | "launch" | "speed" | "mods" | "hangar" | "benchmark" | "help" | "settings";

interface DesktopShellProps {
  page: Page;
  title: string;
  status: AppStatus;
  isReady: boolean;
  updateAvailable: boolean;
  engineVersion: string;
  theme: ThemePreference;
  palette: PalettePreference;
  children: ReactNode;
  onPageChange: (page: Page) => void;
  onThemeChange: (theme: ThemePreference) => void;
  onPaletteChange: (palette: PalettePreference) => void;
}

export function DesktopShell({
  page,
  title,
  status,
  isReady,
  updateAvailable,
  engineVersion,
  theme,
  palette,
  children,
  onPageChange,
  onThemeChange,
  onPaletteChange,
}: DesktopShellProps) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => {
    try {
      return window.localStorage.getItem(SIDEBAR_STORAGE_KEY) === "collapsed";
    } catch {
      return false;
    }
  });
  const homeActive = page === "home" || page === "launch";
  const speedActive = page === "speed" || page === "benchmark";
  const pageViewport = useRef<HTMLDivElement>(null);
  const pageTitle = useRef<HTMLHeadingElement>(null);
  const previousPage = useRef(page);
  const pointerFrame = useRef<number | null>(null);
  const pointerPosition = useRef({ x: -1000, y: -1000 });
  const moveGridHighlight = useCallback((event: ReactPointerEvent<HTMLDivElement>) => {
    const shell = event.currentTarget;
    pointerPosition.current = { x: event.clientX, y: event.clientY };
    // High-polling-rate mice can dispatch several pointer events inside one display frame. The
    // highlight is paint-only, so one style invalidation per frame is the most the eye can use.
    if (pointerFrame.current !== null) return;
    pointerFrame.current = window.requestAnimationFrame(() => {
      pointerFrame.current = null;
      const { x, y } = pointerPosition.current;
      shell.style.setProperty("--grid-x", `${x}px`);
      shell.style.setProperty("--grid-y", `${y}px`);
    });
  }, []);
  const hideGridHighlight = useCallback((event: ReactPointerEvent<HTMLDivElement>) => {
    if (pointerFrame.current !== null) window.cancelAnimationFrame(pointerFrame.current);
    pointerFrame.current = null;
    event.currentTarget.style.setProperty("--grid-x", "-1000px");
    event.currentTarget.style.setProperty("--grid-y", "-1000px");
  }, []);
  const toggleSidebar = useCallback(() => {
    setSidebarCollapsed((current) => {
      const next = !current;
      try {
        window.localStorage.setItem(SIDEBAR_STORAGE_KEY, next ? "collapsed" : "expanded");
      } catch {
        // A denied WebView store only makes the sidebar start expanded next time.
      }
      return next;
    });
  }, []);
  const handOffWorkspaceScroll = useCallback((event: ReactKeyboardEvent<HTMLHeadingElement>) => {
    const workspace = pageViewport.current;
    if (!workspace || !WORKSPACE_SCROLL_KEYS.has(event.key)) return;
    if (workspace.scrollHeight <= workspace.clientHeight + 1) return;
    // Page changes focus the heading so assistive technology hears the new destination. The outer
    // document is intentionally locked, though, so native scroll keys have nowhere to go from the
    // heading. Move focus into the named workspace and let the WebView perform its normal default
    // scroll action; keeping the key event intact preserves platform-native distances and behavior.
    workspace.focus({ preventScroll: true });
  }, []);
  useEffect(() => {
    if (pageViewport.current) pageViewport.current.scrollTop = 0;
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
    if (previousPage.current !== page) pageTitle.current?.focus({ preventScroll: true });
    previousPage.current = page;
  }, [page]);
  useEffect(() => () => {
    if (pointerFrame.current !== null) window.cancelAnimationFrame(pointerFrame.current);
  }, []);
  return (
    <div
      className={`app-shell${sidebarCollapsed ? " app-shell--sidebar-collapsed" : ""}`}
      onPointerMove={moveGridHighlight}
      onPointerLeave={hideGridHighlight}
    >
      <a
        className="skip-link"
        href="#page-workspace"
        onClick={(event) => {
          event.preventDefault();
          const workspace = pageViewport.current;
          const recoveryAction = workspace?.querySelector<HTMLElement>("[role='alert'] button:not([disabled])");
          (recoveryAction ?? workspace)?.focus({ preventScroll: true });
        }}
      >
        Skip to workspace
      </a>
      <aside className="sidebar">
        <div className="sidebar__masthead">
          <Logo compact={sidebarCollapsed} />
          <button
            className="sidebar__collapse"
            type="button"
            aria-label={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
            aria-expanded={!sidebarCollapsed}
            title={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
            onClick={toggleSidebar}
          >
            <SidebarIcon />
          </button>
        </div>
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
          <button className={`nav__item ${page === "hangar" ? "nav__item--active" : ""}`} type="button" title="Hangar" aria-current={page === "hangar" ? "page" : undefined} onClick={() => onPageChange("hangar")} disabled={!isReady}>
            <ShipIcon /><span>Hangar</span>
          </button>
          <button className={`nav__item ${page === "help" ? "nav__item--active" : ""}`} type="button" title="Help" aria-current={page === "help" ? "page" : undefined} onClick={() => onPageChange("help")}>
            <QuestionIcon /><span>Help</span>
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
        <header className={`topbar${homeActive ? " topbar--home" : ""}`}>
          <h1 id="page-title" className="page-title" ref={pageTitle} tabIndex={-1} onKeyDown={handOffWorkspaceScroll}>{title}</h1>
          <div className="topbar__actions">
            <div className="palette-switch" role="group" aria-label="Color palette">
              {PALETTES.map((choice) => (
                <button
                  key={choice}
                  className={`palette-switch__button ${palette === choice ? "palette-switch__button--active" : ""}`}
                  type="button"
                  title={`Use ${PALETTE_NAMES[choice]} palette`}
                  data-tooltip={PALETTE_NAMES[choice]}
                  aria-label={`Use ${PALETTE_NAMES[choice]} palette`}
                  aria-pressed={palette === choice}
                  onClick={() => onPaletteChange(choice)}
                >
                  <span className={`palette-switch__swatch palette-switch__swatch--${choice}`} />
                </button>
              ))}
            </div>
            <div className="theme-switch" role="group" aria-label="Color theme">
              <button className={theme === "system" ? "theme-switch__button theme-switch__button--active" : "theme-switch__button"} type="button" title="Use system theme" data-tooltip="System" aria-label="Use system theme" aria-pressed={theme === "system"} onClick={() => onThemeChange("system")}><SystemThemeIcon /></button>
              <button className={theme === "light" ? "theme-switch__button theme-switch__button--active" : "theme-switch__button"} type="button" title="Use light theme" data-tooltip="Light" aria-label="Use light theme" aria-pressed={theme === "light"} onClick={() => onThemeChange("light")}><SunIcon /></button>
              <button className={theme === "dark" ? "theme-switch__button theme-switch__button--active" : "theme-switch__button"} type="button" title="Use dark theme" data-tooltip="Dark" aria-label="Use dark theme" aria-pressed={theme === "dark"} onClick={() => onThemeChange("dark")}><MoonIcon /></button>
            </div>
          </div>
        </header>
        <div
          key={page}
          id="page-workspace"
          ref={pageViewport}
          role="region"
          aria-labelledby="page-title"
          tabIndex={-1}
          className={`page-viewport page-viewport--${page}`}
        >
          {children}
        </div>
        <footer>
          <span>Preflight {engineVersion}</span>
          {/* Quiet and optional, at the very bottom rather than in the launch flow. */}
          <span className="footer__links">
            <button type="button" onClick={() => void openProjectLink("tip-patreon")}>Support on Patreon</button>
          </span>
          <span>Unofficial · Not affiliated with Fractal Softworks</span>
        </footer>
      </main>
    </div>
  );
}
