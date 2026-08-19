#[path = "report_authority.rs"]
mod report_authority;
#[path = "report_authority_dir.rs"]
mod report_authority_dir;
#[path = "report_transport_v2.rs"]
mod report_transport_v2;

use crate::operations::{
    OperationCoordinator, ReportUploadProcess, refuse_benchmark_for_report, refuse_update_install,
};
use crate::report_transport::{
    configured_report_origin, emit_report_state, perform_report_deletion, report_client,
    validate_report_receipt,
};
use crate::take_deferred_exit;
use report_authority::{ReportAuthorityStore, ReportCaseView, claim_automatic_report};
use report_transport_v2::{
    ReportUploadAttempt, perform_report_upload_with_authority, validated_report_archive_bytes,
};
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use tauri::{AppHandle, State};
use tokio::sync::watch;

static NEXT_REPORT_UPLOAD_ID: AtomicU64 = AtomicU64::new(1);
static AUTOMATIC_REPORT_UPLOAD: Mutex<Option<ReportUploadProcess>> = Mutex::new(None);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ReportUploadKind {
    Manual,
    Automatic,
}

impl ReportUploadKind {
    fn parse(value: &str) -> Result<Self, NativeCommandError> {
        match value {
            "manual" => Ok(Self::Manual),
            "automatic" => Ok(Self::Automatic),
            _ => Err(NativeCommandError::new(
                "report-upload-kind-invalid",
                "Run report uploads must be manual or automatic.",
                false,
            )),
        }
    }

    fn as_str(self) -> &'static str {
        match self {
            Self::Manual => "manual",
            Self::Automatic => "automatic",
        }
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReportIntakeStatus {
    configured: bool,
    origin: Option<String>,
    reason: Option<String>,
    reports: Vec<ReportCaseView>,
    automatic_claimed: Option<bool>,
    legacy_receipt_imported: bool,
    background_upload_id: Option<u64>,
    background_upload_total_bytes: Option<u64>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeCommandError {
    pub(crate) code: &'static str,
    pub(crate) message: String,
    pub(crate) retryable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) detail: Option<String>,
}

impl NativeCommandError {
    fn new(code: &'static str, message: impl Into<String>, retryable: bool) -> Self {
        Self {
            code,
            message: message.into(),
            retryable,
            detail: None,
        }
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReportUploadStateEvent {
    pub(crate) state: &'static str,
    pub(crate) upload_id: u64,
    pub(crate) uploaded_bytes: u64,
    pub(crate) total_bytes: u64,
    pub(crate) case_id: Option<String>,
    pub(crate) receipt: Option<ReportCaseView>,
    pub(crate) detail: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) kind: Option<&'static str>,
}

impl ReportUploadStateEvent {
    pub(crate) fn new(
        state: &'static str,
        upload_id: u64,
        uploaded_bytes: u64,
        total_bytes: u64,
    ) -> Self {
        Self {
            state,
            upload_id,
            uploaded_bytes,
            total_bytes,
            case_id: None,
            receipt: None,
            detail: None,
            kind: None,
        }
    }
    pub(crate) fn with_case(mut self, case_id: String) -> Self {
        self.case_id = Some(case_id);
        self
    }
    pub(crate) fn with_receipt(mut self, receipt: ReportCaseView) -> Self {
        self.receipt = Some(receipt);
        self
    }
    pub(crate) fn with_detail(mut self, detail: String) -> Self {
        self.detail = Some(detail);
        self
    }
    pub(crate) fn with_kind(mut self, kind: &'static str) -> Self {
        self.kind = Some(kind);
        self
    }
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReportUploadInput {
    pub(crate) output: String,
    pub(crate) bytes: u64,
    pub(crate) sha256: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReportDeletion {
    pub(crate) method: String,
    pub(crate) url: String,
    pub(crate) token: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReportReceipt {
    pub(crate) protocol_version: u32,
    pub(crate) case_id: String,
    pub(crate) object_key: String,
    pub(crate) bytes: u64,
    pub(crate) sha256: String,
    pub(crate) product_version: String,
    pub(crate) received_at: String,
    pub(crate) retention_deadline: String,
    pub(crate) deletion: ReportDeletion,
    pub(crate) signature: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CreateReportCaseRequest<'a> {
    pub(crate) protocol_version: u32,
    pub(crate) product_version: &'a str,
    pub(crate) bytes: u64,
    pub(crate) sha256: &'a str,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReportGrantEndpoint {
    pub(crate) method: String,
    pub(crate) url: String,
    #[serde(default)]
    pub(crate) content_type: Option<String>,
    #[serde(default)]
    pub(crate) expires_at: Option<String>,
    pub(crate) token: String,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct CreateReportCaseResponse {
    pub(crate) protocol_version: u32,
    pub(crate) case_id: String,
    pub(crate) upload: ReportGrantEndpoint,
    pub(crate) finalize: ReportGrantEndpoint,
    pub(crate) deletion: ReportGrantEndpoint,
}

#[derive(Debug)]
pub(crate) enum ReportUploadError {
    Cancelled,
    Failed(String),
}

fn upload_outcome_error(error: ReportUploadError) -> NativeCommandError {
    match error {
        ReportUploadError::Cancelled => NativeCommandError::new(
            "report-upload-cancelled",
            "Run report upload was cancelled.",
            true,
        ),
        ReportUploadError::Failed(detail) => {
            NativeCommandError::new("report-upload-failed", detail, false)
        }
    }
}

#[tauri::command]
pub(crate) fn get_report_intake_status(
    app: AppHandle,
    legacy_receipt: Option<ReportReceipt>,
    automatic_run_identity: Option<String>,
) -> Result<ReportIntakeStatus, String> {
    let store = ReportAuthorityStore::create(&app)?;
    let origin_result = configured_report_origin();
    let mut legacy_receipt_imported = false;
    if let (Ok(origin), Some(receipt)) = (&origin_result, legacy_receipt.as_ref()) {
        let disclosed = ReportUploadInput {
            output: "legacy-renderer-receipt".to_string(),
            bytes: receipt.bytes,
            sha256: receipt.sha256.clone(),
        };
        validate_report_receipt(origin, receipt, &receipt.case_id, &disclosed)?;
        store.persist_accepted(receipt)?;
        legacy_receipt_imported = true;
    }
    let automatic_claimed = if origin_result.is_ok() {
        automatic_run_identity
            .as_deref()
            .map(|identity| claim_automatic_report(&app, identity))
            .transpose()?
    } else {
        None
    };
    let (background_upload_id, background_upload_total_bytes) = background_upload_snapshot()?;
    let reports = store.reports()?;
    Ok(match origin_result {
        Ok(origin) => ReportIntakeStatus {
            configured: true,
            origin: Some(origin.origin().ascii_serialization()),
            reason: None,
            reports,
            automatic_claimed,
            legacy_receipt_imported,
            background_upload_id,
            background_upload_total_bytes,
        },
        Err(reason) => ReportIntakeStatus {
            configured: false,
            origin: None,
            reason: Some(reason),
            reports,
            automatic_claimed: None,
            legacy_receipt_imported,
            background_upload_id,
            background_upload_total_bytes,
        },
    })
}

#[tauri::command]
pub(crate) async fn send_run_report(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    report: ReportUploadInput,
    kind: String,
) -> Result<ReportCaseView, NativeCommandError> {
    let kind = ReportUploadKind::parse(&kind)?;
    let origin = configured_report_origin()
        .map_err(|message| NativeCommandError::new("report-intake-unavailable", message, false))?;
    let archive = validated_report_archive_bytes(&report)
        .map_err(|message| NativeCommandError::new("report-archive-invalid", message, false))?;
    let client = report_client().map_err(|message| {
        NativeCommandError::new("report-transport-unavailable", message, true)
    })?;
    let store = ReportAuthorityStore::create(&app).map_err(|message| {
        NativeCommandError::new("report-authority-unavailable", message, false)
    })?;
    let lifecycle = store.lifecycle();
    let id = NEXT_REPORT_UPLOAD_ID.fetch_add(1, Ordering::Relaxed);
    let (cancel, cancel_receiver) = watch::channel(false);

    match kind {
        ReportUploadKind::Manual => {
            let mut running = tracker.0.lock().map_err(|_| {
                NativeCommandError::new(
                    "operation-state-unavailable",
                    "The report upload tracker is unavailable.",
                    true,
                )
            })?;
            refuse_update_install(&running)
                .map_err(|message| NativeCommandError::new("operation-conflict", message, true))?;
            refuse_benchmark_for_report(&running)
                .map_err(|message| NativeCommandError::new("operation-conflict", message, true))?;
            if running.report_upload.is_some() {
                return Err(NativeCommandError::new(
                    "report-upload-active",
                    "A run report is already being sent.",
                    true,
                ));
            }
            cancel_background_report().map_err(|message| {
                NativeCommandError::new("operation-state-unavailable", message, true)
            })?;
            running.report_upload = Some(ReportUploadProcess {
                id,
                total_bytes: report.bytes,
                cancel: cancel.clone(),
            });
        }
        ReportUploadKind::Automatic => {
            let running = tracker.0.try_lock().map_err(|error| match error {
                std::sync::TryLockError::WouldBlock => NativeCommandError::new(
                    "operation-conflict",
                    "A foreground Preflight operation is changing state. The automatic support ZIP was kept for manual review.",
                    true,
                ),
                std::sync::TryLockError::Poisoned(_) => NativeCommandError::new(
                    "operation-state-unavailable",
                    "The report upload tracker is unavailable.",
                    true,
                ),
            })?;
            refuse_update_install(&running)
                .map_err(|message| NativeCommandError::new("operation-conflict", message, true))?;
            refuse_benchmark_for_report(&running)
                .map_err(|message| NativeCommandError::new("operation-conflict", message, true))?;
            if running.report_upload.is_some() {
                return Err(NativeCommandError::new(
                    "report-upload-active",
                    "A manual run report is already being sent.",
                    true,
                ));
            }
            // Publish the background slot while the foreground coordinator is still held.
            // Manual/player actions take the locks in this same order, then cancel this slot.
            let mut background = AUTOMATIC_REPORT_UPLOAD.lock().map_err(|_| {
                NativeCommandError::new(
                    "operation-state-unavailable",
                    "The background report tracker is unavailable.",
                    true,
                )
            })?;
            if background.is_some() {
                return Err(NativeCommandError::new(
                    "report-upload-active",
                    "Another automatic run report is already being sent.",
                    true,
                ));
            }
            *background = Some(ReportUploadProcess {
                id,
                total_bytes: report.bytes,
                cancel: cancel.clone(),
            });
        }
    }

    emit_report_state(
        &app,
        ReportUploadStateEvent::new("starting", id, 0, report.bytes).with_kind(kind.as_str()),
    );
    let upload_app = app.clone();
    let outcome = perform_report_upload_with_authority(
        ReportUploadAttempt {
            client,
            origin,
            archive,
            report: report.clone(),
            id,
            cancel: cancel_receiver,
        },
        &lifecycle,
        move |event| emit_report_state(&upload_app, event.with_kind(kind.as_str())),
    )
    .await;

    let should_exit = match kind {
        ReportUploadKind::Manual => {
            if let Ok(mut running) = tracker.0.lock() {
                if running
                    .report_upload
                    .as_ref()
                    .is_some_and(|upload| upload.id == id)
                {
                    running.report_upload = None;
                }
                take_deferred_exit(&mut running)
            } else {
                false
            }
        }
        ReportUploadKind::Automatic => {
            if let Ok(mut background) = AUTOMATIC_REPORT_UPLOAD.lock() {
                if background.as_ref().is_some_and(|upload| upload.id == id) {
                    *background = None;
                }
            }
            false
        }
    };

    match &outcome {
        Ok(receipt) => emit_report_state(
            &app,
            ReportUploadStateEvent::new("finished", id, report.bytes, report.bytes)
                .with_case(receipt.case_id.clone())
                .with_receipt(ReportCaseView::accepted(receipt))
                .with_kind(kind.as_str()),
        ),
        Err(ReportUploadError::Cancelled) => emit_report_state(
            &app,
            ReportUploadStateEvent::new("cancelled", id, 0, report.bytes)
                .with_detail("Run report upload stopped. The local ZIP is unchanged.".to_string())
                .with_kind(kind.as_str()),
        ),
        Err(ReportUploadError::Failed(detail)) => emit_report_state(
            &app,
            ReportUploadStateEvent::new("failed", id, 0, report.bytes)
                .with_detail(detail.clone())
                .with_kind(kind.as_str()),
        ),
    }
    if should_exit {
        app.exit(0);
    }
    outcome
        .map(|receipt| ReportCaseView::accepted(&receipt))
        .map_err(upload_outcome_error)
}

#[tauri::command]
pub(crate) fn cancel_run_report(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
) -> Result<bool, String> {
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The report upload tracker is unavailable.".to_string())?;
    let Some(upload) = running.report_upload.as_ref() else {
        return Ok(false);
    };
    let id = upload.id;
    let total_bytes = upload.total_bytes;
    upload
        .cancel
        .send(true)
        .map_err(|_| "The report upload has already stopped.".to_string())?;
    drop(running);
    emit_report_state(
        &app,
        ReportUploadStateEvent::new("cancelling", id, 0, total_bytes)
            .with_detail("Stopping the report upload…".to_string())
            .with_kind(ReportUploadKind::Manual.as_str()),
    );
    Ok(true)
}

#[tauri::command]
pub(crate) async fn delete_run_report(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    case_id: String,
    action: String,
) -> Result<bool, String> {
    match action.as_str() {
        "cancel-background" => return cancel_background_report(),
        "clear-all" => {
            let _ = cancel_background_report()?;
            ReportAuthorityStore::clear_all(&app)?;
            return Ok(true);
        }
        "delete" | "dismiss" => {}
        _ => return Err("Unknown run-report action.".to_string()),
    }
    {
        let running = tracker
            .0
            .lock()
            .map_err(|_| "The report upload tracker is unavailable.".to_string())?;
        refuse_benchmark_for_report(&running)?;
    }
    let store = ReportAuthorityStore::create(&app)?;
    if action == "dismiss" {
        store.dismiss(&case_id)?;
        return Ok(true);
    }
    let deletion = store.deletion(&case_id)?;
    let origin = configured_report_origin()?;
    perform_report_deletion(report_client()?, origin, deletion).await?;
    store.dismiss(&case_id)?;
    Ok(true)
}

fn cancel_background_report() -> Result<bool, String> {
    let mut background = AUTOMATIC_REPORT_UPLOAD
        .lock()
        .map_err(|_| "The background report tracker is unavailable.".to_string())?;
    let Some(upload) = background.take() else {
        return Ok(false);
    };
    let _ = upload.cancel.send(true);
    Ok(true)
}

fn background_upload_snapshot() -> Result<(Option<u64>, Option<u64>), String> {
    let background = AUTOMATIC_REPORT_UPLOAD
        .lock()
        .map_err(|_| "The background report tracker is unavailable.".to_string())?;
    Ok((
        background.as_ref().map(|upload| upload.id),
        background.as_ref().map(|upload| upload.total_bytes),
    ))
}

#[cfg(test)]
mod tests {
    use super::{
        AUTOMATIC_REPORT_UPLOAD, ReportUploadError, cancel_background_report,
        upload_outcome_error,
    };
    use crate::operations::ReportUploadProcess;
    use tokio::sync::watch;

    #[test]
    fn foreground_cancellation_signals_and_clears_automatic_upload() {
        let (cancel, receiver) = watch::channel(false);
        {
            let mut background = AUTOMATIC_REPORT_UPLOAD.lock().unwrap();
            assert!(background.is_none());
            *background = Some(ReportUploadProcess {
                id: 7,
                total_bytes: 11,
                cancel,
            });
        }

        assert!(cancel_background_report().unwrap());
        assert!(*receiver.borrow());
        assert!(AUTOMATIC_REPORT_UPLOAD.lock().unwrap().is_none());
    }

    #[test]
    fn cancellation_has_a_stable_machine_readable_error_code() {
        let error = upload_outcome_error(ReportUploadError::Cancelled);
        assert_eq!(
            serde_json::json!({
                "code": "report-upload-cancelled",
                "message": "Run report upload was cancelled.",
                "retryable": true
            }),
            serde_json::to_value(error).unwrap()
        );
    }

    #[test]
    fn generic_upload_failure_does_not_overclaim_recovery_semantics() {
        let error = upload_outcome_error(ReportUploadError::Failed("receipt mismatch".to_string()));
        let value = serde_json::to_value(error).unwrap();
        assert_eq!("report-upload-failed", value["code"]);
        assert_eq!("receipt mismatch", value["message"]);
        assert_eq!(false, value["retryable"]);
    }
}
