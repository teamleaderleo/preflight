use serde::Serialize;
use std::path::PathBuf;
use std::sync::{Mutex, mpsc};
use tokio::sync::watch;

#[derive(Default)]
pub(crate) struct OperationState {
    pub(crate) game: Option<u32>,
    pub(crate) desktop_smoke: Option<DesktopSmokeProcess>,
    pub(crate) preparation: Option<PreparationProcess>,
    pub(crate) report_upload: Option<ReportUploadProcess>,
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
    pub(crate) desktop_smoke_pid: Option<u32>,
    pub(crate) desktop_smoke_run_directory: Option<String>,
    pub(crate) preparation_pid: Option<u32>,
    pub(crate) report_upload_id: Option<u64>,
    pub(crate) report_upload_total_bytes: Option<u64>,
    pub(crate) update_installing: bool,
}

impl OperationSnapshot {
    pub(crate) fn from_state(state: &OperationState) -> Self {
        Self {
            format: "preflight-operation-state-v1",
            game_pid: state.game,
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
            update_installing: state.update_installing,
        }
    }
}

#[derive(Default)]
pub(crate) struct OperationCoordinator(pub(crate) Mutex<OperationState>);

pub(crate) struct UpdateInstallGuard<'a> {
    operations: &'a Mutex<OperationState>,
}

impl Drop for UpdateInstallGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut state) = self.operations.lock() {
            state.update_installing = false;
        }
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn update_install_refuses_active_report_upload() {
        let (cancel, _receiver) = watch::channel(false);
        let operations = Mutex::new(OperationState {
            report_upload: Some(ReportUploadProcess {
                id: 7,
                total_bytes: 1024,
                cancel,
            }),
            ..OperationState::default()
        });

        let error = begin_update_install(&operations)
            .err()
            .expect("an active report upload must block update installation");

        assert_eq!(
            error,
            "Wait for the run report upload to finish or cancel it before installing an update."
        );
        assert!(!operations.lock().unwrap().update_installing);
    }
}
