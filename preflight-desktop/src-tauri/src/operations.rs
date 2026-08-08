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
