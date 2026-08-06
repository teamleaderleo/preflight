import { useCallback, useEffect, useMemo, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { listen } from "@tauri-apps/api/event";
import { getCache, getSnapshot, isDesktopHost, startGame, startPreparation } from "./bridge";
import {
  ArrowIcon,
  CheckIcon,
  ClockIcon,
  FolderIcon,
  HomeIcon,
  PlayIcon,
  RefreshIcon,
  SettingsIcon,
  ShieldIcon,
  SparklesIcon,
} from "./icons";
import Logo from "./Logo";
import type { AppStatus, CacheSnapshot, DesktopSnapshot, PreparationStateEvent, RunStateEvent } from "./types";

type Page = "home" | "prepare";
type TextureStorage = "balanced" | "fastest";

const resourcePresets = {
  gentle: { workers: 2, memoryMib: 128, label: "Gentle" },
  balanced: { workers: 4, memoryMib: 256, label: "Balanced" },
  eager: { workers: 8, memoryMib: 512, label: "Eager" },
} as const;

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = -1;
  do {
    value /= 1024;
    unit += 1;
  } while (value >= 1024 && unit < units.length - 1);
  return `${value.toFixed(value >= 10 ? 1 : 2)} ${units[unit]}`;
}

function shortPath(path: string): string {
  const normalized = path.replaceAll("\\", "/");
  const parts = normalized.split("/").filter(Boolean);
  if (parts.length <= 3) return path;
  return `…/${parts.slice(-3).join("/")}`;
}

function friendlyPlatform(platform: DesktopSnapshot["platform"]): string {
  return { mac: "macOS", windows: "Windows", linux: "Linux", other: "Desktop" }[platform];
}

export default function App() {
  const [snapshot, setSnapshot] = useState<DesktopSnapshot | null>(null);
  const [status, setStatus] = useState<AppStatus>("loading");
  const [message, setMessage] = useState("");
  const [page, setPage] = useState<Page>("home");
  const [cache, setCache] = useState<CacheSnapshot | null>(null);
  const [cacheLoading, setCacheLoading] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const [textureStorage, setTextureStorage] = useState<TextureStorage>("balanced");
  const [resourcePreset, setResourcePreset] = useState<keyof typeof resourcePresets>("balanced");

  const refresh = useCallback(async (game?: string) => {
    setStatus("loading");
    setMessage("");
    try {
      const next = await getSnapshot(game);
      setSnapshot(next);
      setStatus(next.ready ? "ready" : "setup");
    } catch (error) {
      setStatus("error");
      setMessage(String(error));
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<RunStateEvent>("run-state", ({ payload }) => {
      if (payload.state === "finished") {
        setStatus(snapshot?.ready ? "ready" : "setup");
        setMessage(payload.success ? "Welcome back. Your run was tucked away safely." : "The game closed with an error. Your run notes are still safe.");
        void refresh(snapshot?.selected?.installRoot);
      }
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, [refresh, snapshot?.ready, snapshot?.selected?.installRoot]);

  const refreshCache = useCallback(async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setCacheLoading(true);
    try {
      setCache(await getCache(game));
    } catch (error) {
      setMessage(String(error));
    } finally {
      setCacheLoading(false);
    }
  }, [snapshot?.selected?.installRoot]);

  useEffect(() => {
    if (page === "prepare") void refreshCache();
  }, [page, refreshCache]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<PreparationStateEvent>("prepare-state", ({ payload }) => {
      if (payload.state === "finished") {
        setPreparing(false);
        setMessage(payload.success
          ? "Preparation is complete. The current profile is warm and ready."
          : payload.detail ?? "Preparation stopped before it completed.");
        void refreshCache();
      }
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, [refreshCache]);

  const chooseInstall = async () => {
    if (!isDesktopHost()) {
      await refresh("/Applications/Starsector");
      return;
    }
    const selected = await open({
      directory: true,
      multiple: false,
      title: "Choose your Starsector folder",
    });
    if (typeof selected === "string") {
      await refresh(selected);
    }
  };

  const launch = async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setStatus("running");
    setMessage("Preflight is opening the hangar…");
    try {
      await startGame(game);
      setMessage("Starsector is running. Preflight will keep the porch light on.");
    } catch (error) {
      setStatus("error");
      setMessage(String(error));
    }
  };

  const prepare = async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    const resources = resourcePresets[resourcePreset];
    setPreparing(true);
    setMessage("Preparing the exact current profile… You can leave this window open.");
    try {
      await startPreparation(game, textureStorage, resources.workers, resources.memoryMib);
      if (!isDesktopHost()) {
        setPreparing(false);
        setMessage("Preview preparation complete.");
        await refreshCache();
      }
    } catch (error) {
      setPreparing(false);
      setMessage(String(error));
    }
  };

  const isReady = Boolean(snapshot?.ready && snapshot.selected);
  const title = useMemo(() => {
    if (page === "prepare") return preparing ? "Warming the flight deck…" : "Prepare your profile";
    if (status === "loading") return "Checking the launch pad…";
    if (status === "running") return "You’re cleared for adventure";
    if (isReady) return "Your launch pad is cozy and ready";
    return "Let’s find your Starsector home";
  }, [isReady, page, preparing, status]);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Logo />
        <nav className="nav" aria-label="Main navigation">
          <button className={`nav__item ${page === "home" ? "nav__item--active" : ""}`} type="button" aria-current={page === "home" ? "page" : undefined} onClick={() => setPage("home")}>
            <HomeIcon />
            <span>Home</span>
          </button>
          <button className={`nav__item ${page === "prepare" ? "nav__item--active" : ""}`} type="button" aria-current={page === "prepare" ? "page" : undefined} onClick={() => setPage("prepare")} disabled={!isReady}>
            <SparklesIcon />
            <span>Prepare</span>
          </button>
          <button className="nav__item" type="button" disabled title="Coming in the next desktop slice">
            <ClockIcon />
            <span>Runs</span>
            <small>Soon</small>
          </button>
        </nav>
        <div className="sidebar__footer">
          <button className="nav__item" type="button" disabled title="Coming in the next desktop slice">
            <SettingsIcon />
            <span>Settings</span>
          </button>
          <div className="alpha-pill"><span /> Desktop alpha</div>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div>
            <p className="eyebrow">Good evening, captain</p>
            <h1>{title}</h1>
          </div>
          <button className="icon-button" type="button" onClick={() => void refresh(snapshot?.selected?.installRoot)} aria-label="Refresh installation status" disabled={status === "loading"}>
            <RefreshIcon className={status === "loading" ? "spin" : ""} />
          </button>
        </header>

        {page === "home" ? <>
        <section className={`hero card ${isReady ? "hero--ready" : "hero--setup"}`}>
          <div className="hero__copy">
            <div className={`status-chip ${isReady ? "status-chip--ready" : ""}`}>
              {isReady ? <CheckIcon /> : <SparklesIcon />}
              {status === "running" ? "Game running" : isReady ? "All systems comfy" : "A tiny bit of setup"}
            </div>
            <h2>{isReady ? "Ready when you are." : "Show Preflight where the game lives."}</h2>
            <p>
              {isReady
                ? "Preflight found your installation and can launch it with run notes enabled. Your game files, mods, and saves stay exactly where they are."
                : "Pick the folder that contains Starsector. Preflight will check it gently and remember the way back."}
            </p>
            <div className="hero__actions">
              {isReady ? (
                <button className="button button--primary" type="button" onClick={() => void launch()} disabled={status === "running" || status === "loading" || preparing}>
                  <PlayIcon />
                  {status === "running" ? "Starsector is running" : "Launch Starsector"}
                </button>
              ) : (
                <button className="button button--primary" type="button" onClick={() => void chooseInstall()} disabled={status === "loading"}>
                  <FolderIcon />
                  Choose game folder
                </button>
              )}
              {isReady && (
                <button className="button button--quiet" type="button" onClick={() => void chooseInstall()}>
                  Choose another
                </button>
              )}
            </div>
          </div>
          <div className="hero__art" aria-hidden="true">
            <div className="orbit orbit--outer" />
            <div className="orbit orbit--inner" />
            <div className="moon" />
            <div className="ship">
              <span className="ship__window" />
              <span className="ship__flame" />
            </div>
            <span className="star star--one">✦</span>
            <span className="star star--two">✦</span>
            <span className="star star--three">·</span>
          </div>
        </section>

        {message && (
          <div className={`notice ${status === "error" ? "notice--error" : ""}`} role="status">
            <span>{status === "error" ? "!" : "✦"}</span>
            <p>{message}</p>
            {status === "error" && <button type="button" onClick={() => void refresh()}>Try again</button>}
          </div>
        )}

        <div className="content-grid">
          <section className="card installation-card">
            <div className="card__heading">
              <div>
                <p className="eyebrow">Game home</p>
                <h2>Installation</h2>
              </div>
              <div className={`tiny-status ${isReady ? "tiny-status--good" : ""}`}>
                <span />
                {isReady ? "Found" : "Not found"}
              </div>
            </div>
            {isReady && snapshot?.selected ? (
              <div className="install-detail">
                <div className="install-icon"><FolderIcon /></div>
                <div>
                  <strong>{shortPath(snapshot.selected.installRoot)}</strong>
                  <span>{friendlyPlatform(snapshot.platform)} · {snapshot.selected.kind.replace("-", " ")}</span>
                </div>
                <button type="button" className="text-button" onClick={() => void chooseInstall()} aria-label="Change Starsector installation">
                  Change <ArrowIcon />
                </button>
              </div>
            ) : (
              <div className="empty-detail">
                <div className="install-icon"><FolderIcon /></div>
                <div><strong>No folder chosen yet</strong><span>Automatic discovery didn’t find a launcher.</span></div>
              </div>
            )}
          </section>

          <section className="card next-card">
            <div className="card__heading">
              <div>
                <p className="eyebrow">Ready now</p>
                <h2>Prepare your voyage</h2>
              </div>
              <SparklesIcon className="heading-sparkle" />
            </div>
            <p>Warm the safe caches before launch and get a clear, plain-English summary of what changed.</p>
            <div className="feature-row">
              <span className="feature-dot feature-dot--peach" />
              <div><strong>One gentle button</strong><span>Useful defaults, details when you want them</span></div>
              <button className="text-button" type="button" onClick={() => setPage("prepare")}>Open <ArrowIcon /></button>
            </div>
          </section>
        </div>

        <section className="safety card">
          <div className="safety__icon"><ShieldIcon /></div>
          <div>
            <strong>Your save is sacred.</strong>
            <p>Preflight observes launches and builds its own caches. It never edits Starsector, your mods, or your save files.</p>
          </div>
          <span className="safety__check"><CheckIcon /> Read-only by design</span>
        </section>
        </> : (
          <div className="prepare-page">
            <section className="card prepare-intro">
              <div>
                <p className="eyebrow">Exact current mod profile</p>
                <h2>Build once, reuse safely</h2>
                <p>Preflight prepares content-addressed caches outside the game. Changed game or mod files select new identities; missing or rejected entries use the original loader.</p>
              </div>
              <div className={`tiny-status ${cache?.currentProfileFingerprint ? "tiny-status--good" : ""}`}>
                <span />
                {cacheLoading ? "Checking" : cache?.currentProfileFingerprint ? "Profile detected" : "Not prepared"}
              </div>
            </section>

            <div className="prepare-grid">
              <section className="card prepare-options">
                <div className="card__heading">
                  <div><p className="eyebrow">Space and speed</p><h2>Texture storage</h2></div>
                </div>
                <label className={`choice-card ${textureStorage === "balanced" ? "choice-card--selected" : ""}`}>
                  <input type="radio" name="texture-storage" checked={textureStorage === "balanced"} onChange={() => setTextureStorage("balanced")} />
                  <span><strong>Balanced</strong><small>Recommended · exact lossless LZ4 with raw storage where compression barely helps</small></span>
                  <b>Default</b>
                </label>
                <label className={`choice-card ${textureStorage === "fastest" ? "choice-card--selected" : ""}`}>
                  <input type="radio" name="texture-storage" checked={textureStorage === "fastest"} onChange={() => setTextureStorage("fastest")} />
                  <span><strong>Fastest</strong><small>Keeps every upload-ready pixel array raw; typically several GB more for a few hundred ms</small></span>
                </label>

                <div className="resource-heading"><strong>Preparation resources</strong><span>Only affects the one-time build</span></div>
                <div className="preset-row">
                  {Object.entries(resourcePresets).map(([id, preset]) => (
                    <button key={id} type="button" className={resourcePreset === id ? "preset preset--selected" : "preset"} onClick={() => setResourcePreset(id as keyof typeof resourcePresets)}>
                      <strong>{preset.label}</strong><span>{preset.workers} workers · {preset.memoryMib} MiB</span>
                    </button>
                  ))}
                </div>
              </section>

              <section className="card storage-card">
                <div className="card__heading">
                  <div><p className="eyebrow">On this computer</p><h2>Preflight storage</h2></div>
                  <button className="icon-button icon-button--small" type="button" onClick={() => void refreshCache()} aria-label="Refresh cache storage" disabled={cacheLoading}><RefreshIcon className={cacheLoading ? "spin" : ""} /></button>
                </div>
                <strong className="storage-total">{cache ? formatBytes(cache.total.bytes) : "—"}</strong>
                <span className="storage-files">{cache ? `${cache.total.files.toLocaleString()} files` : "Reading cache…"}</span>
                <div className="storage-groups">
                  {(cache?.groups ?? []).map((group) => (
                    <div key={group.id}><span>{group.id}</span><strong>{formatBytes(group.bytes)}</strong></div>
                  ))}
                </div>
                <p className="storage-note">Acceleration data and diagnostic evidence are tracked separately. Cleanup is always previewed before deletion.</p>
              </section>
            </div>

            <section className="card prepare-action">
              <div><strong>{preparing ? "Preparation is running" : "Ready to warm this profile"}</strong><span>{textureStorage === "balanced" ? "Balanced storage selected" : "Fastest raw storage selected"} · {resourcePresets[resourcePreset].label.toLowerCase()} resource use</span></div>
              <button className="button button--primary" type="button" onClick={() => void prepare()} disabled={preparing || !isReady}>
                <SparklesIcon />{preparing ? "Preparing…" : "Prepare current profile"}
              </button>
            </section>
          </div>
        )}

        <footer>
          <span>Preflight {snapshot?.engineVersion ?? "…"}</span>
          <span>Made with care for long mod lists</span>
        </footer>
      </main>
    </div>
  );
}
