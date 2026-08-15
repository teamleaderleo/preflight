use crate::engine::{EnginePaths, READ_BUDGET, canonical_game_directory};
use crate::operations::{OperationCoordinator, PreparationProcess, refuse_update_install};
use crate::{RunStarted, child_error, read_tail, take_deferred_exit};
use serde::{Deserialize, Serialize};
use serde_json::{Value, from_slice, from_str};
use std::io::{BufRead, BufReader, Read};
use std::process::{Child, Stdio};
use std::sync::mpsc;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};

const PREPARATION_PROGRESS_PREFIX: &str = "PREFLIGHT_PROGRESS ";
const PREPARATION_PROGRESS_FORMAT: &str = "preflight-preparation-progress-v1";

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PreparationStateEvent {
    state: &'static str,
    pid: u32,
    success: Option<bool>,
    detail: Option<String>,
    report: Option<String>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PreparationProgressEvent {
    #[serde(default)]
    pid: u32,
    format: String,
    phase: String,
    state: String,
    total_phases: u32,
    status: Option<String>,
    duration_ms: Option<f64>,
    #[serde(default)]
    metrics: Value,
}

#[tauri::command]
pub(crate) fn start_preparation(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    game: String,
    texture_storage: String,
    workers: u8,
    memory_mib: u32,
) -> Result<RunStarted, String> {
    let directory = canonical_game_directory(&game)?;
    validate_texture_storage(&texture_storage)?;
    validate_workers(workers)?;
    validate_memory(memory_mib)?;
    let paths = EnginePaths::resolve(&app)?;
    let mut running = tracker
        .0
        .lock()
        .map_err(|_| "The preparation tracker is unavailable.".to_string())?;
    refuse_update_install(&running)?;
    if running.preparation.is_some() {
        return Err("This profile is already being prepared.".to_string());
    }
    if running.game.is_some() {
        return Err("Close Starsector before preparing its current profile.".to_string());
    }

    let mut command = paths.command();
    command
        .arg("prepare")
        .arg("--game")
        .arg(directory)
        .args(texture_storage_args(&texture_storage))
        .arg("--workers")
        .arg(workers.to_string())
        .arg("--memory-mb")
        .arg(memory_mib.to_string())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let child = command
        .spawn()
        .map_err(|error| format!("Could not start profile preparation: {error}"))?;
    let pid = child.id();
    let (cancel, cancel_receiver) = mpsc::channel();
    running.preparation = Some(PreparationProcess { pid, cancel });
    drop(running);

    let _ = app.emit(
        "prepare-state",
        PreparationStateEvent {
            state: "started",
            pid,
            success: None,
            detail: None,
            report: None,
        },
    );
    watch_preparation(app, child, cancel_receiver);
    Ok(RunStarted { pid })
}

#[tauri::command]
pub(crate) fn cancel_preparation(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
) -> Result<bool, String> {
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The preparation tracker is unavailable.".to_string())?;
    let Some(preparation) = running.preparation.as_ref() else {
        return Ok(false);
    };
    let pid = preparation.pid;
    preparation
        .cancel
        .send(())
        .map_err(|_| "Preparation has already stopped.".to_string())?;
    drop(running);
    let _ = app.emit(
        "prepare-state",
        PreparationStateEvent {
            state: "cancelling",
            pid,
            success: None,
            detail: None,
            report: None,
        },
    );
    Ok(true)
}

#[tauri::command]
pub(crate) fn get_preparation_plan(
    app: AppHandle,
    game: String,
    texture_storage: String,
    workers: u8,
) -> Result<Value, String> {
    let directory = canonical_game_directory(&game)?;
    validate_texture_storage(&texture_storage)?;
    validate_workers(workers)?;
    // `prepare --plan` describes the texture store, and refuses outright when texture preparation
    // is off. Callers skip the plan for minimal storage rather than gating on one; saying so here
    // keeps that a stated contract instead of an engine error surfaced to the user.
    if texture_storage == MINIMAL_STORAGE {
        return Err("Minimal storage prepares no textures, so it has no storage plan.".to_string());
    }
    let paths = EnginePaths::resolve(&app)?;
    let output = paths
        .command()
        .arg("prepare")
        .arg("--plan")
        .arg("--json")
        .arg("--game")
        .arg(directory)
        .arg("--texture-storage")
        .arg(texture_storage)
        .arg("--workers")
        .arg(workers.to_string())
        .output_within(READ_BUDGET)
        .map_err(|error| format!("Could not calculate preparation storage: {error}"))?;
    if !output.status.success() {
        return Err(child_error(
            "Preflight could not calculate preparation storage",
            &output.stderr,
        ));
    }
    from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned an unreadable storage plan: {error}"))
}

/// `minimal` prepares everything except textures. It is not a texture format, so it is spelled as
/// `--no-textures` rather than as a `--texture-storage` value, and the engine has no plan for it.
const MINIMAL_STORAGE: &str = "minimal";

fn validate_texture_storage(texture_storage: &str) -> Result<(), String> {
    if texture_storage == "balanced"
        || texture_storage == "fastest"
        || texture_storage == MINIMAL_STORAGE
    {
        Ok(())
    } else {
        Err("Texture storage must be balanced, fastest, or minimal.".to_string())
    }
}

fn texture_storage_args(texture_storage: &str) -> Vec<&str> {
    if texture_storage == MINIMAL_STORAGE {
        vec!["--no-textures"]
    } else {
        vec!["--texture-storage", texture_storage]
    }
}

fn validate_workers(workers: u8) -> Result<(), String> {
    if (1..=64).contains(&workers) {
        Ok(())
    } else {
        Err("Preparation workers must be between 1 and 64.".to_string())
    }
}

fn validate_memory(memory_mib: u32) -> Result<(), String> {
    if (16..=65_536).contains(&memory_mib) {
        Ok(())
    } else {
        Err("Preparation memory must be between 16 and 65536 MiB.".to_string())
    }
}

fn watch_preparation(app: AppHandle, mut child: Child, cancel: mpsc::Receiver<()>) {
    let pid = child.id();
    std::thread::spawn(move || {
        let stdout = child
            .stdout
            .take()
            .map(|mut stdout| std::thread::spawn(move || read_tail(&mut stdout, 16 * 1024)));
        let stderr_app = app.clone();
        let stderr = child.stderr.take().map(|stderr| {
            std::thread::spawn(move || read_preparation_stderr(stderr_app, pid, stderr))
        });

        let mut cancelled = false;
        let status = loop {
            if cancel.try_recv().is_ok() {
                cancelled = true;
                let _ = child.kill();
                break child.wait();
            }
            match child.try_wait() {
                Ok(Some(status)) => break Ok(status),
                Ok(None) => std::thread::sleep(Duration::from_millis(50)),
                Err(error) => break Err(error),
            }
        };
        let stdout = stdout
            .and_then(|reader| reader.join().ok())
            .unwrap_or_default();
        let stderr = stderr
            .and_then(|reader| reader.join().ok())
            .unwrap_or_default();
        let success = !cancelled && status.as_ref().is_ok_and(|status| status.success());
        let report = if success {
            let value = String::from_utf8_lossy(&stdout).trim().to_string();
            (!value.is_empty()).then_some(value)
        } else {
            None
        };
        let detail = if cancelled {
            Some(
                "Preparation stopped safely. Finished cache artifacts remain reusable.".to_string(),
            )
        } else {
            match &status {
                Ok(status) if status.success() => None,
                Ok(_) => Some(child_error("Profile preparation failed", &stderr)),
                Err(error) => Some(format!("Could not wait for profile preparation: {error}")),
            }
        };
        let should_exit = if let Ok(mut running) = app.state::<OperationCoordinator>().0.lock() {
            if running
                .preparation
                .as_ref()
                .is_some_and(|process| process.pid == pid)
            {
                running.preparation = None;
            }
            take_deferred_exit(&mut running)
        } else {
            false
        };
        let _ = app.emit(
            "prepare-state",
            PreparationStateEvent {
                state: if cancelled { "cancelled" } else { "finished" },
                pid,
                success: Some(success),
                detail,
                report,
            },
        );
        if should_exit {
            app.exit(0);
        }
    });
}

fn parse_preparation_progress(line: &str, pid: u32) -> Option<PreparationProgressEvent> {
    let json = line.strip_prefix(PREPARATION_PROGRESS_PREFIX)?;
    let mut event: PreparationProgressEvent = from_str(json).ok()?;
    if event.format != PREPARATION_PROGRESS_FORMAT || event.phase.is_empty() {
        return None;
    }
    event.pid = pid;
    Some(event)
}

fn read_preparation_stderr(app: AppHandle, pid: u32, stderr: impl Read) -> Vec<u8> {
    let mut tail = Vec::with_capacity(8 * 1024);
    for line in BufReader::new(stderr).lines() {
        let Ok(line) = line else { break };
        if let Some(progress) = parse_preparation_progress(&line, pid) {
            let _ = app.emit("prepare-progress", progress);
        }
        append_tail(&mut tail, line.as_bytes(), 8 * 1024);
        append_tail(&mut tail, b"\n", 8 * 1024);
    }
    tail
}

fn append_tail(tail: &mut Vec<u8>, bytes: &[u8], limit: usize) {
    if bytes.len() >= limit {
        tail.clear();
        tail.extend_from_slice(&bytes[bytes.len() - limit..]);
        return;
    }
    let overflow = tail.len().saturating_add(bytes.len()).saturating_sub(limit);
    if overflow > 0 {
        tail.drain(..overflow);
    }
    tail.extend_from_slice(bytes);
}

#[cfg(test)]
mod tests {
    use super::{
        parse_preparation_progress, texture_storage_args, validate_memory,
        validate_texture_storage, validate_workers,
    };

    #[test]
    fn accepts_only_versioned_preparation_progress() {
        let parsed = parse_preparation_progress(
            "PREFLIGHT_PROGRESS {\"format\":\"preflight-preparation-progress-v1\",\"phase\":\"textures\",\"state\":\"completed\",\"totalPhases\":7,\"status\":\"SUCCESS\",\"durationMs\":12.5,\"metrics\":{\"builtBlobs\":3}}",
            42,
        )
        .unwrap();
        assert_eq!(42, parsed.pid);
        assert_eq!("textures", parsed.phase);
        assert_eq!(Some("SUCCESS".to_string()), parsed.status);
        assert!(parse_preparation_progress("prepare: textures started", 42).is_none());
    }

    #[test]
    fn preparation_inputs_are_bounded() {
        assert!(validate_texture_storage("balanced").is_ok());
        assert!(validate_texture_storage("fastest").is_ok());
        assert!(validate_texture_storage("minimal").is_ok());
        assert!(validate_texture_storage("compact").is_err());
        assert!(validate_workers(1).is_ok());
        assert!(validate_workers(64).is_ok());
        assert!(validate_workers(0).is_err());
        assert!(validate_memory(16).is_ok());
        assert!(validate_memory(65_536).is_ok());
        assert!(validate_memory(15).is_err());
        assert!(validate_memory(65_537).is_err());
    }

    #[test]
    fn minimal_storage_is_spelled_as_skipping_textures() {
        // `minimal` is a Preflight-side name for "prepare everything but textures". The engine has
        // no such texture-storage value, so passing it through as one would fail the preparation.
        assert_eq!(vec!["--no-textures"], texture_storage_args("minimal"));
        assert_eq!(
            vec!["--texture-storage", "balanced"],
            texture_storage_args("balanced")
        );
        assert_eq!(
            vec!["--texture-storage", "fastest"],
            texture_storage_args("fastest")
        );
    }
}
