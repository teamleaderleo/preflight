use crate::reports::{
    CreateReportCaseRequest, CreateReportCaseResponse, ReportDeletion, ReportGrantEndpoint,
    ReportReceipt, ReportRecoveryKind, ReportRemoteIdentity, ReportUploadError, ReportUploadInput,
    ReportUploadStateEvent,
};
use futures_util::StreamExt;
use reqwest::{Client, Response, StatusCode, redirect::Policy};
use serde::de::DeserializeOwned;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{self, Read};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use tokio::sync::watch;
use url::Url;

const REPORT_INTAKE_ORIGIN: Option<&str> = option_env!("PREFLIGHT_REPORT_INTAKE_ORIGIN");
const REPORT_PROTOCOL_VERSION: u32 = 1;
const REPORT_RESPONSE_LIMIT: usize = 64 * 1024;
const REPORT_UPLOAD_LIMIT: u64 = 6 * 1024 * 1024;
const REPORT_TRANSACTION_HEADER: &str = "preflight-report-transaction";

#[derive(Clone)]
pub(crate) struct ValidatedReportSnapshot {
    #[cfg(test)]
    path: PathBuf,
    bytes: Arc<[u8]>,
}

impl ValidatedReportSnapshot {
    fn len(&self) -> u64 {
        self.bytes.len() as u64
    }
}

#[derive(Debug)]
pub(crate) enum ReportRecoveryOutcome {
    Accepted(ReportReceipt),
    CleanupConfirmed,
    RemoteOutcomeUnknown {
        case_id: Option<String>,
        detail: String,
    },
}

#[derive(Debug)]
enum CreateCaseFailure {
    Rejected(String),
    RemoteOutcomeUnknown(String),
}

pub(crate) async fn perform_report_deletion(
    client: Client,
    origin: Url,
    deletion: ReportDeletion,
) -> Result<bool, String> {
    if deletion.method != "DELETE" {
        return Err("The report receipt has an invalid deletion method.".to_string());
    }
    let url = validated_deletion_url(&origin, &deletion.url)?;
    validate_report_token(&deletion.token)?;
    let response = client
        .delete(url)
        .bearer_auth(deletion.token)
        .send()
        .await
        .map_err(|error| {
            format!(
                "Could not request report deletion: {}",
                transport_detail(&error)
            )
        })?;
    if response.status() != StatusCode::NO_CONTENT {
        return Err(response_failure(response, "The report could not be deleted").await);
    }
    Ok(true)
}

pub(crate) async fn perform_report_upload_with_state(
    client: Client,
    origin: Url,
    archive: ValidatedReportSnapshot,
    report: ReportUploadInput,
    transaction_id: &str,
    id: u64,
    mut cancel: watch::Receiver<bool>,
    persist_grant: impl Fn(&CreateReportCaseResponse, ReportRecoveryKind) -> Result<(), String>,
    emit: impl Fn(ReportUploadStateEvent) + Clone + Send + Sync + 'static,
) -> Result<ReportReceipt, ReportUploadError> {
    if archive.len() != report.bytes {
        return Err(ReportUploadError::Failed(
            "The immutable diagnostics snapshot no longer matches its disclosed byte count."
                .to_string(),
        ));
    }
    if *cancel.borrow() {
        return Err(ReportUploadError::Cancelled);
    }
    let identity = ReportRemoteIdentity {
        product_version: env!("CARGO_PKG_VERSION").to_string(),
        bytes: report.bytes,
        sha256: report.sha256.clone(),
    };
    let create = request_report_case(&client, &origin, transaction_id, &identity);
    tokio::pin!(create);
    let grant = tokio::select! {
        changed = cancel.changed() => {
            let _ = changed;
            return Err(ReportUploadError::RemoteOutcomeUnknown {
                case_id: None,
                recovery: ReportRecoveryKind::CreateThenDelete,
                detail: "Upload cancellation raced with case creation. Preflight saved the transaction identity and will reconcile the remote case before another report is created. The local ZIP is unchanged.".to_string(),
            });
        }
        response = create.as_mut() => match response {
            Ok(grant) => grant,
            Err(CreateCaseFailure::Rejected(detail)) => return Err(ReportUploadError::Failed(
                format!("{detail} No retained report case was accepted; the local ZIP is unchanged.")
            )),
            Err(CreateCaseFailure::RemoteOutcomeUnknown(detail)) => {
                return Err(ReportUploadError::RemoteOutcomeUnknown {
                    case_id: None,
                    recovery: ReportRecoveryKind::CreateThenDelete,
                    detail: format!(
                        "{detail} Preflight saved the transaction identity and will reconcile this case before another report is created. The local ZIP is unchanged."
                    ),
                });
            }
        },
    };

    if let Err(error) = persist_grant(&grant, ReportRecoveryKind::Delete) {
        return Err(match delete_granted_case(&client, &origin, &grant).await {
            Ok(()) => ReportUploadError::Failed(format!(
                "Preflight could not durably save deletion authority before upload: {error}. The server case was deleted and the local ZIP is unchanged."
            )),
            Err(cleanup) => ReportUploadError::RemoteOutcomeUnknown {
                case_id: Some(grant.case_id.clone()),
                recovery: ReportRecoveryKind::CreateThenDelete,
                detail: format!(
                    "Preflight could not durably save the case grant: {error}. Deletion of case {} could not be confirmed: {cleanup}. The saved transaction identity can recover and delete this case later; the local ZIP is unchanged.",
                    grant.case_id
                ),
            },
        });
    }

    emit(
        ReportUploadStateEvent::new("uploading", id, 0, report.bytes)
            .with_case(grant.case_id.clone()),
    );
    if *cancel.borrow() {
        return Err(cancelled_cleanup(&client, &origin, &grant).await);
    }

    let stream_cancel = cancel.clone();
    let stream_emit = emit.clone();
    let case_id = grant.case_id.clone();
    let total = report.bytes;
    let snapshot = Arc::clone(&archive.bytes);
    let stream = async_stream::stream! {
        let mut uploaded = 0_u64;
        for chunk in snapshot.chunks(64 * 1024) {
            if *stream_cancel.borrow() {
                yield Err::<Vec<u8>, io::Error>(io::Error::new(
                    io::ErrorKind::Interrupted,
                    "report upload cancelled",
                ));
                return;
            }
            uploaded = uploaded.saturating_add(chunk.len() as u64);
            stream_emit(
                ReportUploadStateEvent::new("uploading", id, uploaded, total)
                    .with_case(case_id.clone()),
            );
            yield Ok(chunk.to_vec());
        }
    };
    let upload_url = match validated_case_url(&origin, &grant.upload.url, &grant.case_id, "archive") {
        Ok(url) => url,
        Err(detail) => {
            return Err(cleanup_granted_failure(&client, &origin, &grant, detail).await);
        }
    };
    let upload_request = client
        .put(upload_url)
        .bearer_auth(&grant.upload.token)
        .header(reqwest::header::CONTENT_TYPE, "application/zip")
        .header(reqwest::header::CONTENT_LENGTH, archive.len())
        .body(reqwest::Body::wrap_stream(stream))
        .send();
    tokio::pin!(upload_request);
    let upload_response = tokio::select! {
        changed = cancel.changed() => {
            let _ = changed;
            let _ = upload_request.as_mut().await;
            return Err(cancelled_cleanup(&client, &origin, &grant).await);
        }
        response = upload_request.as_mut() => match response {
            Ok(response) => response,
            Err(_) if *cancel.borrow() => {
                return Err(cancelled_cleanup(&client, &origin, &grant).await);
            }
            Err(error) => {
                return Err(cleanup_granted_failure(
                    &client,
                    &origin,
                    &grant,
                    format!("Could not upload the run report: {}", transport_detail(&error)),
                )
                .await);
            }
        },
    };
    let upload: Value = match response_json(upload_response, "The run-report archive was rejected").await {
        Ok(upload) => upload,
        Err(detail) => {
            return Err(cleanup_granted_failure(&client, &origin, &grant, detail).await);
        }
    };
    if upload.pointer("/status").and_then(Value::as_str) != Some("uploaded")
        || upload.pointer("/caseId").and_then(Value::as_str) != Some(&grant.case_id)
        || upload.pointer("/bytes").and_then(Value::as_u64) != Some(archive.len())
        || upload.pointer("/sha256").and_then(Value::as_str) != Some(&report.sha256)
    {
        return Err(cleanup_granted_failure(
            &client,
            &origin,
            &grant,
            "The intake returned an inconsistent upload receipt.".to_string(),
        )
        .await);
    }
    if *cancel.borrow() {
        return Err(cancelled_cleanup(&client, &origin, &grant).await);
    }

    if let Err(error) = persist_grant(&grant, ReportRecoveryKind::Finalize) {
        return Err(cleanup_granted_failure(
            &client,
            &origin,
            &grant,
            format!(
                "Preflight could not durably record finalization recovery before asking the service to accept the case: {error}"
            ),
        )
        .await);
    }
    emit(
        ReportUploadStateEvent::new("finalizing", id, archive.len(), archive.len())
            .with_case(grant.case_id.clone()),
    );
    finalize_granted_case(&client, &origin, &grant, &identity)
        .await
        .map_err(|detail| ReportUploadError::RemoteOutcomeUnknown {
            case_id: Some(grant.case_id.clone()),
            recovery: ReportRecoveryKind::Finalize,
            detail: format!(
                "{detail} Preflight saved the finalize and deletion grants and will reconcile case {} before another report is created. The local ZIP is unchanged.",
                grant.case_id
            ),
        })
}

#[cfg(test)]
pub(crate) async fn perform_report_upload(
    client: Client,
    origin: Url,
    archive: PathBuf,
    report: ReportUploadInput,
    id: u64,
    cancel: watch::Receiver<bool>,
    emit: impl Fn(ReportUploadStateEvent) + Clone + Send + Sync + 'static,
) -> Result<ReportReceipt, ReportUploadError> {
    let snapshot = validated_report_snapshot(&report).map_err(ReportUploadError::Failed)?;
    if snapshot.path != archive.canonicalize().map_err(|error| {
        ReportUploadError::Failed(format!("Could not resolve the diagnostics ZIP: {error}"))
    })? {
        return Err(ReportUploadError::Failed(
            "The diagnostics ZIP path changed before upload.".to_string(),
        ));
    }
    let transaction_id = format!("00000000-0000-4000-8000-{id:012x}");
    perform_report_upload_with_state(
        client,
        origin,
        snapshot,
        report,
        &transaction_id,
        id,
        cancel,
        |_grant, _recovery| Ok(()),
        emit,
    )
    .await
}

pub(crate) async fn recover_pending_report(
    client: &Client,
    origin: &Url,
    transaction_id: &str,
    identity: &ReportRemoteIdentity,
) -> ReportRecoveryOutcome {
    match request_report_case(client, origin, transaction_id, identity).await {
        Err(CreateCaseFailure::Rejected(_)) => ReportRecoveryOutcome::CleanupConfirmed,
        Err(CreateCaseFailure::RemoteOutcomeUnknown(detail)) => {
            ReportRecoveryOutcome::RemoteOutcomeUnknown {
                case_id: None,
                detail: format!(
                    "Preflight still cannot reconcile the earlier report transaction: {detail}"
                ),
            }
        }
        Ok(grant) => match delete_granted_case(client, origin, &grant).await {
            Ok(()) => ReportRecoveryOutcome::CleanupConfirmed,
            Err(detail) => ReportRecoveryOutcome::RemoteOutcomeUnknown {
                case_id: Some(grant.case_id.clone()),
                detail: format!(
                    "Preflight recovered case {} but could not confirm its deletion: {detail}",
                    grant.case_id
                ),
            },
        },
    }
}

pub(crate) async fn recover_granted_report(
    client: &Client,
    origin: &Url,
    grant: &CreateReportCaseResponse,
    identity: &ReportRemoteIdentity,
    recovery: ReportRecoveryKind,
) -> ReportRecoveryOutcome {
    if let Err(detail) = validate_case_grant_identity(origin, grant, identity) {
        return ReportRecoveryOutcome::RemoteOutcomeUnknown {
            case_id: Some(grant.case_id.clone()),
            detail: format!("Saved report recovery data failed validation: {detail}"),
        };
    }
    match recovery {
        ReportRecoveryKind::CreateThenDelete | ReportRecoveryKind::Delete => {
            match delete_granted_case(client, origin, grant).await {
                Ok(()) => ReportRecoveryOutcome::CleanupConfirmed,
                Err(detail) => ReportRecoveryOutcome::RemoteOutcomeUnknown {
                    case_id: Some(grant.case_id.clone()),
                    detail: format!(
                        "Deletion of unresolved case {} still cannot be confirmed: {detail}",
                        grant.case_id
                    ),
                },
            }
        }
        ReportRecoveryKind::Finalize => {
            match finalize_granted_case(client, origin, grant, identity).await {
                Ok(receipt) => ReportRecoveryOutcome::Accepted(receipt),
                Err(finalize) => match delete_granted_case(client, origin, grant).await {
                    Ok(()) => ReportRecoveryOutcome::CleanupConfirmed,
                    Err(cleanup) => ReportRecoveryOutcome::RemoteOutcomeUnknown {
                        case_id: Some(grant.case_id.clone()),
                        detail: format!(
                            "Case {} could not be finalized ({finalize}) and deletion could not be confirmed ({cleanup}).",
                            grant.case_id
                        ),
                    },
                },
            }
        }
    }
}

async fn request_report_case(
    client: &Client,
    origin: &Url,
    transaction_id: &str,
    identity: &ReportRemoteIdentity,
) -> Result<CreateReportCaseResponse, CreateCaseFailure> {
    if !is_case_id(transaction_id) {
        return Err(CreateCaseFailure::Rejected(
            "The saved report transaction identity is invalid.".to_string(),
        ));
    }
    let create_url = origin.join("v1/cases").map_err(|error| {
        CreateCaseFailure::Rejected(format!("The report intake URL is invalid: {error}"))
    })?;
    let response = client
        .post(create_url)
        .header(REPORT_TRANSACTION_HEADER, transaction_id)
        .json(&CreateReportCaseRequest {
            protocol_version: REPORT_PROTOCOL_VERSION,
            product_version: &identity.product_version,
            bytes: identity.bytes,
            sha256: &identity.sha256,
        })
        .send()
        .await
        .map_err(|error| {
            CreateCaseFailure::RemoteOutcomeUnknown(format!(
                "Could not confirm case creation: {}",
                transport_detail(&error)
            ))
        })?;
    let status = response.status();
    let bytes = bounded_response_body(response).await.map_err(|detail| {
        CreateCaseFailure::RemoteOutcomeUnknown(format!(
            "Could not read the case-creation response: {detail}"
        ))
    })?;
    if !status.is_success() {
        return Err(CreateCaseFailure::Rejected(response_failure_bytes(
            status,
            &bytes,
            "The report case was rejected",
        )));
    }
    let grant: CreateReportCaseResponse = serde_json::from_slice(&bytes).map_err(|error| {
        CreateCaseFailure::RemoteOutcomeUnknown(format!(
            "The case-creation response was unreadable: {error}"
        ))
    })?;
    validate_case_grant_identity(origin, &grant, identity)
        .map_err(CreateCaseFailure::RemoteOutcomeUnknown)?;
    Ok(grant)
}

async fn finalize_granted_case(
    client: &Client,
    origin: &Url,
    grant: &CreateReportCaseResponse,
    identity: &ReportRemoteIdentity,
) -> Result<ReportReceipt, String> {
    let finalize_url = validated_case_url(origin, &grant.finalize.url, &grant.case_id, "finalize")?;
    let response = client
        .post(finalize_url)
        .bearer_auth(&grant.finalize.token)
        .send()
        .await
        .map_err(|error| {
            format!(
                "Could not confirm report finalization: {}",
                transport_detail(&error)
            )
        })?;
    let receipt: ReportReceipt = response_json(response, "The run report could not be finalized").await?;
    validate_report_receipt_identity(origin, &receipt, &grant.case_id, identity)?;
    Ok(receipt)
}

async fn cancelled_cleanup(
    client: &Client,
    origin: &Url,
    grant: &CreateReportCaseResponse,
) -> ReportUploadError {
    match delete_granted_case(client, origin, grant).await {
        Ok(()) => ReportUploadError::Cancelled,
        Err(cleanup) => ReportUploadError::RemoteOutcomeUnknown {
            case_id: Some(grant.case_id.clone()),
            recovery: ReportRecoveryKind::Delete,
            detail: format!(
                "Upload cancellation could not confirm deletion of case {}: {cleanup}. Native deletion authority remains saved for recovery; the local ZIP is unchanged.",
                grant.case_id
            ),
        },
    }
}

async fn cleanup_granted_failure(
    client: &Client,
    origin: &Url,
    grant: &CreateReportCaseResponse,
    detail: String,
) -> ReportUploadError {
    match delete_granted_case(client, origin, grant).await {
        Ok(()) => ReportUploadError::Failed(format!(
            "{detail} Remote cleanup was confirmed; the local ZIP is unchanged."
        )),
        Err(cleanup) => ReportUploadError::RemoteOutcomeUnknown {
            case_id: Some(grant.case_id.clone()),
            recovery: ReportRecoveryKind::Delete,
            detail: format!(
                "{detail} Deletion of case {} could not be confirmed: {cleanup}. Native deletion authority remains saved for recovery; the local ZIP is unchanged.",
                grant.case_id
            ),
        },
    }
}

async fn delete_granted_case(
    client: &Client,
    origin: &Url,
    grant: &CreateReportCaseResponse,
) -> Result<(), String> {
    let url = validated_case_url(origin, &grant.deletion.url, &grant.case_id, "")?;
    let response = client
        .delete(url)
        .bearer_auth(&grant.deletion.token)
        .send()
        .await
        .map_err(|error| {
            format!(
                "could not contact the deletion endpoint: {}",
                transport_detail(&error)
            )
        })?;
    if response.status() != StatusCode::NO_CONTENT {
        return Err(response_failure(response, "the report cleanup was rejected").await);
    }
    Ok(())
}

pub(crate) fn configured_report_origin() -> Result<Url, String> {
    validate_report_origin(REPORT_INTAKE_ORIGIN)
}

pub(crate) fn validate_report_origin(configured: Option<&str>) -> Result<Url, String> {
    let configured = configured.ok_or_else(|| {
        "Run-report sending isn't configured in this build. You can still save the disclosed ZIP."
            .to_string()
    })?;
    let origin = Url::parse(configured)
        .map_err(|_| "The configured run-report origin is invalid.".to_string())?;
    if origin.scheme() != "https"
        || origin.host_str().is_none()
        || !origin.username().is_empty()
        || origin.password().is_some()
        || origin.path() != "/"
        || origin.query().is_some()
        || origin.fragment().is_some()
        || origin
            .host_str()
            .is_some_and(|host| host.ends_with(".invalid"))
    {
        return Err(
            "The configured run-report origin must be a production HTTPS origin.".to_string(),
        );
    }
    Ok(origin)
}

pub(crate) fn transport_detail(error: &reqwest::Error) -> String {
    let mut detail = error.to_string();
    let mut cause: Option<&(dyn std::error::Error + 'static)> = std::error::Error::source(error);
    while let Some(source) = cause {
        detail.push_str(": ");
        detail.push_str(&source.to_string());
        cause = source.source();
    }
    detail
}

pub(crate) fn report_client() -> Result<Client, String> {
    Client::builder()
        .redirect(Policy::none())
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(180))
        .user_agent(format!("Preflight/{}", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|error| format!("Could not configure the report client: {error}"))
}

pub(crate) fn validated_report_snapshot(
    report: &ReportUploadInput,
) -> Result<ValidatedReportSnapshot, String> {
    if report.bytes == 0 || report.bytes > REPORT_UPLOAD_LIMIT {
        return Err("The diagnostics ZIP is outside the 6 MiB upload limit.".to_string());
    }
    if !is_lower_sha256(&report.sha256) {
        return Err("The diagnostics receipt has an invalid SHA-256.".to_string());
    }
    let requested = PathBuf::from(&report.output);
    if !requested.is_absolute() {
        return Err("The diagnostics ZIP must have an absolute path.".to_string());
    }
    let symlink = requested
        .symlink_metadata()
        .map_err(|error| format!("Could not inspect the diagnostics ZIP: {error}"))?;
    if symlink.file_type().is_symlink() || !symlink.is_file() {
        return Err("The diagnostics ZIP must be a regular, non-symbolic-link file.".to_string());
    }
    let archive = requested
        .canonicalize()
        .map_err(|error| format!("Could not resolve the diagnostics ZIP: {error}"))?;
    if !archive
        .extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| extension.eq_ignore_ascii_case("zip"))
    {
        return Err("The diagnostics filename must end in .zip.".to_string());
    }
    let before = archive
        .metadata()
        .map_err(|error| format!("Could not inspect the diagnostics ZIP: {error}"))?;
    let before_modified = before.modified().ok();
    let mut file = fs::File::open(&archive)
        .map_err(|error| format!("Could not open the diagnostics ZIP: {error}"))?;
    let bytes = read_bounded_snapshot(&mut file, REPORT_UPLOAD_LIMIT)
        .map_err(|error| format!("Could not verify the diagnostics ZIP: {error}"))?;
    if bytes.len() as u64 != report.bytes {
        return Err("The diagnostics ZIP byte count changed after its disclosure.".to_string());
    }
    let after = archive
        .metadata()
        .map_err(|error| format!("Could not recheck the diagnostics ZIP: {error}"))?;
    if after.len() != before.len() || after.modified().ok() != before_modified {
        return Err("The diagnostics ZIP changed while it was being snapshotted.".to_string());
    }
    let digest = Sha256::digest(&bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    if digest != report.sha256 {
        return Err("The diagnostics ZIP SHA-256 changed after its disclosure.".to_string());
    }
    Ok(ValidatedReportSnapshot {
        #[cfg(test)]
        path: archive,
        bytes: Arc::from(bytes),
    })
}

fn read_bounded_snapshot(reader: &mut impl Read, max_bytes: u64) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::with_capacity(max_bytes.min(64 * 1024) as usize);
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = reader.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        if (bytes.len() as u64).saturating_add(read as u64) > max_bytes {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "diagnostics ZIP exceeded the 6 MiB upload limit while being read",
            ));
        }
        bytes.extend_from_slice(&buffer[..read]);
    }
    Ok(bytes)
}

#[cfg(test)]
pub(crate) fn validated_report_archive(report: &ReportUploadInput) -> Result<PathBuf, String> {
    validated_report_snapshot(report).map(|snapshot| snapshot.path)
}

fn validate_case_grant_identity(
    origin: &Url,
    grant: &CreateReportCaseResponse,
    report: &ReportRemoteIdentity,
) -> Result<(), String> {
    if grant.protocol_version != REPORT_PROTOCOL_VERSION || !is_case_id(&grant.case_id) {
        return Err("The intake returned an invalid case identity.".to_string());
    }
    validate_grant_endpoint(origin, &grant.upload, &grant.case_id, "PUT", "archive")?;
    if grant.upload.content_type.as_deref() != Some("application/zip")
        || grant.upload.expires_at.as_deref().is_none_or(str::is_empty)
    {
        return Err("The intake returned an invalid upload grant.".to_string());
    }
    validate_grant_endpoint(origin, &grant.finalize, &grant.case_id, "POST", "finalize")?;
    validate_grant_endpoint(origin, &grant.deletion, &grant.case_id, "DELETE", "")?;
    if report.bytes == 0
        || report.product_version.is_empty()
        || report.product_version.len() > 128
        || !is_lower_sha256(&report.sha256)
    {
        return Err("The disclosed report identity is invalid.".to_string());
    }
    Ok(())
}

fn validate_grant_endpoint(
    origin: &Url,
    endpoint: &ReportGrantEndpoint,
    case_id: &str,
    method: &str,
    suffix: &str,
) -> Result<(), String> {
    if endpoint.method != method {
        return Err("The intake returned an unexpected grant method.".to_string());
    }
    validate_report_token(&endpoint.token)?;
    validated_case_url(origin, &endpoint.url, case_id, suffix).map(|_| ())
}

pub(crate) fn validated_case_url(
    origin: &Url,
    value: &str,
    case_id: &str,
    suffix: &str,
) -> Result<Url, String> {
    if !is_case_id(case_id) {
        return Err("The intake returned an invalid case ID.".to_string());
    }
    let relative = if suffix.is_empty() {
        format!("v1/cases/{case_id}")
    } else {
        format!("v1/cases/{case_id}/{suffix}")
    };
    let expected = origin
        .join(&relative)
        .map_err(|_| "The intake returned an invalid case URL.".to_string())?;
    let actual =
        Url::parse(value).map_err(|_| "The intake returned an invalid case URL.".to_string())?;
    if actual != expected {
        return Err("The intake returned a case URL outside its configured origin.".to_string());
    }
    Ok(actual)
}

fn validated_deletion_url(origin: &Url, value: &str) -> Result<Url, String> {
    let actual = Url::parse(value)
        .map_err(|_| "The report receipt has an invalid deletion URL.".to_string())?;
    if actual.origin() != origin.origin() || actual.query().is_some() || actual.fragment().is_some()
    {
        return Err("The report deletion URL is outside the configured origin.".to_string());
    }
    let Some(case_id) = actual.path().strip_prefix("/v1/cases/") else {
        return Err("The report receipt has an invalid deletion URL.".to_string());
    };
    if !is_case_id(case_id) || actual.path() != format!("/v1/cases/{case_id}") {
        return Err("The report receipt has an invalid deletion URL.".to_string());
    }
    Ok(actual)
}

fn validate_report_receipt_identity(
    origin: &Url,
    receipt: &ReportReceipt,
    case_id: &str,
    report: &ReportRemoteIdentity,
) -> Result<(), String> {
    let Some(received_date) = receipt.received_at.get(..10) else {
        return Err("The intake returned an inconsistent signed receipt.".to_string());
    };
    if !received_date.bytes().enumerate().all(|(index, byte)| {
        if matches!(index, 4 | 7) {
            byte == b'-'
        } else {
            byte.is_ascii_digit()
        }
    }) || receipt.protocol_version != REPORT_PROTOCOL_VERSION
        || receipt.case_id != case_id
        || receipt.object_key != format!("accepted/{case_id}.zip")
        || receipt.bytes != report.bytes
        || receipt.sha256 != report.sha256
        || receipt.product_version != report.product_version
        || receipt.received_at.len() < 20
        || receipt.received_at.len() > 64
        || receipt.retention_deadline.len() < 20
        || receipt.retention_deadline.len() > 64
        || receipt.signature.is_empty()
        || receipt.signature.len() > 256
        || !receipt
            .signature
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
    {
        return Err("The intake returned an inconsistent signed receipt.".to_string());
    }
    if receipt.deletion.method != "DELETE" {
        return Err("The intake returned an invalid deletion receipt.".to_string());
    }
    validate_report_token(&receipt.deletion.token)?;
    let expected = validated_case_url(origin, &receipt.deletion.url, case_id, "")?;
    validated_deletion_url(origin, expected.as_str())?;
    Ok(())
}

pub(crate) fn validate_report_receipt(
    origin: &Url,
    receipt: &ReportReceipt,
    case_id: &str,
    report: &ReportUploadInput,
) -> Result<(), String> {
    validate_report_receipt_identity(
        origin,
        receipt,
        case_id,
        &ReportRemoteIdentity {
            product_version: env!("CARGO_PKG_VERSION").to_string(),
            bytes: report.bytes,
            sha256: report.sha256.clone(),
        },
    )
}

fn validate_report_token(token: &str) -> Result<(), String> {
    if token.is_empty()
        || token.len() > 8192
        || token.matches('.').count() != 1
        || !token.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-' || byte == b'.'
        })
    {
        return Err("The intake returned an invalid bearer grant.".to_string());
    }
    Ok(())
}

fn is_case_id(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(index, byte)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()
            }
        })
}

fn is_lower_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

async fn response_json<T: DeserializeOwned>(
    response: Response,
    context: &str,
) -> Result<T, String> {
    let status = response.status();
    let bytes = bounded_response_body(response).await?;
    if !status.is_success() {
        return Err(response_failure_bytes(status, &bytes, context));
    }
    serde_json::from_slice(&bytes)
        .map_err(|error| format!("{context}: unreadable response: {error}"))
}

fn response_failure_bytes(status: StatusCode, bytes: &[u8], context: &str) -> String {
    serde_json::from_slice::<Value>(bytes)
        .ok()
        .and_then(|value| {
            value
                .pointer("/error")
                .and_then(Value::as_str)
                .map(str::to_string)
        })
        .map(|detail| format!("{context}: {detail}"))
        .unwrap_or_else(|| format!("{context}: HTTP {status}"))
}

async fn response_failure(response: Response, context: &str) -> String {
    let status = response.status();
    match bounded_response_body(response).await {
        Ok(bytes) => response_failure_bytes(status, &bytes, context),
        Err(error) => format!("{context}: HTTP {status}; {error}"),
    }
}

async fn bounded_response_body(response: Response) -> Result<Vec<u8>, String> {
    if response
        .content_length()
        .is_some_and(|length| length > REPORT_RESPONSE_LIMIT as u64)
    {
        return Err("The report intake response is too large.".to_string());
    }
    let mut stream = response.bytes_stream();
    let mut body = Vec::new();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| {
            format!(
                "Could not read the report intake response: {}",
                transport_detail(&error)
            )
        })?;
        if body.len().saturating_add(chunk.len()) > REPORT_RESPONSE_LIMIT {
            return Err("The report intake response is too large.".to_string());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

pub(crate) fn emit_report_state(app: &AppHandle, event: ReportUploadStateEvent) {
    let _ = app.emit("report-upload-state", event);
}

#[cfg(test)]
mod tests {
    use super::{
        REPORT_UPLOAD_LIMIT, cancelled_cleanup, read_bounded_snapshot, recover_granted_report,
        validated_report_snapshot,
    };
    use crate::reports::{
        CreateReportCaseResponse, ReportGrantEndpoint, ReportRecoveryKind::Delete,
        ReportRecoveryKind::Finalize, ReportRemoteIdentity, ReportUploadError, ReportUploadInput,
    };
    use reqwest::Client;
    use sha2::{Digest, Sha256};
    use std::fs;
    use std::io::{self, Read};
    use std::time::{Duration, SystemTime, UNIX_EPOCH};
    use url::Url;

    struct LyingReader {
        remaining: u64,
    }

    impl Read for LyingReader {
        fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
            if self.remaining == 0 {
                return Ok(0);
            }
            let read = self.remaining.min(buffer.len() as u64) as usize;
            buffer[..read].fill(0x5a);
            self.remaining -= read as u64;
            Ok(read)
        }
    }

    #[test]
    fn bounded_snapshot_rejects_actual_bytes_past_six_mib() {
        let mut reader = LyingReader {
            remaining: REPORT_UPLOAD_LIMIT + 1,
        };
        let error = read_bounded_snapshot(&mut reader, REPORT_UPLOAD_LIMIT).unwrap_err();
        assert_eq!(io::ErrorKind::InvalidData, error.kind());
        assert!(error.to_string().contains("exceeded the 6 MiB"));
    }

    #[test]
    fn path_changes_after_snapshot_cannot_change_upload_bytes() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let archive = std::env::temp_dir().join(format!(
            "preflight-report-snapshot-test-{}-{unique}.zip",
            std::process::id()
        ));
        let disclosed = b"exact disclosed report bytes".to_vec();
        fs::write(&archive, &disclosed).unwrap();
        let report = ReportUploadInput {
            output: archive.to_string_lossy().into_owned(),
            bytes: disclosed.len() as u64,
            sha256: Sha256::digest(&disclosed)
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect(),
        };
        let snapshot = validated_report_snapshot(&report).unwrap();

        fs::write(&archive, b"different bytes after disclosure").unwrap();

        assert_eq!(&disclosed[..], &snapshot.bytes[..]);
        assert_eq!(report.bytes, snapshot.len());
        let digest = Sha256::digest(&snapshot.bytes[..])
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        assert_eq!(report.sha256, digest);
        fs::remove_file(archive).unwrap();
    }
    fn unreachable_grant(origin: &Url) -> CreateReportCaseResponse {
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        CreateReportCaseResponse {
            protocol_version: 1,
            case_id: case_id.to_string(),
            upload: ReportGrantEndpoint {
                method: "PUT".to_string(),
                url: origin.join(&format!("v1/cases/{case_id}/archive")).unwrap().to_string(),
                content_type: Some("application/zip".to_string()),
                expires_at: Some("2026-09-07T13:00:00Z".to_string()),
                token: "upload.signature".to_string(),
            },
            finalize: ReportGrantEndpoint {
                method: "POST".to_string(),
                url: origin.join(&format!("v1/cases/{case_id}/finalize")).unwrap().to_string(),
                content_type: None,
                expires_at: None,
                token: "upload.signature".to_string(),
            },
            deletion: ReportGrantEndpoint {
                method: "DELETE".to_string(),
                url: origin.join(&format!("v1/cases/{case_id}")).unwrap().to_string(),
                content_type: None,
                expires_at: None,
                token: "delete.signature".to_string(),
            },
        }
    }

    fn short_client() -> Client {
        Client::builder()
            .connect_timeout(Duration::from_millis(100))
            .timeout(Duration::from_millis(200))
            .build()
            .unwrap()
    }

    #[tokio::test]
    async fn cancellation_without_confirmed_cleanup_is_remote_outcome_unknown() {
        let origin = Url::parse("http://127.0.0.1:1/").unwrap();
        let grant = unreachable_grant(&origin);

        let outcome = cancelled_cleanup(&short_client(), &origin, &grant).await;

        assert!(matches!(
            outcome,
            ReportUploadError::RemoteOutcomeUnknown {
                case_id: Some(ref case_id),
                recovery: Delete,
                ..
            } if case_id == &grant.case_id
        ));
    }

    #[tokio::test]
    async fn interrupted_finalization_and_failed_cleanup_remain_recoverable_unknown() {
        let origin = Url::parse("http://127.0.0.1:1/").unwrap();
        let grant = unreachable_grant(&origin);
        let identity = ReportRemoteIdentity {
            product_version: env!("CARGO_PKG_VERSION").to_string(),
            bytes: 3,
            sha256: "a".repeat(64),
        };

        let outcome = recover_granted_report(
            &short_client(),
            &origin,
            &grant,
            &identity,
            Finalize,
        )
        .await;

        assert!(matches!(
            outcome,
            super::ReportRecoveryOutcome::RemoteOutcomeUnknown {
                case_id: Some(ref case_id),
                ..
            } if case_id == &grant.case_id
        ));
    }

}
