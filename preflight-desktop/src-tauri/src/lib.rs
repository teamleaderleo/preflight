use serde::Serialize;
use std::io::Read;
use std::process::{Child, Stdio};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager, State};

mod automation;
mod desktop_automation_bridge;
mod engine;
mod operations;
mod preparation;
mod report_transport;
mod reports;
mod updates;

use automation::{
    cancel_desktop_smoke, get_desktop_smoke_probe, open_desktop_accessibility_settings,
    request_desktop_smoke_cancellation, start_desktop_smoke,
};
use engine::{
    EnginePaths, activate_profile, apply_cache_cleanup, apply_removal, canonical_game_directory,
    export_diagnostics, get_cache, get_cache_cleanup, get_cache_health, get_launch_settings,
    get_profiles, get_removal_plan, get_snapshot, repair_cache, save_profile,
    update_launch_settings,
};
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

#[tauri::command]
fn get_operation_state(
    tracker: State<'_, OperationCoordinator>,
) -> Result<OperationSnapshot, String> {
    snapshot_operation_state(&tracker)
}

fn snapshot_operation_state(tracker: &OperationCoordinator) -> Result<OperationSnapshot, String> {
    let state = tracker
        .0
        .lock()
        .map_err(|_| "The operation coordinator is unavailable.".to_string())?;
    Ok(OperationSnapshot::from_state(&state))
}

#[tauri::command]
fn start_game(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    game: String,
    optimization_preset: String,
    disabled_optimization_domains: Vec<String>,
) -> Result<RunStarted, String> {
    let directory = canonical_game_directory(&game)?;
    let optimization_preset = validate_optimization_preset(&optimization_preset)?;
    let disabled_optimization_domains =
        validate_optimization_domains(&disabled_optimization_domains)?;
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
            "Wait for profile preparation to finish before launching Starsector.".to_string(),
        );
    }

    let mut command = paths.command();
    command
        .arg("run")
        .arg("--optimization-preset")
        .arg(optimization_preset)
        .arg("--game")
        .arg(directory);
    for domain in disabled_optimization_domains {
        command.arg("--disable-optimization-domain").arg(domain);
    }
    command.stderr(Stdio::piped());
    let child = command
        .spawn()
        .map_err(|error| format!("Could not launch Starsector: {error}"))?;
    let pid = child.id();
    running.game = Some(pid);
    drop(running);

    let _ = app.emit(
        "run-state",
        RunStateEvent {
            state: "started",
            pid,
            success: None,
            detail: None,
        },
    );

    watch_child(app, child);
    Ok(RunStarted { pid })
}

fn validate_optimization_preset(value: &str) -> Result<&str, String> {
    match value {
        "recommended" | "conservative" | "off" => Ok(value),
        _ => Err("Optimization preset must be recommended, conservative, or off.".to_string()),
    }
}

fn validate_optimization_domains(values: &[String]) -> Result<Vec<&str>, String> {
    let mut validated = Vec::with_capacity(values.len());
    for value in values {
        let value = match value.as_str() {
            "prepared-textures" | "prepared-audio" => value.as_str(),
            _ => {
                return Err(
                    "Optimization domains must be prepared-textures or prepared-audio.".to_string(),
                );
            }
        };
        if validated.contains(&value) {
            return Err(format!(
                "Optimization domain {value} was supplied more than once."
            ));
        }
        validated.push(value);
    }
    Ok(validated)
}

fn watch_child(app: AppHandle, mut child: Child) {
    let pid = child.id();
    std::thread::spawn(move || {
        let stderr = child
            .stderr
            .take()
            .map(|mut stderr| read_tail(&mut stderr, 8 * 1024))
            .unwrap_or_default();
        let status = child.wait();
        let success = status.as_ref().is_ok_and(|status| status.success());
        let detail = if success {
            None
        } else {
            Some(child_error("Starsector exited with an error", &stderr))
        };
        if let Ok(mut running) = app.state::<OperationCoordinator>().0.lock() {
            running.game = None;
        }
        let _ = app.emit(
            "run-state",
            RunStateEvent {
                state: "finished",
                pid,
                success: Some(success),
                detail,
            },
        );
    });
}

pub(crate) fn take_deferred_exit(running: &mut OperationState) -> bool {
    if running.exit_after_cleanup
        && running.desktop_smoke.is_none()
        && running.preparation.is_none()
        && running.report_upload.is_none()
    {
        running.exit_after_cleanup = false;
        true
    } else {
        false
    }
}

fn begin_exit_cleanup(running: &mut OperationState) -> Result<bool, String> {
    let mut pending = false;
    if let Some(process) = running.desktop_smoke.as_ref() {
        request_desktop_smoke_cancellation(process)?;
        pending = true;
    }
    if let Some(preparation) = running.preparation.as_ref() {
        if preparation.cancel.send(()).is_ok() {
            pending = true;
        } else {
            running.preparation = None;
        }
    }
    if let Some(upload) = running.report_upload.as_ref() {
        if upload.cancel.send(true).is_ok() {
            pending = true;
        } else {
            running.report_upload = None;
        }
    }
    if pending {
        running.exit_after_cleanup = true;
    }
    Ok(pending)
}

fn read_tail(reader: &mut dyn Read, limit: usize) -> Vec<u8> {
    let mut tail = Vec::with_capacity(limit);
    let mut chunk = [0_u8; 4096];
    loop {
        let read = match reader.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(read) => read,
        };
        if read >= limit {
            tail.clear();
            tail.extend_from_slice(&chunk[read - limit..read]);
            continue;
        }
        let overflow = tail.len().saturating_add(read).saturating_sub(limit);
        if overflow > 0 {
            tail.drain(..overflow);
        }
        tail.extend_from_slice(&chunk[..read]);
    }
    tail
}

pub(crate) fn child_error(context: &str, stderr: &[u8]) -> String {
    let details = String::from_utf8_lossy(stderr);
    let details = details.trim();
    if details.is_empty() {
        context.to_string()
    } else {
        format!("{context}: {details}")
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(OperationCoordinator::default())
        .manage(UpdateTracker(Mutex::new(None)))
        .setup(|app| {
            if std::env::var_os("PREFLIGHT_DESKTOP_AUTOMATION_PROBE_SMOKE").as_deref()
                == Some(std::ffi::OsStr::new("1"))
            {
                match get_desktop_smoke_probe(app.handle().clone()) {
                    Ok(probe) => println!(
                        "PREFLIGHT_DESKTOP_AUTOMATION_PROBE={}",
                        serde_json::to_string(&probe)
                            .expect("desktop automation probe should serialize")
                    ),
                    Err(error) => {
                        eprintln!("PREFLIGHT_DESKTOP_AUTOMATION_PROBE_ERROR={error}");
                        app.handle().exit(1);
                        return Ok(());
                    }
                }
                app.handle().exit(0);
                return Ok(());
            }
            if std::env::var_os("PREFLIGHT_DESKTOP_BOOT_SMOKE").as_deref()
                == Some(std::ffi::OsStr::new("1"))
            {
                println!("PREFLIGHT_DESKTOP_BOOT_SMOKE_OK");
                app.handle().exit(0);
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_snapshot,
            get_desktop_smoke_probe,
            open_desktop_accessibility_settings,
            start_desktop_smoke,
            cancel_desktop_smoke,
            get_cache,
            get_cache_health,
            repair_cache,
            get_cache_cleanup,
            apply_cache_cleanup,
            get_removal_plan,
            apply_removal,
            check_for_update,
            install_update,
            export_diagnostics,
            get_report_intake_status,
            send_run_report,
            cancel_run_report,
            delete_run_report,
            get_launch_settings,
            update_launch_settings,
            get_profiles,
            save_profile,
            activate_profile,
            start_game,
            get_operation_state,
            get_preparation_plan,
            start_preparation,
            cancel_preparation
        ])
        .build(tauri::generate_context!())
        .expect("error while running Preflight");
    app.run(|app, event| {
        let tauri::RunEvent::ExitRequested { code, api, .. } = event else {
            return;
        };
        if code == Some(tauri::RESTART_EXIT_CODE) {
            return;
        }
        let cleanup = app
            .state::<OperationCoordinator>()
            .0
            .lock()
            .map_err(|_| "The process tracker is unavailable during shutdown.".to_string())
            .and_then(|mut running| begin_exit_cleanup(&mut running));
        match cleanup {
            Ok(true) => api.prevent_exit(),
            Ok(false) => {}
            Err(error) => {
                api.prevent_exit();
                eprintln!("Preflight delayed shutdown: {error}");
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::{
        begin_exit_cleanup, read_tail, snapshot_operation_state, take_deferred_exit,
        validate_optimization_domains, validate_optimization_preset,
    };
    use crate::automation::{
        DESKTOP_SMOKE_CANCELLATION_FILE, desktop_smoke_cancellation_outcome,
        desktop_smoke_cancellation_requested, desktop_smoke_outcome,
    };
    use crate::engine::{
        LaunchSettingsInput, configure_cache_health_command, diagnostic_output_path,
        validate_cache_repair_state, validate_launch_settings, validate_removal_scope,
    };
    use crate::operations::{
        DesktopSmokeProcess, OperationCoordinator, OperationState, PreparationProcess,
        ReportUploadProcess, begin_update_install, refuse_update_install,
    };
    use crate::report_transport::{
        perform_report_deletion, perform_report_upload, report_client, validate_report_origin,
        validate_report_receipt, validated_case_url, validated_report_archive,
    };
    use crate::reports::{ReportDeletion, ReportReceipt, ReportUploadError, ReportUploadInput};
    use crate::updates::{
        DEFAULT_UPDATE_ENDPOINT, compiled_updater_endpoint, validated_updater_endpoint,
    };
    use sha2::{Digest, Sha256};
    use std::fs;
    use std::io::{Cursor, Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::path::PathBuf;
    use std::process::Command;
    use std::sync::{Arc, Mutex, mpsc};
    use std::thread;
    use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
    use tokio::sync::{Mutex as AsyncMutex, watch};
    use url::Url;

    #[cfg(target_os = "macos")]
    use crate::automation::MACOS_ACCESSIBILITY_SETTINGS;

    // Each case owns a blocking loopback server. Serialize those cases and handshake with the
    // accept thread below so rapid parallel suites never race a freshly opened listener.
    static REPORT_SERVER_TEST_LOCK: AsyncMutex<()> = AsyncMutex::const_new(());

    #[test]
    fn keeps_only_the_bounded_end_of_child_stderr() {
        let mut stderr = Cursor::new(b"0123456789abcdef");

        assert_eq!(b"89abcdef", read_tail(&mut stderr, 8).as_slice());
    }

    #[test]
    fn keeps_short_child_stderr_in_full() {
        let mut stderr = Cursor::new(b"useful failure");

        assert_eq!(b"useful failure", read_tail(&mut stderr, 1024).as_slice());
    }

    #[test]
    fn operation_state_command_serializes_only_the_public_live_operation_contract() {
        let (preparation_cancel, _preparation_cancelled) = mpsc::channel();
        let (report_cancel, _report_cancelled) = watch::channel(false);
        let coordinator = OperationCoordinator(Mutex::new(OperationState {
            game: Some(41),
            desktop_smoke: Some(DesktopSmokeProcess {
                pid: 42,
                run_directory: PathBuf::from("/tmp/preflight-smoke-42"),
            }),
            preparation: Some(PreparationProcess {
                pid: 43,
                cancel: preparation_cancel,
            }),
            report_upload: Some(ReportUploadProcess {
                id: 44,
                total_bytes: 4_096,
                cancel: report_cancel,
            }),
            update_installing: true,
            exit_after_cleanup: true,
        }));

        let snapshot = snapshot_operation_state(&coordinator).unwrap();
        let value = serde_json::to_value(snapshot).unwrap();

        assert_eq!(
            serde_json::json!({
                "format": "preflight-operation-state-v1",
                "gamePid": 41,
                "desktopSmokePid": 42,
                "desktopSmokeRunDirectory": "/tmp/preflight-smoke-42",
                "preparationPid": 43,
                "reportUploadId": 44,
                "reportUploadTotalBytes": 4096,
                "updateInstalling": true
            }),
            value
        );
        assert!(value.get("exitAfterCleanup").is_none());
    }

    #[test]
    fn operation_state_command_reports_a_poisoned_coordinator_without_panicking() {
        let coordinator = Arc::new(OperationCoordinator(Mutex::new(OperationState::default())));
        let poison = Arc::clone(&coordinator);
        assert!(
            thread::spawn(move || {
                let _state = poison.0.lock().unwrap();
                panic!("poison operation coordinator for the command boundary");
            })
            .join()
            .is_err()
        );

        assert_eq!(
            "The operation coordinator is unavailable.",
            snapshot_operation_state(coordinator.as_ref()).unwrap_err()
        );
    }

    #[test]
    fn cache_repair_guard_blocks_mutating_operations_and_allows_read_only_neighbors() {
        assert!(validate_cache_repair_state(&OperationState::default()).is_ok());

        let update = OperationState {
            update_installing: true,
            ..OperationState::default()
        };
        assert_eq!(
            "Wait for the Preflight update to finish installing.",
            validate_cache_repair_state(&update).unwrap_err()
        );

        let game = OperationState {
            game: Some(51),
            ..OperationState::default()
        };
        assert_eq!(
            "Close Starsector before repairing prepared data.",
            validate_cache_repair_state(&game).unwrap_err()
        );

        let (preparation_cancel, _preparation_cancelled) = mpsc::channel();
        let preparation = OperationState {
            preparation: Some(PreparationProcess {
                pid: 52,
                cancel: preparation_cancel,
            }),
            ..OperationState::default()
        };
        assert_eq!(
            "Wait for profile preparation to finish before repairing prepared data.",
            validate_cache_repair_state(&preparation).unwrap_err()
        );

        let (report_cancel, _report_cancelled) = watch::channel(false);
        let read_only_neighbors = OperationState {
            report_upload: Some(ReportUploadProcess {
                id: 54,
                total_bytes: 8_192,
                cancel: report_cancel,
            }),
            exit_after_cleanup: true,
            ..OperationState::default()
        };
        assert!(validate_cache_repair_state(&read_only_neighbors).is_ok());
    }

    #[test]
    fn cache_health_command_keeps_inspection_read_only_and_repair_explicit() {
        let game = PathBuf::from("/tmp/Starsector test");

        let mut health = Command::new("preflight-engine");
        configure_cache_health_command(&mut health, &game, None);
        assert_eq!(
            vec![
                "cache",
                "health",
                "--json",
                "--game",
                "/tmp/Starsector test"
            ],
            health
                .get_args()
                .map(|argument| argument.to_string_lossy().into_owned())
                .collect::<Vec<_>>()
        );

        let mut repair = Command::new("preflight-engine");
        let profile = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        configure_cache_health_command(&mut repair, &game, Some(profile));
        assert_eq!(
            vec![
                "cache",
                "repair",
                "--yes",
                "--expected-profile",
                profile,
                "--json",
                "--game",
                "/tmp/Starsector test"
            ],
            repair
                .get_args()
                .map(|argument| argument.to_string_lossy().into_owned())
                .collect::<Vec<_>>()
        );
    }

    #[test]
    fn desktop_smoke_receipt_controls_success_instead_of_the_process_alone() {
        let status = successful_status();
        let passed = br#"{"protocol":1,"launch":{"status":"passed","diagnostics":[]}}"#;
        let failed =
            br#"{"protocol":1,"launch":{"status":"failed","diagnostics":["bounded failure"]}}"#;

        assert_eq!(
            (true, None),
            desktop_smoke_outcome(&Ok(status), passed, b"")
        );
        let status = successful_status();
        assert_eq!(
            (false, Some("bounded failure".to_string())),
            desktop_smoke_outcome(&Ok(status), failed, b""),
        );

        let status = successful_status();
        let paired = br#"{"protocol":1,"launch":{"format":"starsector-preflight-desktop-benchmark-v1","status":"passed","diagnostics":[],"complete":true}}"#;
        assert_eq!(
            (true, None),
            desktop_smoke_outcome(&Ok(status), paired, b"")
        );

        let status = successful_status();
        let mismatched = br#"{"protocol":1,"launch":{"format":"starsector-preflight-desktop-benchmark-v1","status":"failed","diagnostics":["The profile changed between runs"],"complete":false}}"#;
        assert_eq!(
            (false, Some("The profile changed between runs".to_string())),
            desktop_smoke_outcome(&Ok(status), mismatched, b"")
        );
    }

    #[test]
    fn cancellation_requires_a_sealed_cancelled_receipt() {
        let status = successful_status();
        let cancelled = br#"{"protocol":1,"launch":{"status":"cancelled","diagnostics":[]}}"#;
        assert_eq!(
            (
                true,
                Some("Automated game test stopped safely after exact-process cleanup.".to_string())
            ),
            desktop_smoke_cancellation_outcome(&Ok(status), cancelled, b"")
        );

        let status = successful_status();
        let failed =
            br#"{"protocol":1,"launch":{"status":"failed","diagnostics":["cleanup failed"]}}"#;
        assert_eq!(
            (false, Some("cleanup failed".to_string())),
            desktop_smoke_cancellation_outcome(&Ok(status), failed, b"")
        );
    }

    #[test]
    fn app_exit_requests_owned_cleanup_before_it_can_finish() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let run_directory = std::env::temp_dir().join(format!(
            "preflight-desktop-exit-test-{}-{unique}",
            std::process::id()
        ));
        fs::create_dir(&run_directory).unwrap();
        let (cancel, cancelled) = mpsc::channel();
        let (report_cancel, report_cancelled) = watch::channel(false);
        let mut running = OperationState {
            game: Some(41),
            desktop_smoke: Some(DesktopSmokeProcess {
                pid: 41,
                run_directory: run_directory.clone(),
            }),
            preparation: Some(PreparationProcess { pid: 42, cancel }),
            report_upload: Some(ReportUploadProcess {
                id: 43,
                total_bytes: 1_024,
                cancel: report_cancel,
            }),
            update_installing: false,
            exit_after_cleanup: false,
        };

        assert!(begin_exit_cleanup(&mut running).unwrap());
        assert!(desktop_smoke_cancellation_requested(&run_directory));
        assert!(cancelled.try_recv().is_ok());
        assert!(*report_cancelled.borrow());
        assert!(running.exit_after_cleanup);
        assert!(!take_deferred_exit(&mut running));
        running.desktop_smoke = None;
        running.preparation = None;
        running.report_upload = None;
        assert!(take_deferred_exit(&mut running));
        assert!(!running.exit_after_cleanup);

        fs::remove_file(run_directory.join(DESKTOP_SMOKE_CANCELLATION_FILE)).unwrap();
        fs::remove_dir(run_directory).unwrap();
    }

    #[test]
    fn report_origin_and_case_urls_fail_closed() {
        assert!(validate_report_origin(None).is_err());
        assert!(validate_report_origin(Some("http://reports.example.com")).is_err());
        assert!(validate_report_origin(Some("https://reports.preflight.invalid")).is_err());
        assert!(validate_report_origin(Some("https://reports.example.com/path")).is_err());
        let origin = validate_report_origin(Some("https://reports.example.com")).unwrap();
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        assert!(
            validated_case_url(
                &origin,
                &format!("https://reports.example.com/v1/cases/{case_id}/archive"),
                case_id,
                "archive",
            )
            .is_ok()
        );
        assert!(
            validated_case_url(
                &origin,
                &format!("https://elsewhere.example/v1/cases/{case_id}/archive"),
                case_id,
                "archive",
            )
            .is_err()
        );
    }

    #[test]
    fn report_receipt_matches_the_flat_operator_resolvable_object_key() {
        let origin = validate_report_origin(Some("https://reports.example.com")).unwrap();
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        let report = ReportUploadInput {
            output: "/unused/report.zip".to_string(),
            bytes: 197_375,
            sha256: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".to_string(),
        };
        let receipt = ReportReceipt {
            protocol_version: 1,
            case_id: case_id.to_string(),
            object_key: format!("accepted/{case_id}.zip"),
            bytes: report.bytes,
            sha256: report.sha256.clone(),
            product_version: env!("CARGO_PKG_VERSION").to_string(),
            received_at: "2026-08-07T18:00:00.000Z".to_string(),
            retention_deadline: "2026-08-22T18:00:00.000Z".to_string(),
            deletion: ReportDeletion {
                method: "DELETE".to_string(),
                url: format!("https://reports.example.com/v1/cases/{case_id}"),
                token: "header.signature".to_string(),
            },
            signature: "signed-receipt".to_string(),
        };

        assert!(validate_report_receipt(&origin, &receipt, case_id, &report).is_ok());
        let mut stale = receipt;
        stale.object_key = format!("accepted/2026-08-07/{case_id}.zip");
        assert!(validate_report_receipt(&origin, &stale, case_id, &report).is_err());
    }

    #[test]
    fn report_archive_must_still_match_its_disclosed_digest() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let archive = std::env::temp_dir().join(format!(
            "preflight-report-archive-test-{}-{unique}.zip",
            std::process::id()
        ));
        fs::write(&archive, b"abc").unwrap();
        let report = ReportUploadInput {
            output: archive.to_string_lossy().into_owned(),
            bytes: 3,
            sha256: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".to_string(),
        };

        assert_eq!(
            archive.canonicalize().unwrap(),
            validated_report_archive(&report).unwrap()
        );
        fs::write(&archive, b"changed").unwrap();
        assert!(validated_report_archive(&report).is_err());
        fs::remove_file(archive).unwrap();
    }

    #[tokio::test(flavor = "current_thread")]
    async fn cancelled_report_upload_deletes_its_incomplete_server_case() {
        let _server_test = REPORT_SERVER_TEST_LOCK.lock().await;
        let (origin, requests, server) = local_report_server();
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let archive = std::env::temp_dir().join(format!(
            "preflight-report-cancel-test-{}-{unique}.zip",
            std::process::id()
        ));
        let payload = vec![0x5a; 256 * 1024];
        fs::write(&archive, &payload).unwrap();
        let report = ReportUploadInput {
            output: archive.to_string_lossy().into_owned(),
            bytes: payload.len() as u64,
            sha256: Sha256::digest(&payload)
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect(),
        };
        let (cancel, cancel_receiver) = watch::channel(false);
        let cancel_on_upload = cancel.clone();

        let outcome = perform_report_upload(
            report_client().unwrap(),
            origin,
            archive.clone(),
            report,
            71,
            cancel_receiver,
            move |event| {
                if event.state == "uploading" && event.uploaded_bytes == 0 {
                    let _ = cancel_on_upload.send(true);
                }
            },
        )
        .await;

        assert!(matches!(outcome, Err(ReportUploadError::Cancelled)));
        server.join().unwrap();
        let requests = requests.lock().unwrap();
        assert!(
            requests
                .iter()
                .any(|request| { request.method == "POST" && request.path == "/v1/cases" })
        );
        assert!(requests.iter().any(|request| {
            request.method == "DELETE"
                && request.path == "/v1/cases/3961d5f3-cd4c-4b62-b915-e9cc5a68d5db"
                && request.authorization.as_deref() == Some("Bearer delete.signature")
        }));
        assert_eq!(payload, fs::read(&archive).unwrap());
        drop(requests);
        fs::remove_file(archive).unwrap();
    }

    #[tokio::test(flavor = "current_thread")]
    async fn successful_report_upload_streams_and_finalizes_the_disclosed_archive() {
        let _server_test = REPORT_SERVER_TEST_LOCK.lock().await;
        let (origin, requests, server) = local_report_server();
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let archive = std::env::temp_dir().join(format!(
            "preflight-report-success-test-{}-{unique}.zip",
            std::process::id()
        ));
        let payload = b"bounded local diagnostics archive".to_vec();
        fs::write(&archive, &payload).unwrap();
        let digest: String = Sha256::digest(&payload)
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        let report = ReportUploadInput {
            output: archive.to_string_lossy().into_owned(),
            bytes: payload.len() as u64,
            sha256: digest.clone(),
        };
        let (_cancel, cancel_receiver) = watch::channel(false);
        let states = Arc::new(Mutex::new(Vec::new()));
        let emitted_states = states.clone();

        let receipt = perform_report_upload(
            report_client().unwrap(),
            origin,
            archive.clone(),
            report,
            72,
            cancel_receiver,
            move |event| emitted_states.lock().unwrap().push(event.state),
        )
        .await
        .unwrap();

        server.join().unwrap();
        assert_eq!(payload.len() as u64, receipt.bytes);
        assert_eq!(digest, receipt.sha256);
        assert_eq!(
            vec!["uploading", "uploading", "finalizing"],
            *states.lock().unwrap()
        );
        let requests = requests.lock().unwrap();
        assert!(requests.iter().any(|request| {
            request.method == "PUT"
                && request.path == "/v1/cases/3961d5f3-cd4c-4b62-b915-e9cc5a68d5db/archive"
                && request.authorization.as_deref() == Some("Bearer upload.signature")
                && request.body == payload
        }));
        assert!(requests.iter().any(|request| {
            request.method == "POST"
                && request.path == "/v1/cases/3961d5f3-cd4c-4b62-b915-e9cc5a68d5db/finalize"
                && request.authorization.as_deref() == Some("Bearer finalize.signature")
        }));
        drop(requests);
        fs::remove_file(archive).unwrap();
    }

    #[tokio::test(flavor = "current_thread")]
    async fn report_receipt_deletion_uses_its_bearer_grant() {
        let _server_test = REPORT_SERVER_TEST_LOCK.lock().await;
        let (origin, requests, server) = local_report_server();
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        let deletion = ReportDeletion {
            method: "DELETE".to_string(),
            url: origin
                .join(&format!("v1/cases/{case_id}"))
                .unwrap()
                .to_string(),
            token: "receipt.signature".to_string(),
        };

        assert!(
            perform_report_deletion(report_client().unwrap(), origin, deletion)
                .await
                .unwrap()
        );
        server.join().unwrap();
        let requests = requests.lock().unwrap();
        assert_eq!(1, requests.len());
        assert_eq!("DELETE", requests[0].method);
        assert_eq!(format!("/v1/cases/{case_id}"), requests[0].path);
        assert_eq!(
            Some("Bearer receipt.signature"),
            requests[0].authorization.as_deref()
        );
    }

    #[derive(Debug)]
    struct RecordedRequest {
        method: String,
        path: String,
        authorization: Option<String>,
        body: Vec<u8>,
    }

    fn local_report_server() -> (
        Url,
        Arc<Mutex<Vec<RecordedRequest>>>,
        thread::JoinHandle<()>,
    ) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap();
        let origin = Url::parse(&format!("http://{address}/")).unwrap();
        let server_origin = origin.clone();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let server_requests = requests.clone();
        let (ready, server_started) = mpsc::sync_channel(1);
        let server = thread::spawn(move || {
            let mut report_identity = None;
            let deadline = Instant::now() + Duration::from_secs(10);
            ready.send(()).unwrap();
            while Instant::now() < deadline {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let Some(request) = read_local_request(&mut stream) else {
                            continue;
                        };
                        if request.method == "POST" && request.path == "/v1/cases" {
                            let value: serde_json::Value =
                                serde_json::from_slice(&request.body).unwrap();
                            report_identity = Some((
                                value
                                    .pointer("/bytes")
                                    .and_then(serde_json::Value::as_u64)
                                    .unwrap(),
                                value
                                    .pointer("/sha256")
                                    .and_then(serde_json::Value::as_str)
                                    .unwrap()
                                    .to_string(),
                            ));
                        }
                        let stop = request.method == "DELETE"
                            || (request.method == "POST" && request.path.ends_with("/finalize"));
                        let response = local_report_response(
                            &server_origin,
                            &request,
                            report_identity.as_ref(),
                        );
                        server_requests.lock().unwrap().push(request);
                        stream.write_all(response.as_bytes()).unwrap();
                        stream.flush().unwrap();
                        if stop {
                            return;
                        }
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(error) => panic!("local report server failed: {error}"),
                }
            }
            panic!("local report server timed out before deletion");
        });
        server_started
            .recv_timeout(Duration::from_secs(2))
            .expect("local report server did not start");
        (origin, requests, server)
    }

    fn read_local_request(stream: &mut TcpStream) -> Option<RecordedRequest> {
        stream
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let mut bytes = Vec::new();
        let mut buffer = [0_u8; 8192];
        let header_end = loop {
            let read = stream.read(&mut buffer).ok()?;
            if read == 0 {
                return None;
            }
            bytes.extend_from_slice(&buffer[..read]);
            if let Some(end) = bytes.windows(4).position(|window| window == b"\r\n\r\n") {
                break end + 4;
            }
            if bytes.len() > 64 * 1024 {
                return None;
            }
        };
        let headers = String::from_utf8(bytes[..header_end].to_vec()).ok()?;
        let mut lines = headers.split("\r\n");
        let mut request_line = lines.next()?.split_whitespace();
        let method = request_line.next()?.to_string();
        let path = request_line.next()?.to_string();
        let mut content_length = 0_usize;
        let mut authorization = None;
        for line in lines {
            let Some((name, value)) = line.split_once(':') else {
                continue;
            };
            if name.eq_ignore_ascii_case("content-length") {
                content_length = value.trim().parse().unwrap_or(0);
            } else if name.eq_ignore_ascii_case("authorization") {
                authorization = Some(value.trim().to_string());
            }
        }
        let mut body = bytes[header_end..].to_vec();
        while body.len() < content_length {
            match stream.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(read) => body.extend_from_slice(&buffer[..read]),
            }
        }
        body.truncate(content_length.min(body.len()));
        Some(RecordedRequest {
            method,
            path,
            authorization,
            body,
        })
    }

    fn local_report_response(
        origin: &Url,
        request: &RecordedRequest,
        report_identity: Option<&(u64, String)>,
    ) -> String {
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        if request.method == "POST" && request.path == "/v1/cases" {
            let body = serde_json::json!({
                "protocolVersion": 1,
                "caseId": case_id,
                "upload": {
                    "method": "PUT",
                    "url": origin.join(&format!("v1/cases/{case_id}/archive")).unwrap(),
                    "contentType": "application/zip",
                    "expiresAt": "2026-08-09T00:00:00.000Z",
                    "token": "upload.signature"
                },
                "finalize": {
                    "method": "POST",
                    "url": origin.join(&format!("v1/cases/{case_id}/finalize")).unwrap(),
                    "token": "finalize.signature"
                },
                "deletion": {
                    "method": "DELETE",
                    "url": origin.join(&format!("v1/cases/{case_id}")).unwrap(),
                    "token": "delete.signature"
                }
            })
            .to_string();
            return http_response("201 Created", &body);
        }
        if request.method == "PUT" && request.path == format!("/v1/cases/{case_id}/archive") {
            let Some((bytes, sha256)) = report_identity else {
                return http_response("409 Conflict", r#"{"error":"missing identity"}"#);
            };
            if request.body.len() as u64 != *bytes
                || Sha256::digest(&request.body)
                    .iter()
                    .map(|byte| format!("{byte:02x}"))
                    .collect::<String>()
                    != *sha256
            {
                return http_response("499 Client Closed Request", "{}");
            }
            let body = serde_json::json!({
                "status": "uploaded",
                "caseId": case_id,
                "bytes": bytes,
                "sha256": sha256
            })
            .to_string();
            return http_response("200 OK", &body);
        }
        if request.method == "POST" && request.path == format!("/v1/cases/{case_id}/finalize") {
            let Some((bytes, sha256)) = report_identity else {
                return http_response("409 Conflict", r#"{"error":"missing identity"}"#);
            };
            let body = serde_json::json!({
                "protocolVersion": 1,
                "caseId": case_id,
                "objectKey": format!("accepted/{case_id}.zip"),
                "bytes": bytes,
                "sha256": sha256,
                "productVersion": env!("CARGO_PKG_VERSION"),
                "receivedAt": "2026-08-08T00:00:00.000Z",
                "retentionDeadline": "2026-08-22T00:00:00.000Z",
                "deletion": {
                    "method": "DELETE",
                    "url": origin.join(&format!("v1/cases/{case_id}")).unwrap(),
                    "token": "receipt.signature"
                },
                "signature": "signed-receipt"
            })
            .to_string();
            return http_response("200 OK", &body);
        }
        if request.method == "DELETE" && request.path == format!("/v1/cases/{case_id}") {
            return http_response("204 No Content", "");
        }
        http_response("404 Not Found", r#"{"error":"unexpected request"}"#)
    }

    fn http_response(status: &str, body: &str) -> String {
        format!(
            "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        )
    }

    fn successful_status() -> std::process::ExitStatus {
        if cfg!(windows) {
            Command::new("cmd")
                .args(["/d", "/c", "exit", "0"])
                .status()
                .unwrap()
        } else {
            Command::new("sh").args(["-c", "exit 0"]).status().unwrap()
        }
    }

    #[test]
    fn removal_scopes_are_a_closed_product_contract() {
        assert!(validate_removal_scope("launcher").is_ok());
        assert!(validate_removal_scope("all-data").is_ok());
        assert!(validate_removal_scope("cache").is_err());
        assert!(validate_removal_scope("../game").is_err());
    }

    #[test]
    fn diagnostics_output_requires_an_absolute_zip_path() {
        let temporary = std::env::temp_dir().canonicalize().unwrap();
        let text = temporary.join("diagnostics.txt");
        let zip = temporary.join("diagnostics.zip");
        assert!(diagnostic_output_path("relative.zip").is_err());
        assert!(diagnostic_output_path(text.to_str().unwrap()).is_err());
        assert_eq!(zip, diagnostic_output_path(zip.to_str().unwrap()).unwrap());
    }

    #[test]
    fn launch_settings_validation_matches_the_engine_contract() {
        let valid = LaunchSettingsInput {
            resolution: "1920x1080".to_string(),
            fullscreen: false,
            sound: true,
            antialiasing_samples: 12,
            ui_scale: 1.25,
            battle_size: 400,
            memory_mib: Some(6144),
        };
        assert!(validate_launch_settings(&valid).is_ok());

        let invalid = LaunchSettingsInput {
            resolution: "1920 by 1080".to_string(),
            ..valid
        };
        assert!(validate_launch_settings(&invalid).is_err());
    }

    #[test]
    fn optimization_presets_are_a_closed_product_contract() {
        assert_eq!(
            "recommended",
            validate_optimization_preset("recommended").unwrap()
        );
        assert_eq!(
            "conservative",
            validate_optimization_preset("conservative").unwrap()
        );
        assert_eq!("off", validate_optimization_preset("off").unwrap());
        assert!(validate_optimization_preset("custom").is_err());
        assert!(validate_optimization_preset("recommended --no-adapter").is_err());
    }

    #[test]
    fn optimization_domains_are_a_closed_duplicate_free_product_contract() {
        let values = vec![
            "prepared-textures".to_string(),
            "prepared-audio".to_string(),
        ];
        assert_eq!(
            vec!["prepared-textures", "prepared-audio"],
            validate_optimization_domains(&values).unwrap()
        );
        assert!(validate_optimization_domains(&["campaign".to_string()]).is_err());
        assert!(
            validate_optimization_domains(&[
                "prepared-audio".to_string(),
                "prepared-audio".to_string(),
            ])
            .is_err()
        );
    }

    #[test]
    fn default_updater_endpoint_is_the_fixed_https_release_feed() {
        assert_eq!(DEFAULT_UPDATE_ENDPOINT, compiled_updater_endpoint());
        let endpoint = validated_updater_endpoint(DEFAULT_UPDATE_ENDPOINT).unwrap();
        assert_eq!("https", endpoint.scheme());
        assert_eq!(Some("github.com"), endpoint.host_str());
        assert_eq!(
            "/teamleaderleo/preflight/releases/latest/download/latest.json",
            endpoint.path()
        );
    }

    #[test]
    fn updater_endpoint_rejects_insecure_or_credentialed_urls() {
        assert!(validated_updater_endpoint("http://updates.example.com/latest.json").is_err());
        assert!(
            validated_updater_endpoint("https://token@updates.example.com/latest.json").is_err()
        );
        assert!(
            validated_updater_endpoint("https://updates.example.com/latest.json?client=1").is_err()
        );
        assert!(
            validated_updater_endpoint("https://updates.example.com/latest.json#fragment").is_err()
        );
        assert!(validated_updater_endpoint("https://updates.example.com/latest.json").is_ok());
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_accessibility_link_targets_the_system_privacy_pane() {
        assert_eq!(
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
            MACOS_ACCESSIBILITY_SETTINGS,
        );
    }

    #[test]
    fn mutable_operations_stop_during_update_installation() {
        let mut running = OperationState::default();
        assert!(refuse_update_install(&running).is_ok());
        running.update_installing = true;
        assert!(refuse_update_install(&running).is_err());
    }

    #[test]
    fn update_install_guard_releases_every_error_path() {
        let processes = Mutex::new(OperationState::default());
        {
            let _install = begin_update_install(&processes).unwrap();
            assert!(processes.lock().unwrap().update_installing);
            assert!(begin_update_install(&processes).is_err());
        }
        assert!(!processes.lock().unwrap().update_installing);
        assert!(begin_update_install(&processes).is_ok());
    }
}
