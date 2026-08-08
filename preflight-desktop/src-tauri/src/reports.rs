use crate::operations::{OperationCoordinator, ReportUploadProcess, refuse_update_install};
use crate::{
    configured_report_origin, emit_report_state, perform_report_deletion, perform_report_upload,
    report_client, take_deferred_exit, validated_report_archive,
};
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicU64, Ordering};
use tauri::{AppHandle, State};
use tokio::sync::watch;

static NEXT_REPORT_UPLOAD_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReportIntakeStatus {
    configured: bool,
    origin: Option<String>,
    reason: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReportUploadStateEvent {
    pub(crate) state: &'static str,
    pub(crate) upload_id: u64,
    pub(crate) uploaded_bytes: u64,
    pub(crate) total_bytes: u64,
    pub(crate) case_id: Option<String>,
    pub(crate) receipt: Option<ReportReceipt>,
    pub(crate) detail: Option<String>,
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
        }
    }

    pub(crate) fn with_case(mut self, case_id: String) -> Self {
        self.case_id = Some(case_id);
        self
    }

    pub(crate) fn with_receipt(mut self, receipt: ReportReceipt) -> Self {
        self.receipt = Some(receipt);
        self
    }

    pub(crate) fn with_detail(mut self, detail: String) -> Self {
        self.detail = Some(detail);
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

#[derive(Deserialize)]
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

#[derive(Deserialize)]
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

#[tauri::command]
pub(crate) fn get_report_intake_status() -> ReportIntakeStatus {
    match configured_report_origin() {
        Ok(origin) => ReportIntakeStatus {
            configured: true,
            origin: Some(origin.origin().ascii_serialization()),
            reason: None,
        },
        Err(reason) => ReportIntakeStatus {
            configured: false,
            origin: None,
            reason: Some(reason),
        },
    }
}

#[tauri::command]
pub(crate) async fn send_run_report(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    report: ReportUploadInput,
) -> Result<ReportReceipt, String> {
    let origin = configured_report_origin()?;
    let archive = validated_report_archive(&report)?;
    let client = report_client()?;
    let id = NEXT_REPORT_UPLOAD_ID.fetch_add(1, Ordering::Relaxed);
    let (cancel, cancel_receiver) = watch::channel(false);
    {
        let mut running = tracker
            .0
            .lock()
            .map_err(|_| "The report upload tracker is unavailable.".to_string())?;
        refuse_update_install(&running)?;
        if running.report_upload.is_some() {
            return Err("A run report is already being sent.".to_string());
        }
        running.report_upload = Some(ReportUploadProcess {
            id,
            total_bytes: report.bytes,
            cancel,
        });
    }
    emit_report_state(
        &app,
        ReportUploadStateEvent::new("starting", id, 0, report.bytes),
    );

    let upload_app = app.clone();
    let outcome = perform_report_upload(
        client,
        origin,
        archive,
        report.clone(),
        id,
        cancel_receiver,
        move |event| emit_report_state(&upload_app, event),
    )
    .await;
    let should_exit = if let Ok(mut running) = tracker.0.lock() {
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
    };

    match &outcome {
        Ok(receipt) => emit_report_state(
            &app,
            ReportUploadStateEvent::new("finished", id, report.bytes, report.bytes)
                .with_case(receipt.case_id.clone())
                .with_receipt(receipt.clone()),
        ),
        Err(ReportUploadError::Cancelled) => emit_report_state(
            &app,
            ReportUploadStateEvent::new("cancelled", id, 0, report.bytes)
                .with_detail("Run report upload stopped. The local ZIP is unchanged.".to_string()),
        ),
        Err(ReportUploadError::Failed(detail)) => emit_report_state(
            &app,
            ReportUploadStateEvent::new("failed", id, 0, report.bytes).with_detail(detail.clone()),
        ),
    }
    if should_exit {
        app.exit(0);
    }
    outcome.map_err(|error| match error {
        ReportUploadError::Cancelled => "Run report upload was cancelled.".to_string(),
        ReportUploadError::Failed(detail) => detail,
    })
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
            .with_detail("Stopping the report upload…".to_string()),
    );
    Ok(true)
}

#[tauri::command]
pub(crate) async fn delete_run_report(deletion: ReportDeletion) -> Result<bool, String> {
    let origin = configured_report_origin()?;
    perform_report_deletion(report_client()?, origin, deletion).await
}
