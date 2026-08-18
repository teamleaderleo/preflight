use serde::{Deserialize, Serialize};
use std::io::{BufReader, Read};
use std::process::{Child, Stdio};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager, State};

mod automation;
// Kept for the separate, opt-in campaign automation harness. The product startup benchmark never
// constructs this bridge or requests desktop input permissions.
#[allow(dead_code, unused_imports)]
mod desktop_automation_bridge;
mod engine;
mod hull_sprite_tracer;
pub mod hulls;
mod operations;
mod preparation;
// The legacy path stays compiled for compatibility helpers while production uploads use
// report_transport_v2 through the native authority lifecycle. Compatibility-only functions carry
// their own narrow allowance when they have no production caller.
mod report_transport;
mod reports;
mod updates;

use automation::{
    cancel_desktop_smoke, get_desktop_smoke_probe, request_desktop_smoke_cancellation,
    start_desktop_smoke,
};
use engine::{
    EnginePaths, activate_profile, apply_cache_cleanup, apply_evidence_cleanup, apply_removal,
    canonical_game_directory, delete_profile, duplicate_profile, export_automatic_diagnostics,
    export_diagnostics, get_bootstrap, get_cache, get_cache_cleanup, get_cache_health,
    get_cache_inspection, get_evidence_cleanup, get_home_state, get_launch_settings, get_profiles,
    get_removal_plan, get_snapshot, rename_profile, repair_cache, save_profile,
    update_launch_settings,
};
use hulls::get_wireframe_hulls;
use operations::{OperationCoordinator, OperationSnapshot, OperationState, refuse_update_install};
use preparation::{cancel_preparation, get_preparation_plan, start_preparation};
use reports::{cancel_run_report, delete_run_report, get_report_intake_status, send_run_report};
use updates::{UpdateTracker, check_for_update, install_update};

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RunStarted {
    pid: u32,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RunStateEvent {
    state: &'static str,
    pid: u32,
    success: Option<bool>,
    detail: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum AfterLaunchBehavior {
    Keep,
    Minimize,
    Quit,
}

impl AfterLaunchBehavior {
    fn parse(value: &str) -> Result<Self, String> {
        match value {
            "keep" => Ok(Self::Keep),
            "minimize" => Ok(Self::Minimize),
            "quit" => Ok(Self::Quit),
            _ => Err("After-launch behavior must be keep, minimize, or quit.".to_string()),
        }
    }
}

const DESKTOP_RUN_EVENT_PREFIX: &[u8] = b"PREFLIGHT_DESKTOP_EVENT ";
const DESKTOP_RUN_EVENT_MAX_BYTES: usize = 1_024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct DesktopRunEvent {
    format: String,
    state: String,
    pid: u32,
}

#[derive(Clone, Serialize, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct StopGameResult {
    inspected: usize,
    stopped: usize,
    still_running: usize,
    skipped: usize,
    forced: bool,
}

/// The only web addresses Preflight will ask the operating system to open.
///
/// A general "open this URL" command is a webview's most useful gadget if it is ever made to run
/// something it shouldn't, and Preflight has no need for one: every outbound link in the interface
/// is a fixed destination decided here, at build time. The frontend names a key, not a URL, so
/// there is nothing to inject.
const PROJECT_LINKS: [(&str, &str); 6] = [
    ("project", "https://github.com/teamleaderleo/preflight"),
    ("tip-patreon", "https://www.patreon.com/cw/teamleaderleo"),
    (
        "getting-started",
        "https://github.com/teamleaderleo/preflight/blob/main/docs/getting-started.md",
    ),
    (
        "privacy",
        "https://github.com/teamleaderleo/preflight/blob/main/docs/privacy.md",
    ),
    (
        "capabilities",
        "https://github.com/teamleaderleo/preflight/blob/main/docs/capability-receipt.md",
    ),
    (
        "report-issue",
        "https://github.com/teamleaderleo/preflight/issues/new",
    ),
];

#[tauri::command]
fn open_project_link(link: String) -> Result<(), String> {
    let url = project_link_url(&link)?;
    let mut command = if cfg!(target_os = "macos") {
        let mut command = std::process::Command::new("open");
        command.arg(url);
        command
    } else if cfg!(target_os = "windows") {
        // `start` is a shell builtin, and its first quoted argument is taken as the window title.
        let mut command = std::process::Command::new("cmd");
        command.args(["/C", "start", "", url]);
        command
    } else {
        let mut command = std::process::Command::new("xdg-open");
        command.arg(url);
        command
    };
    command
        .spawn()
        .map_err(|error| format!("Could not open the project link: {error}"))?;
    Ok(())
}

fn project_link_url(link: &str) -> Result<&'static str, String> {
    PROJECT_LINKS
        .iter()
        .find_map(|(key, url)| (*key == link).then_some(*url))
        .ok_or_else(|| "Unknown project link.".to_string())
}

#[tauri::command]
async fn launch_game(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    paths: State<'_, EnginePaths>,
    game: String,
    profile: String,
    optimization_preset: String,
    after_launch: String,
) -> Result<RunStarted, String> {
    let behavior = AfterLaunchBehavior::parse(&after_launch)?;
    let game_path = canonical_game_directory(&game)?;
    let mut running = tracker
        .0
        .lock()
        .map_err(|_| "Operation tracker is unavailable.".to_string())?;
    refuse_update_install(&running)?;
    if running.game.is_some() {
        return Err("Starsector is already running from Preflight.".to_string());
    }
    if running.preparation.is_some() {
        return Err("Profile preparation is still running.".to_string());
    }
    if running.benchmark.is_some() {
        return Err("A startup benchmark is still running.".to_string());
    }
    if running.report_upload.is_some() {
        return Err("A run report is still uploading.".to_string());
    }
    let child = engine::start_game(
        &paths,
        &game_path,
        &profile,
        &optimization_preset,
    )?;
    let pid = child.id();
    running.game = Some(operations::GameProcess {
        pid,
        child,
        recovered: false,
    });
    drop(running);
    let _ = app.emit("run-started", RunStarted { pid });
    Ok(RunStarted { pid })
}

#[tauri::command]
fn stop_game(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    force: bool,
) -> Result<StopGameResult, String> {
    let mut running = tracker
        .0
        .lock()
        .map_err(|_| "Operation tracker is unavailable.".to_string())?;
    let Some(game) = running.game.as_mut() else {
        return Ok(StopGameResult {
            inspected: 0,
            stopped: 0,
            still_running: 0,
            skipped: 0,
            forced: force,
        });
    };
    let result = engine::stop_game_process(game, force)?;
    if result.still_running == 0 {
        running.game = None;
    }
    drop(running);
    if result.still_running == 0 {
        let _ = app.emit(
            "run-state",
            RunStateEvent {
                state: "stopped",
                pid: result.stopped_pid.unwrap_or_default(),
                success: Some(true),
                detail: None,
            },
        );
    }
    Ok(StopGameResult {
        inspected: result.inspected,
        stopped: result.stopped,
        still_running: result.still_running,
        skipped: result.skipped,
        forced: force,
    })
}

fn handle_run_output(app: &AppHandle, line: &str) {
    let Some(payload) = line.strip_prefix(std::str::from_utf8(DESKTOP_RUN_EVENT_PREFIX).unwrap()) else {
        return;
    };
    if payload.len() > DESKTOP_RUN_EVENT_MAX_BYTES {
        return;
    }
    let Ok(event) = serde_json::from_str::<DesktopRunEvent>(payload) else {
        return;
    };
    if event.format != "preflight-desktop-event-v1" {
        return;
    }
    let state = match event.state.as_str() {
        "started" => "started",
        "stopped" => "stopped",
        "failed" => "failed",
        _ => return,
    };
    let _ = app.emit(
        "run-state",
        RunStateEvent {
            state,
            pid: event.pid,
            success: (state != "started").then_some(state == "stopped"),
            detail: None,
        },
    );
}

fn spawn_run_output_reader(app: AppHandle, child: &mut Child) {
    let Some(stdout) = child.stdout.take() else {
        return;
    };
    std::thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in std::io::BufRead::lines(reader).map_while(Result::ok) {
            handle_run_output(&app, &line);
        }
    });
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let paths = EnginePaths::resolve().expect("Could not resolve desktop engine paths");
    tauri::Builder::default()
        .manage(paths)
        .manage(OperationCoordinator::default())
        .manage(UpdateTracker::default())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .invoke_handler(tauri::generate_handler![
            get_bootstrap,
            get_snapshot,
            get_home_state,
            get_cache,
            get_cache_health,
            get_cache_inspection,
            get_cache_cleanup,
            apply_cache_cleanup,
            get_evidence_cleanup,
            apply_evidence_cleanup,
            get_removal_plan,
            apply_removal,
            get_profiles,
            save_profile,
            rename_profile,
            duplicate_profile,
            delete_profile,
            activate_profile,
            get_launch_settings,
            update_launch_settings,
            launch_game,
            stop_game,
            get_preparation_plan,
            start_preparation,
            cancel_preparation,
            get_desktop_smoke_probe,
            start_desktop_smoke,
            request_desktop_smoke_cancellation,
            cancel_desktop_smoke,
            get_wireframe_hulls,
            get_report_intake_status,
            send_run_report,
            cancel_run_report,
            delete_run_report,
            check_for_update,
            install_update,
            open_project_link,
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            operations::recover_operation_state(&handle)?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running Tauri application");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn project_links_are_fixed_and_https() {
        for (_, url) in PROJECT_LINKS {
            assert!(url.starts_with("https://"));
        }
        assert!(project_link_url("project").is_ok());
        assert!(project_link_url("unknown").is_err());
    }
}
