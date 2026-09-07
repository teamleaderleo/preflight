#[path = "bound_directory.rs"]
mod bound_directory;

use crate::operations::{
    OperationCoordinator, ReportUploadProcess, refuse_benchmark_for_report, refuse_update_install,
};
use crate::report_transport::{
    ReportRecoveryOutcome, configured_report_origin, emit_report_state, perform_report_deletion,
    perform_report_upload_with_state, recover_granted_report, recover_pending_report,
    report_client, validated_report_snapshot,
};
use crate::take_deferred_exit;
use bound_directory::BoundDirectory;
use serde::{Deserialize, Serialize};
use std::ffi::{OsStr, OsString};
use std::fs;
use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use tauri::{AppHandle, Manager, State};
use tokio::sync::watch;

static NEXT_REPORT_UPLOAD_ID: AtomicU64 = AtomicU64::new(1);
const REPORT_STATE_DIRECTORY: &str = "private/report-cases";
const REPORT_STATE_PREFIX: &str = "report-state-";
const REPORT_STATE_SUFFIX: &str = ".json";
const REPORT_STATE_LIMIT: u64 = 64 * 1024;

#[cfg(test)]
static FAIL_NEXT_REPORT_STATE_PUBLISH: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReportIntakeStatus {
    configured: bool,
    origin: Option<String>,
    reason: Option<String>,
    report_case: Option<ReportTransactionResult>,
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

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) enum ReportLifecycleState {
    Accepted,
    CleanupConfirmed,
    RemoteOutcomeUnknown,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReportTransactionResult {
    pub(crate) state: ReportLifecycleState,
    pub(crate) case_id: Option<String>,
    pub(crate) receipt: Option<SupportReportReceipt>,
    pub(crate) detail: Option<String>,
}

impl ReportTransactionResult {
    fn accepted(receipt: &ReportReceipt) -> Self {
        Self {
            state: ReportLifecycleState::Accepted,
            case_id: Some(receipt.case_id.clone()),
            receipt: Some(SupportReportReceipt::from(receipt)),
            detail: None,
        }
    }

    fn cleanup_confirmed(detail: impl Into<String>) -> Self {
        Self {
            state: ReportLifecycleState::CleanupConfirmed,
            case_id: None,
            receipt: None,
            detail: Some(detail.into()),
        }
    }

    fn unknown(case_id: Option<String>, detail: impl Into<String>) -> Self {
        Self {
            state: ReportLifecycleState::RemoteOutcomeUnknown,
            case_id,
            receipt: None,
            detail: Some(detail.into()),
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
    pub(crate) receipt: Option<SupportReportReceipt>,
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

    pub(crate) fn with_receipt(mut self, receipt: SupportReportReceipt) -> Self {
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

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReportRemoteIdentity {
    pub(crate) product_version: String,
    pub(crate) bytes: u64,
    pub(crate) sha256: String,
}

impl ReportRemoteIdentity {
    fn for_upload(report: &ReportUploadInput) -> Self {
        Self {
            product_version: env!("CARGO_PKG_VERSION").to_string(),
            bytes: report.bytes,
            sha256: report.sha256.clone(),
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReportDeletion {
    pub(crate) method: String,
    pub(crate) url: String,
    pub(crate) token: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
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

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct SupportReportReceipt {
    pub(crate) case_id: String,
    pub(crate) bytes: u64,
    pub(crate) sha256: String,
    pub(crate) product_version: String,
    pub(crate) received_at: String,
    pub(crate) retention_deadline: String,
}

impl From<&ReportReceipt> for SupportReportReceipt {
    fn from(receipt: &ReportReceipt) -> Self {
        Self {
            case_id: receipt.case_id.clone(),
            bytes: receipt.bytes,
            sha256: receipt.sha256.clone(),
            product_version: receipt.product_version.clone(),
            received_at: receipt.received_at.clone(),
            retention_deadline: receipt.retention_deadline.clone(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CreateReportCaseRequest<'a> {
    pub(crate) protocol_version: u32,
    pub(crate) product_version: &'a str,
    pub(crate) bytes: u64,
    pub(crate) sha256: &'a str,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
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

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct CreateReportCaseResponse {
    pub(crate) protocol_version: u32,
    pub(crate) case_id: String,
    pub(crate) upload: ReportGrantEndpoint,
    pub(crate) finalize: ReportGrantEndpoint,
    pub(crate) deletion: ReportGrantEndpoint,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub(crate) enum ReportRecoveryKind {
    CreateThenDelete,
    Delete,
    Finalize,
}

#[derive(Debug)]
pub(crate) enum ReportUploadError {
    Cancelled,
    Failed(String),
    RemoteOutcomeUnknown {
        case_id: Option<String>,
        recovery: ReportRecoveryKind,
        detail: String,
    },
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(tag = "state", rename_all = "kebab-case")]
enum StoredReportState {
    Pending {
        transaction_id: String,
        identity: ReportRemoteIdentity,
    },
    Granted {
        transaction_id: String,
        identity: ReportRemoteIdentity,
        grant: CreateReportCaseResponse,
        recovery: ReportRecoveryKind,
    },
    Accepted {
        transaction_id: String,
        receipt: ReportReceipt,
    },
}

impl StoredReportState {
    fn file_name(&self) -> String {
        match self {
            Self::Pending { transaction_id, .. } => {
                format!("{REPORT_STATE_PREFIX}pending-{transaction_id}{REPORT_STATE_SUFFIX}")
            }
            Self::Granted {
                transaction_id,
                recovery,
                ..
            } => {
                let phase = match recovery {
                    ReportRecoveryKind::CreateThenDelete => "create-delete",
                    ReportRecoveryKind::Delete => "delete",
                    ReportRecoveryKind::Finalize => "finalize",
                };
                format!(
                    "{REPORT_STATE_PREFIX}granted-{phase}-{transaction_id}{REPORT_STATE_SUFFIX}"
                )
            }
            Self::Accepted { receipt, .. } => format!(
                "{REPORT_STATE_PREFIX}accepted-{}{REPORT_STATE_SUFFIX}",
                receipt.case_id
            ),
        }
    }

    fn priority(&self) -> u8 {
        match self {
            Self::Pending { .. } => 1,
            Self::Granted {
                recovery: ReportRecoveryKind::CreateThenDelete | ReportRecoveryKind::Delete,
                ..
            } => 2,
            Self::Granted {
                recovery: ReportRecoveryKind::Finalize,
                ..
            } => 3,
            Self::Accepted { .. } => 4,
        }
    }

    fn current_view(&self, detail: impl Into<String>) -> ReportTransactionResult {
        match self {
            Self::Accepted { receipt, .. } => ReportTransactionResult::accepted(receipt),
            Self::Granted { grant, .. } => {
                ReportTransactionResult::unknown(Some(grant.case_id.clone()), detail)
            }
            Self::Pending { .. } => ReportTransactionResult::unknown(None, detail),
        }
    }
}

struct ReportStore {
    directory: BoundDirectory,
}

impl ReportStore {
    fn for_app(app: &AppHandle) -> Result<Self, String> {
        let anchor = app
            .path()
            .app_data_dir()
            .map_err(|error| format!("Could not resolve private report storage: {error}"))?;
        Self::open_at(&anchor)
    }

    fn open_at(anchor: &Path) -> Result<Self, String> {
        fs::create_dir_all(anchor)
            .map_err(|error| format!("Could not create private report storage root: {error}"))?;
        let directory =
            BoundDirectory::open_or_create(anchor, Path::new(REPORT_STATE_DIRECTORY))
                .map_err(|error| format!("Could not open private report storage: {error}"))?;
        Ok(Self { directory })
    }

    fn state_names(&self) -> Result<Vec<OsString>, String> {
        let names = self
            .directory
            .list_names()
            .map_err(|error| format!("Could not list private report state: {error}"))?;
        let mut states = Vec::new();
        for name in names {
            let text = name.to_str().ok_or_else(|| {
                "Private report storage contains an invalid filename.".to_string()
            })?;
            if !text.starts_with(REPORT_STATE_PREFIX) || !text.ends_with(REPORT_STATE_SUFFIX) {
                return Err(
                    "Private report storage contains an unexpected entry; reporting is paused."
                        .to_string(),
                );
            }
            states.push(name);
        }
        Ok(states)
    }

    fn load(&self) -> Result<Option<StoredReportState>, String> {
        let mut selected: Option<StoredReportState> = None;
        let mut invalid = Vec::new();
        for name in self.state_names()? {
            let state = match self.directory.read_bytes(&name, REPORT_STATE_LIMIT) {
                Ok(bytes) => match serde_json::from_slice::<StoredReportState>(&bytes) {
                    Ok(state) if OsStr::new(&state.file_name()) == name.as_os_str() => state,
                    Ok(_) => {
                        invalid.push(
                            "private report state filename does not match its contents".to_string(),
                        );
                        continue;
                    }
                    Err(error) => {
                        invalid.push(format!("private report state is invalid: {error}"));
                        continue;
                    }
                },
                Err(error) => {
                    invalid.push(format!("could not read private report state: {error}"));
                    continue;
                }
            };
            match &selected {
                None => selected = Some(state),
                Some(current) if state.priority() > current.priority() => selected = Some(state),
                Some(current) if state.priority() == current.priority() && state != *current => {
                    return Err(
                        "Private report storage contains multiple active report states."
                            .to_string(),
                    );
                }
                Some(_) => {}
            }
        }
        match selected {
            Some(state) => Ok(Some(state)),
            None if invalid.is_empty() => Ok(None),
            None => Err(format!(
                "Private report storage has no readable recovery state: {}",
                invalid.join("; ")
            )),
        }
    }

    fn publish(&self, state: &StoredReportState) -> Result<(), String> {
        #[cfg(test)]
        if FAIL_NEXT_REPORT_STATE_PUBLISH.swap(false, Ordering::SeqCst) {
            return Err("injected private report publication failure".to_string());
        }

        let bytes = serde_json::to_vec(state)
            .map_err(|error| format!("Could not serialize private report state: {error}"))?;
        if bytes.len() as u64 > REPORT_STATE_LIMIT {
            return Err("Private report state exceeds its bounded storage limit.".to_string());
        }
        let name = state.file_name();
        let name_os = OsStr::new(&name);
        if self
            .directory
            .exists_regular(name_os)
            .map_err(|error| format!("Could not inspect private report state: {error}"))?
        {
            let existing = self
                .directory
                .read_bytes(name_os, REPORT_STATE_LIMIT)
                .map_err(|error| format!("Could not read private report state: {error}"))?;
            if existing != bytes {
                return Err(
                    "Private report state publication collided with different data.".to_string(),
                );
            }
        } else {
            let mut file = self
                .directory
                .create_new(name_os, 0o600)
                .map_err(|error| format!("Could not create private report state: {error}"))?;
            if let Err(error) = file.write_all(&bytes).and_then(|()| file.sync_all()) {
                drop(file);
                let _ = self.directory.delete_file(name_os);
                let _ = self.directory.sync();
                return Err(format!(
                    "Could not durably write private report state: {error}"
                ));
            }
            self.directory
                .sync()
                .map_err(|error| format!("Could not publish private report state: {error}"))?;
        }

        for stale in self.state_names()? {
            if stale.as_os_str() != name_os {
                self.directory.delete_file(&stale).map_err(|error| {
                    format!("Could not retire superseded private report state: {error}")
                })?;
            }
        }
        self.directory
            .sync()
            .map_err(|error| format!("Could not sync private report state directory: {error}"))
    }

    fn clear(&self) -> Result<(), String> {
        for name in self.state_names()? {
            self.directory
                .delete_file(&name)
                .map_err(|error| format!("Could not clear private report state: {error}"))?;
        }
        self.directory
            .sync()
            .map_err(|error| format!("Could not sync private report state directory: {error}"))
    }

    fn accepted_deletion(&self, case_id: &str) -> Result<ReportDeletion, String> {
        match self.load()? {
            Some(StoredReportState::Accepted { receipt, .. }) if receipt.case_id == case_id => {
                Ok(receipt.deletion)
            }
            Some(StoredReportState::Accepted { .. }) => {
                Err("The requested report case does not match the saved receipt.".to_string())
            }
            Some(_) => Err(
                "The report case still needs remote reconciliation before deletion.".to_string(),
            ),
            None => Err("No deletable report case is saved on this computer.".to_string()),
        }
    }
}

fn new_report_transaction_id() -> Result<String, String> {
    let mut bytes = [0_u8; 16];
    getrandom::fill(&mut bytes)
        .map_err(|error| format!("Could not create a report transaction identity: {error}"))?;
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Ok(format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0],
        bytes[1],
        bytes[2],
        bytes[3],
        bytes[4],
        bytes[5],
        bytes[6],
        bytes[7],
        bytes[8],
        bytes[9],
        bytes[10],
        bytes[11],
        bytes[12],
        bytes[13],
        bytes[14],
        bytes[15]
    ))
}

async fn recover_stored_case(
    store: &ReportStore,
    client: &reqwest::Client,
    origin: &url::Url,
) -> Result<Option<ReportTransactionResult>, String> {
    let Some(state) = store.load()? else {
        return Ok(None);
    };
    match state {
        StoredReportState::Accepted { receipt, .. } => {
            Ok(Some(ReportTransactionResult::accepted(&receipt)))
        }
        StoredReportState::Pending {
            transaction_id,
            identity,
        } => match recover_pending_report(client, origin, &transaction_id, &identity).await {
            ReportRecoveryOutcome::CleanupConfirmed => {
                store.clear()?;
                Ok(None)
            }
            ReportRecoveryOutcome::Accepted(receipt) => {
                let accepted = StoredReportState::Accepted {
                    transaction_id,
                    receipt: receipt.clone(),
                };
                store.publish(&accepted)?;
                Ok(Some(ReportTransactionResult::accepted(&receipt)))
            }
            ReportRecoveryOutcome::RemoteOutcomeUnknown { case_id, detail } => {
                Ok(Some(ReportTransactionResult::unknown(case_id, detail)))
            }
        },
        StoredReportState::Granted {
            transaction_id,
            identity,
            grant,
            recovery,
        } => match recover_granted_report(client, origin, &grant, &identity, recovery).await {
            ReportRecoveryOutcome::CleanupConfirmed => {
                store.clear()?;
                Ok(None)
            }
            ReportRecoveryOutcome::Accepted(receipt) => {
                let accepted = StoredReportState::Accepted {
                    transaction_id,
                    receipt: receipt.clone(),
                };
                match store.publish(&accepted) {
                    Ok(()) => Ok(Some(ReportTransactionResult::accepted(&receipt))),
                    Err(error) => Ok(Some(ReportTransactionResult::unknown(
                        Some(receipt.case_id.clone()),
                        format!(
                            "The report service accepted the case, but Preflight could not durably publish its deletion receipt: {error}. The saved recovery grant will be reconciled before another report is created."
                        ),
                    ))),
                }
            }
            ReportRecoveryOutcome::RemoteOutcomeUnknown { case_id, detail } => {
                Ok(Some(ReportTransactionResult::unknown(case_id, detail)))
            }
        },
    }
}

#[tauri::command]
pub(crate) async fn get_report_intake_status(app: AppHandle) -> ReportIntakeStatus {
    let origin = match configured_report_origin() {
        Ok(origin) => origin,
        Err(reason) => {
            return ReportIntakeStatus {
                configured: false,
                origin: None,
                reason: Some(reason),
                report_case: None,
            };
        }
    };
    let store = match ReportStore::for_app(&app) {
        Ok(store) => store,
        Err(reason) => {
            return ReportIntakeStatus {
                configured: false,
                origin: Some(origin.origin().ascii_serialization()),
                reason: Some(reason),
                report_case: None,
            };
        }
    };
    let client = match report_client() {
        Ok(client) => client,
        Err(reason) => {
            return ReportIntakeStatus {
                configured: false,
                origin: Some(origin.origin().ascii_serialization()),
                reason: Some(reason),
                report_case: store.load().ok().flatten().map(|state| {
                    state.current_view(
                        "A saved report case still needs remote reconciliation before another report can be sent.",
                    )
                }),
            };
        }
    };
    match recover_stored_case(&store, &client, &origin).await {
        Ok(report_case) => {
            let unresolved = report_case.as_ref().is_some_and(|case| {
                case.state == ReportLifecycleState::RemoteOutcomeUnknown
            });
            let reason = report_case.as_ref().and_then(|case| {
                if case.state == ReportLifecycleState::RemoteOutcomeUnknown {
                    case.detail.clone()
                } else {
                    None
                }
            });
            ReportIntakeStatus {
                configured: !unresolved,
                origin: Some(origin.origin().ascii_serialization()),
                reason,
                report_case,
            }
        }
        Err(reason) => ReportIntakeStatus {
            configured: false,
            origin: Some(origin.origin().ascii_serialization()),
            reason: Some(reason),
            report_case: store.load().ok().flatten().map(|state| {
                state.current_view(
                    "A saved report case could not be reconciled. Reporting stays paused to avoid creating a duplicate unresolved case.",
                )
            }),
        },
    }
}

fn finish_report_operation(tracker: &OperationCoordinator, id: u64) -> bool {
    let Ok(mut running) = tracker.0.lock() else {
        return false;
    };
    if running
        .report_upload
        .as_ref()
        .is_some_and(|upload| upload.id == id)
    {
        running.report_upload = None;
    }
    take_deferred_exit(&mut running)
}

#[tauri::command]
pub(crate) async fn send_run_report(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    report: ReportUploadInput,
) -> Result<ReportTransactionResult, NativeCommandError> {
    let origin = configured_report_origin()
        .map_err(|message| NativeCommandError::new("report-intake-unavailable", message, false))?;
    let store = ReportStore::for_app(&app)
        .map_err(|message| NativeCommandError::new("report-state-unavailable", message, false))?;
    let client = report_client().map_err(|message| {
        NativeCommandError::new("report-transport-unavailable", message, true)
    })?;
    if let Some(existing) = recover_stored_case(&store, &client, &origin)
        .await
        .map_err(|message| NativeCommandError::new("report-recovery-unavailable", message, false))?
    {
        return Ok(existing);
    }

    let archive = validated_report_snapshot(&report)
        .map_err(|message| NativeCommandError::new("report-archive-invalid", message, false))?;
    let id = NEXT_REPORT_UPLOAD_ID.fetch_add(1, Ordering::Relaxed);
    let (cancel, cancel_receiver) = watch::channel(false);
    {
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
        running.report_upload = Some(ReportUploadProcess {
            id,
            total_bytes: report.bytes,
            cancel,
        });
    }

    let transaction_id = match new_report_transaction_id() {
        Ok(transaction_id) => transaction_id,
        Err(message) => {
            if finish_report_operation(&tracker, id) {
                app.exit(0);
            }
            return Err(NativeCommandError::new(
                "report-state-unavailable",
                message,
                false,
            ));
        }
    };
    let identity = ReportRemoteIdentity::for_upload(&report);
    if let Err(message) = store.publish(&StoredReportState::Pending {
        transaction_id: transaction_id.clone(),
        identity: identity.clone(),
    }) {
        if finish_report_operation(&tracker, id) {
            app.exit(0);
        }
        return Err(NativeCommandError::new(
            "report-state-unavailable",
            message,
            false,
        ));
    }

    emit_report_state(
        &app,
        ReportUploadStateEvent::new("starting", id, 0, report.bytes),
    );
    let upload_app = app.clone();
    let transaction_for_grant = transaction_id.clone();
    let identity_for_grant = identity.clone();
    let outcome = perform_report_upload_with_state(
        client,
        origin,
        archive,
        report.clone(),
        &transaction_id,
        id,
        cancel_receiver,
        |grant, recovery| {
            store.publish(&StoredReportState::Granted {
                transaction_id: transaction_for_grant.clone(),
                identity: identity_for_grant.clone(),
                grant: grant.clone(),
                recovery,
            })
        },
        move |event| emit_report_state(&upload_app, event),
    )
    .await;

    let transaction = match outcome {
        Ok(receipt) => {
            let accepted = StoredReportState::Accepted {
                transaction_id: transaction_id.clone(),
                receipt: receipt.clone(),
            };
            match store.publish(&accepted) {
                Ok(()) => ReportTransactionResult::accepted(&receipt),
                Err(error) => ReportTransactionResult::unknown(
                    Some(receipt.case_id.clone()),
                    format!(
                        "The report service accepted case {}, but Preflight could not durably publish its deletion receipt: {error}. Native recovery data remains saved and will be reconciled before another report is created.",
                        receipt.case_id
                    ),
                ),
            }
        }
        Err(ReportUploadError::Cancelled) => {
            let clear = store.clear();
            let detail = match clear {
                Ok(()) => {
                    "Upload stopped and remote cleanup was confirmed. The local ZIP is unchanged."
                        .to_string()
                }
                Err(error) => format!(
                    "Upload stopped and remote cleanup was confirmed. The local ZIP is unchanged. Private recovery cleanup will be retried later: {error}"
                ),
            };
            ReportTransactionResult::cleanup_confirmed(detail)
        }
        Err(ReportUploadError::Failed(detail)) => {
            let clear = store.clear();
            let detail = match clear {
                Ok(()) => detail,
                Err(error) => {
                    format!("{detail} Private recovery cleanup will be retried later: {error}")
                }
            };
            ReportTransactionResult::cleanup_confirmed(detail)
        }
        Err(ReportUploadError::RemoteOutcomeUnknown {
            case_id,
            recovery: _,
            detail,
        }) => ReportTransactionResult::unknown(case_id, detail),
    };

    let should_exit = finish_report_operation(&tracker, id);
    match &transaction {
        ReportTransactionResult {
            state: ReportLifecycleState::Accepted,
            case_id: Some(case_id),
            receipt: Some(receipt),
            ..
        } => emit_report_state(
            &app,
            ReportUploadStateEvent::new("finished", id, report.bytes, report.bytes)
                .with_case(case_id.clone())
                .with_receipt(receipt.clone()),
        ),
        ReportTransactionResult {
            state: ReportLifecycleState::CleanupConfirmed,
            detail,
            ..
        } => emit_report_state(
            &app,
            ReportUploadStateEvent::new("cleanup-confirmed", id, 0, report.bytes)
                .with_detail(detail.clone().unwrap_or_default()),
        ),
        ReportTransactionResult {
            state: ReportLifecycleState::RemoteOutcomeUnknown,
            case_id,
            detail,
            ..
        } => {
            let mut event =
                ReportUploadStateEvent::new("remote-outcome-unknown", id, 0, report.bytes)
                    .with_detail(detail.clone().unwrap_or_default());
            if let Some(case_id) = case_id {
                event = event.with_case(case_id.clone());
            }
            emit_report_state(&app, event);
        }
        _ => {}
    }
    if should_exit {
        app.exit(0);
    }
    Ok(transaction)
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
pub(crate) async fn delete_run_report(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    case_id: String,
) -> Result<bool, String> {
    {
        let running = tracker
            .0
            .lock()
            .map_err(|_| "The report upload tracker is unavailable.".to_string())?;
        refuse_benchmark_for_report(&running)?;
    }
    let store = ReportStore::for_app(&app)?;
    let deletion = store.accepted_deletion(&case_id)?;
    let origin = configured_report_origin()?;
    let deleted = perform_report_deletion(report_client()?, origin, deletion).await?;
    if deleted {
        store.clear().map_err(|error| {
            format!(
                "The remote report was deleted, but Preflight could not clear its private local receipt: {error}"
            )
        })?;
    }
    Ok(deleted)
}

#[cfg(test)]
mod tests {
    use super::{
        FAIL_NEXT_REPORT_STATE_PUBLISH, REPORT_STATE_PREFIX, REPORT_STATE_SUFFIX, ReportDeletion,
        ReportReceipt, ReportRecoveryKind, ReportRemoteIdentity, ReportStore, StoredReportState,
        SupportReportReceipt, new_report_transaction_id,
    };
    use crate::operations::{OperationState, ReportUploadProcess};
    use crate::take_deferred_exit;
    use std::ffi::OsStr;
    use std::fs;
    use std::io::Write;
    use std::sync::Mutex;
    use std::sync::atomic::Ordering;
    use std::time::{SystemTime, UNIX_EPOCH};
    use tokio::sync::watch;

    static STORE_TEST_LOCK: Mutex<()> = Mutex::new(());

    fn temp_root(label: &str) -> std::path::PathBuf {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "preflight-report-store-{label}-{}-{unique}",
            std::process::id()
        ));
        fs::create_dir(&root).unwrap();
        root
    }

    fn identity() -> ReportRemoteIdentity {
        ReportRemoteIdentity {
            product_version: "0.1.0".to_string(),
            bytes: 3,
            sha256: "a".repeat(64),
        }
    }

    fn receipt(case_id: &str) -> ReportReceipt {
        ReportReceipt {
            protocol_version: 1,
            case_id: case_id.to_string(),
            object_key: format!("accepted/{case_id}.zip"),
            bytes: 3,
            sha256: "a".repeat(64),
            product_version: "0.1.0".to_string(),
            received_at: "2026-09-07T12:00:00Z".to_string(),
            retention_deadline: "2026-09-22T12:00:00Z".to_string(),
            deletion: ReportDeletion {
                method: "DELETE".to_string(),
                url: format!("https://reports.example/v1/cases/{case_id}"),
                token: "header.signature".to_string(),
            },
            signature: "receipt-signature".to_string(),
        }
    }

    fn grant(case_id: &str) -> super::CreateReportCaseResponse {
        super::CreateReportCaseResponse {
            protocol_version: 1,
            case_id: case_id.to_string(),
            upload: super::ReportGrantEndpoint {
                method: "PUT".to_string(),
                url: format!("https://reports.example/v1/cases/{case_id}/archive"),
                content_type: Some("application/zip".to_string()),
                expires_at: Some("2026-09-07T13:00:00Z".to_string()),
                token: "upload.signature".to_string(),
            },
            finalize: super::ReportGrantEndpoint {
                method: "POST".to_string(),
                url: format!("https://reports.example/v1/cases/{case_id}/finalize"),
                content_type: None,
                expires_at: None,
                token: "upload.signature".to_string(),
            },
            deletion: super::ReportGrantEndpoint {
                method: "DELETE".to_string(),
                url: format!("https://reports.example/v1/cases/{case_id}"),
                content_type: None,
                expires_at: None,
                token: "delete.signature".to_string(),
            },
        }
    }

    #[test]
    fn native_receipt_survives_store_reopen_and_keeps_deletion_authority_private() {
        let _guard = STORE_TEST_LOCK.lock().unwrap();
        let root = temp_root("restart");
        let transaction_id = new_report_transaction_id().unwrap();
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        {
            let store = ReportStore::open_at(&root).unwrap();
            store
                .publish(&StoredReportState::Accepted {
                    transaction_id,
                    receipt: receipt(case_id),
                })
                .unwrap();
        }

        let reopened = ReportStore::open_at(&root).unwrap();
        let deletion = reopened.accepted_deletion(case_id).unwrap();
        assert_eq!("header.signature", deletion.token);
        let public = match reopened.load().unwrap().unwrap() {
            StoredReportState::Accepted { receipt, .. } => SupportReportReceipt::from(&receipt),
            state => panic!("unexpected state: {state:?}"),
        };
        let public_json = serde_json::to_string(&public).unwrap();
        assert!(!public_json.contains("header.signature"));
        assert!(!public_json.contains("deletion"));
        assert!(!public_json.contains("objectKey"));

        reopened.clear().unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn accepted_publication_failure_leaves_finalize_recovery_grant_durable() {
        let _guard = STORE_TEST_LOCK.lock().unwrap();
        let root = temp_root("publish-failure");
        let store = ReportStore::open_at(&root).unwrap();
        let transaction_id = new_report_transaction_id().unwrap();
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        let grant = grant(case_id);
        store
            .publish(&StoredReportState::Granted {
                transaction_id: transaction_id.clone(),
                identity: identity(),
                grant: grant.clone(),
                recovery: ReportRecoveryKind::Finalize,
            })
            .unwrap();
        FAIL_NEXT_REPORT_STATE_PUBLISH.store(true, Ordering::SeqCst);

        assert!(
            store
                .publish(&StoredReportState::Accepted {
                    transaction_id,
                    receipt: receipt(case_id),
                })
                .is_err()
        );
        assert!(matches!(
            store.load().unwrap().unwrap(),
            StoredReportState::Granted {
                recovery: ReportRecoveryKind::Finalize,
                grant: saved,
                ..
            } if saved == grant
        ));

        store.clear().unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn torn_higher_priority_state_keeps_finalize_recovery_available() {
        let _guard = STORE_TEST_LOCK.lock().unwrap();
        let root = temp_root("torn-state");
        let store = ReportStore::open_at(&root).unwrap();
        let transaction_id = new_report_transaction_id().unwrap();
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        let grant = grant(case_id);
        store
            .publish(&StoredReportState::Granted {
                transaction_id,
                identity: identity(),
                grant: grant.clone(),
                recovery: ReportRecoveryKind::Finalize,
            })
            .unwrap();

        let torn_name = format!("{REPORT_STATE_PREFIX}accepted-{case_id}{REPORT_STATE_SUFFIX}");
        let mut torn = store
            .directory
            .create_new(OsStr::new(&torn_name), 0o600)
            .unwrap();
        torn.write_all(b"{").unwrap();
        torn.sync_all().unwrap();
        store.directory.sync().unwrap();

        assert!(matches!(
            store.load().unwrap().unwrap(),
            StoredReportState::Granted {
                recovery: ReportRecoveryKind::Finalize,
                grant: saved,
                ..
            } if saved == grant
        ));

        store.clear().unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn deferred_exit_stays_blocked_until_report_publication_and_operation_settle() {
        let _guard = STORE_TEST_LOCK.lock().unwrap();
        let root = temp_root("deferred-exit");
        let store = ReportStore::open_at(&root).unwrap();
        let (cancel, _cancelled) = watch::channel(false);
        let mut operations = OperationState {
            report_upload: Some(ReportUploadProcess {
                id: 91,
                total_bytes: 3,
                cancel,
            }),
            exit_after_cleanup: true,
            ..OperationState::default()
        };

        assert!(!take_deferred_exit(&mut operations));
        store
            .publish(&StoredReportState::Accepted {
                transaction_id: new_report_transaction_id().unwrap(),
                receipt: receipt("3961d5f3-cd4c-4b62-b915-e9cc5a68d5db"),
            })
            .unwrap();
        assert!(!take_deferred_exit(&mut operations));
        operations.report_upload = None;
        assert!(take_deferred_exit(&mut operations));

        store.clear().unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn report_transaction_ids_are_canonical_random_uuids() {
        let id = new_report_transaction_id().unwrap();
        assert_eq!(36, id.len());
        assert_eq!(Some('4'), id.chars().nth(14));
        assert!(matches!(id.chars().nth(19), Some('8' | '9' | 'a' | 'b')));
    }
}
