use crate::desktop_automation_bridge::DesktopAutomationBridge;
use crate::operations::{DesktopSmokeProcess, OperationCoordinator, refuse_update_install};
use crate::{
    EnginePaths, RunStarted, canonical_game_directory, child_error, read_tail, take_deferred_exit,
};
use serde::Serialize;
use serde_json::Value;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::path::BaseDirectory;
use tauri::{AppHandle, Emitter, Manager, State};

#[cfg(target_os = "macos")]
pub(crate) const MACOS_ACCESSIBILITY_SETTINGS: &str =
    "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";
pub(crate) const DESKTOP_SMOKE_CANCELLATION_FILE: &str = "cancel.requested";

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopSmokeStateEvent {
    state: &'static str,
    pid: u32,
    success: Option<bool>,
    detail: Option<String>,
    run_directory: String,
}

#[tauri::command]
pub(crate) fn get_desktop_smoke_probe(app: AppHandle) -> Result<Value, String> {
    let paths = EnginePaths::resolve(&app)?;
    let mut command = paths.command();
    let automation = DesktopAutomationBridge::start(None)?;
    automation.configure(&mut command);
    command.arg("desktop").arg("smoke").arg("probe");
    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() {
        return Err(child_error(
            "Preflight could not inspect desktop-test readiness",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned an unreadable desktop-test probe: {error}"))
}

#[tauri::command]
pub(crate) fn open_desktop_accessibility_settings() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let status = Command::new("/usr/bin/open")
            .arg(MACOS_ACCESSIBILITY_SETTINGS)
            .status()
            .map_err(|error| format!("Could not open macOS Accessibility settings: {error}"))?;
        if status.success() {
            Ok(())
        } else {
            Err(format!(
                "macOS could not open Accessibility settings (exit {}).",
                status.code().unwrap_or(-1)
            ))
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        Err("Accessibility settings are available only on macOS.".to_string())
    }
}

fn desktop_benchmark_scenarios(app: &AppHandle) -> Result<(PathBuf, PathBuf), String> {
    let optimized = app
        .path()
        .resolve(
            "engine/scenarios/campaign-roam.json",
            BaseDirectory::Resource,
        )
        .map_err(|error| format!("Could not resolve the automated-test scenario: {error}"))?;
    let measurement = app
        .path()
        .resolve(
            "engine/scenarios/campaign-roam-measurement-only.json",
            BaseDirectory::Resource,
        )
        .map_err(|error| format!("Could not resolve the benchmark scenario: {error}"))?;
    if !optimized.is_file() || !measurement.is_file() {
        return Err("A packaged benchmark scenario is missing. Reinstall Preflight.".to_string());
    }
    Ok((measurement, optimized))
}

fn desktop_smoke_run_directory(app: &AppHandle) -> Result<PathBuf, String> {
    let home = app
        .path()
        .home_dir()
        .map_err(|error| format!("Could not locate the home directory: {error}"))?;
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "The system clock is before 1970.".to_string())?
        .as_millis();
    Ok(home
        .join(".starsector-preflight")
        .join("runs")
        .join(format!("desktop-smoke-{timestamp}-{}", std::process::id())))
}

#[tauri::command]
pub(crate) fn start_desktop_smoke(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    game: String,
) -> Result<RunStarted, String> {
    let directory = canonical_game_directory(&game)?;
    let (measurement_scenario, optimized_scenario) = desktop_benchmark_scenarios(&app)?;
    let run_directory = desktop_smoke_run_directory(&app)?;
    fs::create_dir_all(&run_directory)
        .map_err(|error| format!("Could not create the automated-test run folder: {error}"))?;
    let run_directory = run_directory
        .canonicalize()
        .map_err(|error| format!("Could not open the automated-test run folder: {error}"))?;
    let paths = EnginePaths::resolve(&app)?;

    let mut running = tracker
        .0
        .lock()
        .map_err(|_| "The launch tracker is unavailable.".to_string())?;
    refuse_update_install(&running)?;
    if running.game.is_some() {
        return Err("Starsector is already running through Preflight.".to_string());
    }
    if running.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before running the automated test.".to_string(),
        );
    }

    let mut command = paths.command();
    let automation = DesktopAutomationBridge::start(Some(&run_directory))?;
    automation.configure(&mut command);
    command
        .arg("desktop")
        .arg("benchmark")
        .arg("launch")
        .arg(measurement_scenario)
        .arg(optimized_scenario)
        .arg(&run_directory)
        .arg("--game")
        .arg(directory)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let child = command
        .spawn()
        .map_err(|error| format!("Could not start the paired benchmark: {error}"))?;
    let pid = child.id();
    running.game = Some(pid);
    running.desktop_smoke = Some(DesktopSmokeProcess {
        pid,
        run_directory: run_directory.clone(),
    });
    drop(running);

    let _ = app.emit(
        "desktop-smoke-state",
        DesktopSmokeStateEvent {
            state: "started",
            pid,
            success: None,
            detail: Some(
                "Starting measurement-only run 1 of 2; the optimized run follows after cleanup."
                    .to_string(),
            ),
            run_directory: run_directory.to_string_lossy().into_owned(),
        },
    );
    watch_desktop_smoke(app, child, run_directory, automation);
    Ok(RunStarted { pid })
}

pub(crate) fn request_desktop_smoke_cancellation(
    process: &DesktopSmokeProcess,
) -> Result<bool, String> {
    let marker = process.run_directory.join(DESKTOP_SMOKE_CANCELLATION_FILE);
    match OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&marker)
    {
        Ok(mut file) => {
            file.write_all(b"cancel\n").map_err(|error| {
                format!("Could not write the automated-test stop request: {error}")
            })?;
            Ok(true)
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            let metadata = marker.symlink_metadata().map_err(|problem| {
                format!("Could not inspect the automated-test stop request: {problem}")
            })?;
            if metadata.file_type().is_symlink() || !metadata.is_file() {
                Err("The automated-test stop request isn't a regular file.".to_string())
            } else {
                Ok(false)
            }
        }
        Err(error) => Err(format!(
            "Could not request a safe stop for the automated game test: {error}"
        )),
    }
}

pub(crate) fn desktop_smoke_cancellation_requested(run_directory: &Path) -> bool {
    let marker = run_directory.join(DESKTOP_SMOKE_CANCELLATION_FILE);
    marker
        .symlink_metadata()
        .is_ok_and(|metadata| metadata.is_file() && !metadata.file_type().is_symlink())
}

#[tauri::command]
pub(crate) fn cancel_desktop_smoke(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
) -> Result<bool, String> {
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The launch tracker is unavailable.".to_string())?;
    let Some(process) = running.desktop_smoke.as_ref() else {
        return Ok(false);
    };
    request_desktop_smoke_cancellation(process)?;
    let pid = process.pid;
    let run_directory = process.run_directory.to_string_lossy().into_owned();
    drop(running);
    let _ = app.emit(
        "desktop-smoke-state",
        DesktopSmokeStateEvent {
            state: "cancelling",
            pid,
            success: None,
            detail: Some("Stopping the exact game process and sealing its evidence…".to_string()),
            run_directory,
        },
    );
    Ok(true)
}

fn watch_desktop_smoke(
    app: AppHandle,
    mut child: Child,
    run_directory: PathBuf,
    automation: DesktopAutomationBridge,
) {
    let pid = child.id();
    std::thread::spawn(move || {
        let _automation = automation;
        let stdout = child
            .stdout
            .take()
            .map(|mut stdout| std::thread::spawn(move || read_tail(&mut stdout, 256 * 1024)));
        let stderr = child
            .stderr
            .take()
            .map(|mut stderr| std::thread::spawn(move || read_tail(&mut stderr, 16 * 1024)));
        let status = child.wait();
        let stdout = stdout
            .and_then(|reader| reader.join().ok())
            .unwrap_or_default();
        let stderr = stderr
            .and_then(|reader| reader.join().ok())
            .unwrap_or_default();
        let cancellation_requested = desktop_smoke_cancellation_requested(&run_directory);
        let (success, detail) = if cancellation_requested {
            desktop_smoke_cancellation_outcome(&status, &stdout, &stderr)
        } else {
            desktop_smoke_outcome(&status, &stdout, &stderr)
        };
        let should_exit = if let Ok(mut running) = app.state::<OperationCoordinator>().0.lock() {
            if running.game == Some(pid) {
                running.game = None;
            }
            if running
                .desktop_smoke
                .as_ref()
                .is_some_and(|process| process.pid == pid)
            {
                running.desktop_smoke = None;
            }
            if cancellation_requested && !success {
                running.exit_after_cleanup = false;
                false
            } else {
                take_deferred_exit(&mut running)
            }
        } else {
            false
        };
        let _ = app.emit(
            "desktop-smoke-state",
            DesktopSmokeStateEvent {
                state: if cancellation_requested && success {
                    "cancelled"
                } else {
                    "finished"
                },
                pid,
                success: Some(success),
                detail,
                run_directory: run_directory.to_string_lossy().into_owned(),
            },
        );
        if should_exit {
            app.exit(0);
        }
    });
}

pub(crate) fn desktop_smoke_cancellation_outcome(
    process_status: &std::io::Result<std::process::ExitStatus>,
    stdout: &[u8],
    stderr: &[u8],
) -> (bool, Option<String>) {
    let receipt: Value = match serde_json::from_slice(stdout) {
        Ok(receipt) => receipt,
        Err(_) => return desktop_smoke_outcome(process_status, stdout, stderr),
    };
    if receipt.pointer("/launch/status").and_then(Value::as_str) == Some("cancelled") {
        return (
            true,
            Some("Automated game test stopped safely after exact-process cleanup.".to_string()),
        );
    }
    desktop_smoke_outcome(process_status, stdout, stderr)
}

pub(crate) fn desktop_smoke_outcome(
    process_status: &std::io::Result<std::process::ExitStatus>,
    stdout: &[u8],
    stderr: &[u8],
) -> (bool, Option<String>) {
    let receipt: Value = match serde_json::from_slice(stdout) {
        Ok(receipt) => receipt,
        Err(error) => {
            let detail = match process_status {
                Ok(_) => child_error(
                    &format!("Preflight returned an unreadable automated-test result: {error}"),
                    stderr,
                ),
                Err(wait) => format!("Could not wait for the automated game test: {wait}"),
            };
            return (false, Some(detail));
        }
    };
    let status = receipt
        .pointer("/launch/status")
        .and_then(Value::as_str)
        .unwrap_or("failed");
    let process_success = process_status
        .as_ref()
        .is_ok_and(|process_status| process_status.success());
    if process_success && status == "passed" {
        return (true, None);
    }
    let diagnostic = receipt
        .pointer("/launch/diagnostics/0")
        .or_else(|| receipt.pointer("/launch/evidence/diagnostics/0"))
        .and_then(Value::as_str);
    let detail = diagnostic
        .map(str::to_string)
        .unwrap_or_else(|| child_error(&format!("Automated game test {status}"), stderr));
    (false, Some(detail))
}
