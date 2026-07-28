import { useCallback, useEffect, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { open } from "@tauri-apps/plugin-dialog";
import { getSnapshot, isDesktopHost, startGame } from "./bridge";
import {
  CheckIcon,
  ClockIcon,
  FolderIcon,
  PlayIcon,
  RefreshIcon,
  ShieldIcon,
} from "./icons";
import Logo from "./Logo";
import type { AppStatus, DesktopSnapshot, RunStateEvent } from "./types";

function fileName(path: string): string {
  return path.replaceAll("\\", "/").split("/").filter(Boolean).at(-1) ?? path;
}

function compactPath(path: string): string {
  const normalized = path.replaceAll("\\", "/");
  const parts = normalized.split("/").filter(Boolean);
  return parts.length > 4 ? `…/${parts.slice(-4).join("/")}` : path;
}

function formatLastRun(value?: string): string {
  if (!value) return "None recorded";
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return "Recorded";
  return new Intl.DateTimeFormat(undefined, {
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function platformName(platform?: DesktopSnapshot["platform"]): string {
  if (!platform) return "—";
  return { mac: "macOS", windows: "Windows", linux: "Linux", other: "Desktop" }[platform];
}

export default function App() {
  const [snapshot, setSnapshot] = useState<DesktopSnapshot | null>(null);
  const [status, setStatus] = useState<AppStatus>("loading");
  const [message, setMessage] = useState("");
  const [messageKind, setMessageKind] = useState<"info" | "error">("info");
  const [runningPid, setRunningPid] = useState<number | null>(null);
  const selectedGame = useRef<string | undefined>(undefined);
  const finishedRuns = useRef(new Set<number>());
  const startPending = useRef(false);

  const refresh = useCallback(async (game?: string, preserveMessage = false) => {
    setStatus("loading");
    if (!preserveMessage) {
      setMessage("");
      setMessageKind("info");
    }
    try {
      const next = await getSnapshot(game);
      selectedGame.current = next.selected?.installRoot;
      setSnapshot(next);
      setStatus(next.ready ? "ready" : "setup");
    } catch (error) {
      setStatus("error");
      setMessageKind("error");
      setMessage(String(error));
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let cancelled = false;
    let stopListening: (() => void) | undefined;
    void listen<RunStateEvent>("run-state", ({ payload }) => {
      if (payload.state === "started") {
        if (!finishedRuns.current.has(payload.pid)) {
          setRunningPid(payload.pid);
        }
        return;
      }
      if (payload.state === "finished") {
        if (startPending.current) {
          finishedRuns.current.add(payload.pid);
        }
        setRunningPid((current) => current === payload.pid ? null : current);
        setMessageKind(payload.success ? "info" : "error");
        setMessage(
          payload.success
            ? "Run finished"
            : payload.detail || "Starsector exited with an error",
        );
        void refresh(selectedGame.current, true);
      }
    }).then((unlisten) => {
      if (cancelled) {
        unlisten();
      } else {
        stopListening = unlisten;
      }
    });
    return () => {
      cancelled = true;
      stopListening?.();
    };
  }, [refresh]);

  const chooseInstall = async () => {
    if (!isDesktopHost()) {
      await refresh("/Applications/Starsector");
      return;
    }
    const selected = await open({
      directory: true,
      multiple: false,
      title: "Choose Starsector folder",
    });
    if (typeof selected === "string") {
      await refresh(selected);
    }
  };

  const launch = async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setMessage("");
    setMessageKind("info");
    startPending.current = true;
    try {
      const started = await startGame(game);
      startPending.current = false;
      if (finishedRuns.current.delete(started.pid)) {
        setRunningPid((current) => current === started.pid ? null : current);
      } else {
        setRunningPid(started.pid);
      }
    } catch (error) {
      startPending.current = false;
      finishedRuns.current.clear();
      setMessageKind("error");
      setMessage(String(error));
    }
  };

  const ready = Boolean(snapshot?.ready && snapshot.selected);
  const selected = snapshot?.selected;

  return (
    <div className="app">
      <header className="app-header">
        <Logo />
        <div className="header-actions">
          <span className={`engine-status engine-status--${runningPid ? "running" : status}`}>
            <i />
            {runningPid
              ? "Running"
              : status === "loading"
              ? "Checking"
              : ready
                ? "Ready"
                : status === "error"
                  ? "Error"
                  : "Setup required"}
          </span>
          <button
            className="icon-button"
            type="button"
            onClick={() => void refresh(selected?.installRoot)}
            aria-label="Refresh"
            title="Refresh"
            disabled={status === "loading"}
          >
            <RefreshIcon className={status === "loading" ? "spin" : ""} />
          </button>
        </div>
      </header>

      <main className="workspace">
        <section className="launch-panel">
          <div className="panel-index">01 / LAUNCH</div>

          {ready && selected ? (
            <>
              <div className="game-identity">
                <span className="game-identity__kicker">STARSECTOR</span>
                <h1>{fileName(selected.installRoot)}</h1>
                <button className="path-button" type="button" onClick={() => void chooseInstall()}>
                  <FolderIcon />
                  <span>{compactPath(selected.installRoot)}</span>
                  <strong>Change</strong>
                </button>
              </div>

              <button
                className="launch-button"
                type="button"
                aria-label="Launch Starsector"
                onClick={() => void launch()}
                disabled={runningPid !== null || status === "loading"}
              >
                <span className="launch-button__icon"><PlayIcon /></span>
                <span>
                  <small>{runningPid ? "ACTIVE PROCESS" : "PREFLIGHT READY"}</small>
                  <strong>{runningPid ? "Running" : "Launch Starsector"}</strong>
                </span>
              </button>
            </>
          ) : (
            <div className="setup-state">
              <div className="setup-state__icon"><FolderIcon /></div>
              <span>STARSECTOR</span>
              <h1>Installation not found</h1>
              <button className="select-button" type="button" onClick={() => void chooseInstall()} disabled={status === "loading"}>
                Select game folder
              </button>
            </div>
          )}

          <div className="launch-panel__footer">
            <ShieldIcon />
            <span>Game files unchanged</span>
          </div>
        </section>

        <aside className="side-panel">
          <div className="panel-index">02 / STATUS</div>

          <dl className="status-list">
            <div>
              <dt>Platform</dt>
              <dd>{platformName(snapshot?.platform)}</dd>
            </div>
            <div>
              <dt>Launcher</dt>
              <dd title={selected?.launcher}>{selected ? fileName(selected.launcher) : "—"}</dd>
            </div>
            <div>
              <dt>Cache</dt>
              <dd className={snapshot?.cachePresent ? "value-good" : ""}>
                {snapshot?.cachePresent ? <><CheckIcon /> Present</> : "Empty"}
              </dd>
            </div>
            <div>
              <dt>Last run</dt>
              <dd><ClockIcon /> {formatLastRun(snapshot?.lastRun?.modifiedAt)}</dd>
            </div>
          </dl>

          {message && (
            <div className={`message ${messageKind === "error" ? "message--error" : ""}`} role="status">
              <span>{messageKind === "error" ? "!" : "✓"}</span>
              <p>{message}</p>
              {status === "error" && (
                <button type="button" onClick={() => void refresh(selected?.installRoot)}>Retry</button>
              )}
            </div>
          )}

          {!ready && snapshot?.diagnostics[0] && status !== "error" && (
            <p className="diagnostic">{snapshot.diagnostics[0]}</p>
          )}

          <div className="version">
            <span>Engine</span>
            <code>{snapshot?.engineVersion ?? "—"}</code>
          </div>
        </aside>
      </main>
    </div>
  );
}
