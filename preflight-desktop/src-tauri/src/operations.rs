use serde::Serialize;
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard, TryLockError, mpsc};
use tokio::sync::watch;

#[derive(Default)]
pub(crate) struct OperationState {
    pub(crate) game: Option<u32>,
    pub(crate) game_recovered: bool,
    pub(crate) desktop_smoke: Option<DesktopSmokeProcess>,
    pub(crate) preparation: Option<PreparationProcess>,
    pub(crate) report_upload: Option<ReportUploadProcess>,
    pub(crate) diagnostics_exporting: bool,
    pub(crate) update_checking: bool,
    pub(crate) update_installing: bool,
    pub(crate) exit_after_cleanup: bool,
    pub(crate) foreground_operation: bool,
}

pub(crate) struct DesktopSmokeProcess {
    pub(crate) pid: u32,
    pub(crate) run_directory: PathBuf,
}

pub(crate) struct PreparationProcess {
    pub(crate) pid: u32,
    pub(crate) cancel: mpsc::Sender<()>,
}

pub(crate) struct ReportUploadProcess {
    pub(crate) id: u64,
    pub(crate) total_bytes: u64,
    pub(crate) cancel: watch::Sender<bool>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct OperationSnapshot {
    pub(crate) format: &'static str,
    pub(crate) game_pid: Option<u32>,
    pub(crate) game_recovered: bool,
    pub(crate) desktop_smoke_pid: Option<u32>,
    pub(crate) desktop_smoke_run_directory: Option<String>,
    pub(crate) preparation_pid: Option<u32>,
    pub(crate) report_upload_id: Option<u64>,
    pub(crate) report_upload_total_bytes: Option<u64>,
    pub(crate) diagnostics_exporting: bool,
    pub(crate) update_checking: bool,
    pub(crate) update_installing: bool,
}

impl OperationSnapshot {
    pub(crate) fn from_state(state: &OperationState) -> Self {
        Self {
            format: "preflight-operation-state-v1",
            game_pid: state.game,
            game_recovered: state.game_recovered,
            desktop_smoke_pid: state.desktop_smoke.as_ref().map(|process| process.pid),
            desktop_smoke_run_directory: state
                .desktop_smoke
                .as_ref()
                .map(|process| process.run_directory.to_string_lossy().into_owned()),
            preparation_pid: state.preparation.as_ref().map(|process| process.pid),
            report_upload_id: state.report_upload.as_ref().map(|process| process.id),
            report_upload_total_bytes: state
                .report_upload
                .as_ref()
                .map(|process| process.total_bytes),
            diagnostics_exporting: state.diagnostics_exporting,
            update_checking: state.update_checking,
            update_installing: state.update_installing,
        }
    }
}

#[derive(Default)]
pub(crate) struct OperationCoordinator(pub(crate) Mutex<OperationState>);

// A reservation preserves admission rules while child work runs without the coordinator lock.
// Drop releases ownership on success, refusal, or panic and completes a deferred app exit.
pub(crate) struct ForegroundReservation<'a> {
    operations: &'a Mutex<OperationState>,
    on_exit: Option<Box<dyn FnOnce() + Send + 'a>>,
}

impl Drop for ForegroundReservation<'_> {
    fn drop(&mut self) {
        let should_exit = {
            let mut state = self
                .operations
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            state.foreground_operation = false;
            crate::take_deferred_exit(&mut state)
        };
        if let (true, Some(on_exit)) = (should_exit, self.on_exit.take()) {
            on_exit();
        }
    }
}

pub(crate) fn reserve_foreground<'a>(
    app: &tauri::AppHandle,
    coordinator: &'a OperationCoordinator,
    state: MutexGuard<'_, OperationState>,
) -> Result<ForegroundReservation<'a>, String> {
    let app = app.clone();
    reserve_foreground_with_exit(&coordinator.0, state, move || app.exit(0))
}

fn reserve_foreground_with_exit<'a>(
    operations: &'a Mutex<OperationState>,
    mut state: MutexGuard<'_, OperationState>,
    on_exit: impl FnOnce() + Send + 'a,
) -> Result<ForegroundReservation<'a>, String> {
    refuse_update_install(&state)?;
    state.foreground_operation = true;
    drop(state);
    Ok(ForegroundReservation {
        operations,
        on_exit: Some(Box::new(on_exit)),
    })
}

fn refuse_foreground_operation(state: &OperationState) -> Result<(), String> {
    if state.exit_after_cleanup {
        return Err("Preflight is closing. Wait for its current operation to finish.".to_string());
    }
    if state.foreground_operation {
        return Err("Wait for the current Preflight operation to finish.".to_string());
    }
    Ok(())
}

pub(crate) struct UpdateCheckGuard<'a> {
    operations: &'a Mutex<OperationState>,
}

impl Drop for UpdateCheckGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut state) = self.operations.lock() {
            state.update_checking = false;
        }
    }
}

pub(crate) struct UpdateInstallGuard<'a> {
    operations: &'a Mutex<OperationState>,
}

pub(crate) struct DiagnosticsExportGuard<'a> {
    operations: &'a Mutex<OperationState>,
}

impl Drop for DiagnosticsExportGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut state) = self.operations.lock() {
            state.diagnostics_exporting = false;
        }
    }
}

pub(crate) fn begin_diagnostics_export(
    operations: &Mutex<OperationState>,
) -> Result<DiagnosticsExportGuard<'_>, String> {
    let mut state = match operations.try_lock() {
        Ok(state) => state,
        Err(TryLockError::WouldBlock) => {
            return Err(
                "Wait for the current Preflight operation to finish before creating a support file."
                    .to_string(),
            );
        }
        Err(TryLockError::Poisoned(_)) => {
            return Err("The operation coordinator is unavailable.".to_string());
        }
    };
    refuse_foreground_operation(&state)?;
    if state.desktop_smoke.is_some() {
        return Err(
            "Wait for the startup benchmark to finish or cancel it before creating a support file."
                .to_string(),
        );
    }
    if state.update_installing {
        return Err(
            "Wait for the Preflight update to finish installing before creating a support file."
                .to_string(),
        );
    }
    if state.diagnostics_exporting {
        return Err("A support file is already being created.".to_string());
    }
    state.diagnostics_exporting = true;
    drop(state);
    Ok(DiagnosticsExportGuard { operations })
}

impl Drop for UpdateInstallGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut state) = self.operations.lock() {
            state.update_installing = false;
        }
    }
}

pub(crate) fn begin_update_check(
    operations: &Mutex<OperationState>,
) -> Result<UpdateCheckGuard<'_>, String> {
    let mut state = operations
        .lock()
        .map_err(|_| "The operation coordinator is unavailable.".to_string())?;
    refuse_update_install(&state)?;
    if state.desktop_smoke.is_some() {
        return Err(
            "Wait for the startup benchmark to finish or cancel it before checking for updates."
                .to_string(),
        );
    }
    if state.update_checking {
        return Err("A Preflight update check is already running.".to_string());
    }
    state.update_checking = true;
    drop(state);
    Ok(UpdateCheckGuard { operations })
}

pub(crate) fn begin_update_install(
    operations: &Mutex<OperationState>,
) -> Result<UpdateInstallGuard<'_>, String> {
    let mut state = operations
        .lock()
        .map_err(|_| "The operation coordinator is unavailable.".to_string())?;
    refuse_foreground_operation(&state)?;
    if state.game.is_some() {
        return Err("Close Starsector before installing a Preflight update.".to_string());
    }
    if state.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before installing an update.".to_string(),
        );
    }
    if state.report_upload.is_some() {
        return Err(
            "Wait for the run report upload to finish or cancel it before installing an update."
                .to_string(),
        );
    }
    if state.diagnostics_exporting {
        return Err("Wait for the support file to finish before installing an update.".to_string());
    }
    if state.update_checking {
        return Err(
            "Wait for the current update check to finish before installing an update.".to_string(),
        );
    }
    if state.update_installing {
        return Err("A Preflight update is already being installed.".to_string());
    }
    state.update_installing = true;
    drop(state);
    Ok(UpdateInstallGuard { operations })
}

pub(crate) fn refuse_update_install(state: &OperationState) -> Result<(), String> {
    refuse_foreground_operation(state)?;
    if state.update_installing {
        Err("Wait for the Preflight update to finish installing.".to_string())
    } else {
        Ok(())
    }
}

pub(crate) fn refuse_report_upload_for_removal(state: &OperationState) -> Result<(), String> {
    if state.report_upload.is_some() {
        return Err(
            "Wait for the run report upload to finish or cancel it before removing Preflight data."
                .to_string(),
        );
    }
    if state.diagnostics_exporting {
        return Err(
            "Wait for the support file to finish before removing Preflight data.".to_string(),
        );
    }
    Ok(())
}

pub(crate) fn refuse_report_upload_for_benchmark(state: &OperationState) -> Result<(), String> {
    if state.report_upload.is_some() {
        return Err(
            "Wait for the run report upload to finish or cancel it before running the startup benchmark."
                .to_string(),
        );
    }
    if state.update_checking {
        return Err(
            "Wait for the update check to finish before running the startup benchmark.".to_string(),
        );
    }
    if state.diagnostics_exporting {
        return Err(
            "Wait for the support file to finish before running the startup benchmark.".to_string(),
        );
    }
    Ok(())
}

pub(crate) fn refuse_benchmark_for_report(state: &OperationState) -> Result<(), String> {
    refuse_foreground_operation(state)?;
    if state.desktop_smoke.is_some() {
        Err(
            "Wait for the startup benchmark to finish or cancel it before changing run reports."
                .to_string(),
        )
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn foreground_reservation_releases_lock_refuses_conflicts_and_finishes_exit() {
        use std::sync::{
            Arc,
            atomic::{AtomicUsize, Ordering},
        };
        let operations = Arc::new(Mutex::new(OperationState::default()));
        let exits = Arc::new(AtomicUsize::new(0));
        let (started, ready) = mpsc::channel();
        let (release, finish) = mpsc::channel();
        let worker_operations = Arc::clone(&operations);
        let worker_exits = Arc::clone(&exits);
        let worker = std::thread::spawn(move || {
            let reservation = reserve_foreground_with_exit(
                &worker_operations,
                worker_operations.lock().unwrap(),
                move || {
                    worker_exits.fetch_add(1, Ordering::SeqCst);
                },
            )
            .unwrap();
            started.send(()).unwrap();
            finish.recv().unwrap();
            drop(reservation);
        });
        ready
            .recv_timeout(std::time::Duration::from_secs(2))
            .unwrap();
        // A delayed child does not own the mutex, but admission remains exclusive.
        let mut state = operations
            .try_lock()
            .expect("UI state and quit must remain responsive");
        assert!(refuse_update_install(&state).is_err());
        assert!(
            reserve_foreground_with_exit(
                &operations,
                {
                    assert!(crate::begin_exit_cleanup(&mut state).unwrap());
                    assert!(!crate::take_deferred_exit(&mut state));
                    state
                },
                || {}
            )
            .is_err()
        );
        assert!(begin_update_install(&operations).is_err());
        assert_eq!(exits.load(Ordering::SeqCst), 0);
        release.send(()).unwrap();
        worker.join().unwrap();
        assert_eq!(exits.load(Ordering::SeqCst), 1);
        let state = operations.lock().unwrap();
        assert!(!state.foreground_operation);
        assert!(!state.exit_after_cleanup);
        assert!(refuse_update_install(&state).is_ok());
    }

    #[test]
    fn foreground_reservation_releases_on_unwind() {
        let operations = Mutex::new(OperationState::default());
        let outcome = std::panic::catch_unwind(|| {
            let _reservation =
                reserve_foreground_with_exit(&operations, operations.lock().unwrap(), || {})
                    .unwrap();
            panic!("synthetic child failure");
        });
        assert!(outcome.is_err());
        assert!(!operations.lock().unwrap().foreground_operation);
    }

    fn state_with_report_upload() -> OperationState {
        let (cancel, _receiver) = watch::channel(false);
        OperationState {
            report_upload: Some(ReportUploadProcess {
                id: 7,
                total_bytes: 1024,
                cancel,
            }),
            ..OperationState::default()
        }
    }

    fn state_with_benchmark() -> OperationState {
        OperationState {
            game: Some(41),
            desktop_smoke: Some(DesktopSmokeProcess {
                pid: 41,
                run_directory: PathBuf::from("benchmark-run"),
            }),
            ..OperationState::default()
        }
    }

    #[test]
    fn update_install_refuses_active_report_upload() {
        let operations = Mutex::new(state_with_report_upload());

        let error = begin_update_install(&operations)
            .err()
            .expect("an active report upload must block update installation");

        assert_eq!(
            error,
            "Wait for the run report upload to finish or cancel it before installing an update."
        );
        assert!(!operations.lock().unwrap().update_installing);
    }

    #[test]
    fn update_install_refuses_active_diagnostics_export() {
        let operations = Mutex::new(OperationState {
            diagnostics_exporting: true,
            ..OperationState::default()
        });

        assert_eq!(
            begin_update_install(&operations).err().unwrap(),
            "Wait for the support file to finish before installing an update."
        );
        assert!(!operations.lock().unwrap().update_installing);
    }

    #[test]
    fn diagnostics_export_refuses_active_update_install() {
        let operations = Mutex::new(OperationState {
            update_installing: true,
            ..OperationState::default()
        });

        assert_eq!(
            begin_diagnostics_export(&operations).err().unwrap(),
            "Wait for the Preflight update to finish installing before creating a support file."
        );
        assert!(!operations.lock().unwrap().diagnostics_exporting);
    }

    #[test]
    fn diagnostics_export_refuses_instead_of_queuing_behind_operation_lock() {
        let operations = std::sync::Arc::new(Mutex::new(OperationState::default()));
        let held = operations.lock().unwrap();
        let worker_operations = std::sync::Arc::clone(&operations);
        let (sender, receiver) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            let outcome = match begin_diagnostics_export(&worker_operations) {
                Ok(_guard) => "started".to_string(),
                Err(error) => error,
            };
            sender.send(outcome).unwrap();
        });

        let admission = receiver.recv_timeout(std::time::Duration::from_millis(250));
        drop(held);
        worker.join().unwrap();

        assert_eq!(
            admission.expect("support export admission must not wait behind the coordinator lock"),
            "Wait for the current Preflight operation to finish before creating a support file."
        );
        {
            let _export = begin_diagnostics_export(&operations)
                .expect("a fresh support export request can start after the operation finishes");
            assert!(operations.lock().unwrap().diagnostics_exporting);
        }
        assert!(!operations.lock().unwrap().diagnostics_exporting);
    }

    #[test]
    fn update_check_refuses_active_benchmark() {
        let operations = Mutex::new(state_with_benchmark());

        assert_eq!(
            begin_update_check(&operations).err().unwrap(),
            "Wait for the startup benchmark to finish or cancel it before checking for updates."
        );
        assert!(!operations.lock().unwrap().update_checking);
    }

    #[test]
    fn update_check_guard_releases_ownership() {
        let operations = Mutex::new(OperationState::default());
        {
            let _check = begin_update_check(&operations).expect("update check starts");
            assert!(operations.lock().unwrap().update_checking);
        }
        assert!(!operations.lock().unwrap().update_checking);
    }

    #[test]
    fn update_install_refuses_active_update_check() {
        let operations = Mutex::new(OperationState {
            update_checking: true,
            ..OperationState::default()
        });

        assert_eq!(
            begin_update_install(&operations).err().unwrap(),
            "Wait for the current update check to finish before installing an update."
        );
    }

    #[test]
    fn removal_refuses_active_report_upload() {
        let state = state_with_report_upload();

        assert_eq!(
            refuse_report_upload_for_removal(&state).unwrap_err(),
            "Wait for the run report upload to finish or cancel it before removing Preflight data."
        );
    }

    #[test]
    fn removal_refuses_active_diagnostics_export() {
        let state = OperationState {
            diagnostics_exporting: true,
            ..OperationState::default()
        };

        assert_eq!(
            refuse_report_upload_for_removal(&state).unwrap_err(),
            "Wait for the support file to finish before removing Preflight data."
        );
    }

    #[test]
    fn benchmark_refuses_active_report_upload() {
        let state = state_with_report_upload();

        assert_eq!(
            refuse_report_upload_for_benchmark(&state).unwrap_err(),
            "Wait for the run report upload to finish or cancel it before running the startup benchmark."
        );
    }

    #[test]
    fn benchmark_refuses_active_update_check() {
        let state = OperationState {
            update_checking: true,
            ..OperationState::default()
        };

        assert_eq!(
            refuse_report_upload_for_benchmark(&state).unwrap_err(),
            "Wait for the update check to finish before running the startup benchmark."
        );
    }

    #[test]
    fn run_report_changes_refuse_active_benchmark() {
        let state = state_with_benchmark();

        assert_eq!(
            refuse_benchmark_for_report(&state).unwrap_err(),
            "Wait for the startup benchmark to finish or cancel it before changing run reports."
        );
    }

    #[test]
    fn diagnostics_export_and_benchmark_exclude_each_other() {
        let benchmark = Mutex::new(state_with_benchmark());
        assert_eq!(
            begin_diagnostics_export(&benchmark).err().unwrap(),
            "Wait for the startup benchmark to finish or cancel it before creating a support file."
        );

        let operations = Mutex::new(OperationState::default());
        {
            let _export = begin_diagnostics_export(&operations).unwrap();
            assert!(operations.lock().unwrap().diagnostics_exporting);
            assert_eq!(
                refuse_report_upload_for_benchmark(&operations.lock().unwrap()).unwrap_err(),
                "Wait for the support file to finish before running the startup benchmark."
            );
        }
        assert!(!operations.lock().unwrap().diagnostics_exporting);
    }

    #[test]
    fn ordinary_update_guard_still_allows_report_upload_as_a_read_only_neighbor() {
        let state = state_with_report_upload();

        assert!(refuse_update_install(&state).is_ok());
    }

    #[test]
    fn operation_snapshot_serializes_all_active_fields() {
        let (cancel, _rx) = watch::channel(false);
        let state = OperationState {
            game: Some(101),
            game_recovered: true,
            desktop_smoke: Some(DesktopSmokeProcess {
                pid: 102,
                run_directory: PathBuf::from("run-102"),
            }),
            preparation: None,
            report_upload: Some(ReportUploadProcess {
                id: 103,
                total_bytes: 4096,
                cancel,
            }),
            diagnostics_exporting: true,
            update_checking: true,
            update_installing: false,
            exit_after_cleanup: false,
            foreground_operation: false,
        };

        let snapshot = OperationSnapshot::from_state(&state);
        let json = serde_json::to_value(&snapshot).expect("snapshot serializes");

        assert_eq!(json["format"], "preflight-operation-state-v1");
        assert_eq!(json["gamePid"], 101);
        assert_eq!(json["gameRecovered"], true);
        assert_eq!(json["desktopSmokePid"], 102);
        assert_eq!(json["desktopSmokeRunDirectory"], "run-102");
        assert_eq!(json["reportUploadId"], 103);
        assert_eq!(json["reportUploadTotalBytes"], 4096);
        assert_eq!(json["diagnosticsExporting"], true);
        assert_eq!(json["updateChecking"], true);
        assert_eq!(json["updateInstalling"], false);
    }
}
