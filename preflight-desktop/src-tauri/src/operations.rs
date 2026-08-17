use serde::Serialize;
use std::path::PathBuf;
use std::sync::{Mutex, mpsc};
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
    let mut state = operations
        .lock()
        .map_err(|_| "The operation coordinator is unavailable.".to_string())?;
    if state.desktop_smoke.is_some() {
        return Err(
            "Wait for the startup benchmark to finish or cancel it before creating a support file."
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
        return Err("Wait for the support file to finish before removing Preflight data.".to_string());
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
