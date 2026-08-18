use super::report_authority::NativeReportAuthorityLifecycle;
use super::{
    CreateReportCaseRequest, CreateReportCaseResponse, ReportGrantEndpoint, ReportReceipt,
    ReportUploadError, ReportUploadInput, ReportUploadStateEvent,
};
use crate::report_transport::{transport_detail, validate_report_receipt, validated_case_url};
use futures_util::StreamExt;
use reqwest::{Client, Response, StatusCode};
use serde::de::DeserializeOwned;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use tokio::sync::watch;
use url::Url;

const REPORT_PROTOCOL_VERSION: u32 = 1;
const REPORT_RESPONSE_LIMIT: usize = 64 * 1024;
const REPORT_UPLOAD_LIMIT: u64 = 6 * 1024 * 1024;

pub(crate) struct ReportUploadAttempt {
    pub(crate) client: Client,
    pub(crate) origin: Url,
    pub(crate) archive: Vec<u8>,
    pub(crate) report: ReportUploadInput,
    pub(crate) id: u64,
    pub(crate) cancel: watch::Receiver<bool>,
}

/// Reads the exact disclosed ZIP once, verifies that opened file handle, and returns immutable
/// bytes for the upload. The network path never reopens the filesystem path after this boundary.
pub(crate) fn validated_report_archive_bytes(
    report: &ReportUploadInput,
) -> Result<Vec<u8>, String> {
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

    let mut file = fs::File::open(&archive)
        .map_err(|error| format!("Could not open the diagnostics ZIP: {error}"))?;
    let before = file
        .metadata()
        .map_err(|error| format!("Could not inspect the diagnostics ZIP: {error}"))?;
    if !before.is_file() || before.len() != report.bytes || before.len() > REPORT_UPLOAD_LIMIT {
        return Err("The diagnostics ZIP size changed after its disclosure.".to_string());
    }
    let before_modified = before.modified().ok();
    let mut bytes = Vec::with_capacity(before.len() as usize);
    file.read_to_end(&mut bytes)
        .map_err(|error| format!("Could not verify the diagnostics ZIP: {error}"))?;
    let after = file
        .metadata()
        .map_err(|error| format!("Could not recheck the diagnostics ZIP: {error}"))?;
    if after.len() != before.len() || after.modified().ok() != before_modified {
        return Err("The diagnostics ZIP changed while it was being verified.".to_string());
    }
    if bytes.len() as u64 != report.bytes {
        return Err("The diagnostics ZIP size changed while it was being read.".to_string());
    }
    let digest = Sha256::digest(&bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    if digest != report.sha256 {
        return Err("The diagnostics ZIP SHA-256 changed after its disclosure.".to_string());
    }
    Ok(bytes)
}

pub(crate) async fn perform_report_upload_with_authority(
    upload: ReportUploadAttempt,
    lifecycle: &NativeReportAuthorityLifecycle,
    emit: impl Fn(ReportUploadStateEvent) + Clone + Send + Sync + 'static,
) -> Result<ReportReceipt, ReportUploadError> {
    let ReportUploadAttempt {
        client,
        origin,
        archive,
        report,
        id,
        cancel,
    } = upload;
    let mut cancel = cancel;
    if archive.len() as u64 != report.bytes {
        return Err(ReportUploadError::Failed(
            "The verified diagnostics bytes no longer match their disclosed size.".to_string(),
        ));
    }
    if *cancel.borrow() {
        return Err(ReportUploadError::Cancelled);
    }
    let create_url = origin.join("v1/cases").map_err(|error| {
        ReportUploadError::Failed(format!("The report intake URL is invalid: {error}"))
    })?;
    let create = client
        .post(create_url)
        .json(&CreateReportCaseRequest {
            protocol_version: REPORT_PROTOCOL_VERSION,
            product_version: env!("CARGO_PKG_VERSION"),
            bytes: report.bytes,
            sha256: &report.sha256,
        })
        .send();
    let create_response = tokio::select! {
        changed = cancel.changed() => {
            let _ = changed;
            return Err(ReportUploadError::Cancelled);
        }
        response = create => response.map_err(|error| ReportUploadError::Failed(
            format!("Could not create a run-report case: {}", transport_detail(&error))
        ))?,
    };
    let grant: CreateReportCaseResponse =
        response_json(create_response, "The report case was rejected")
            .await
            .map_err(ReportUploadError::Failed)?;
    validate_case_grant(&origin, &grant, &report).map_err(ReportUploadError::Failed)?;

    if let Err(detail) = lifecycle.granted(&grant, &report) {
        return Err(cleanup_unpersisted_grant(
            &client,
            &origin,
            &grant,
            format!(
                "Preflight could not save deletion authority for case {}: {detail}",
                grant.case_id
            ),
        )
        .await);
    }

    emit(
        ReportUploadStateEvent::new("uploading", id, 0, report.bytes)
            .with_case(grant.case_id.clone()),
    );
    if *cancel.borrow() {
        return cancel_granted_case(&client, &origin, &grant, lifecycle).await;
    }

    let stream_cancel = cancel.clone();
    let stream_emit = emit.clone();
    let case_id = grant.case_id.clone();
    let total = report.bytes;
    let stream = async_stream::stream! {
        let mut uploaded = 0_u64;
        for chunk in archive.chunks(64 * 1024) {
            if *stream_cancel.borrow() {
                yield Err::<Vec<u8>, std::io::Error>(std::io::Error::new(
                    std::io::ErrorKind::Interrupted,
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
    let upload_url = validated_case_url(&origin, &grant.upload.url, &grant.case_id, "archive")
        .map_err(ReportUploadError::Failed)?;
    let upload_request = client
        .put(upload_url)
        .bearer_auth(&grant.upload.token)
        .header(reqwest::header::CONTENT_TYPE, "application/zip")
        .header(reqwest::header::CONTENT_LENGTH, report.bytes)
        .body(reqwest::Body::wrap_stream(stream))
        .send();
    tokio::pin!(upload_request);
    let upload_response = tokio::select! {
        changed = cancel.changed() => {
            let _ = changed;
            let _ = upload_request.as_mut().await;
            return cancel_granted_case(&client, &origin, &grant, lifecycle).await;
        }
        response = upload_request.as_mut() => match response {
            Ok(response) => response,
            Err(_) if *cancel.borrow() => {
                return cancel_granted_case(&client, &origin, &grant, lifecycle).await;
            }
            Err(error) => {
                return Err(cleanup_granted_failure(
                    &client,
                    &origin,
                    &grant,
                    lifecycle,
                    format!("Could not upload the run report: {}", transport_detail(&error)),
                ).await);
            }
        },
    };
    let upload: Value = match response_json(upload_response, "The run-report archive was rejected")
        .await
    {
        Ok(upload) => upload,
        Err(detail) => {
            return Err(cleanup_granted_failure(&client, &origin, &grant, lifecycle, detail).await);
        }
    };
    if upload.pointer("/status").and_then(Value::as_str) != Some("uploaded")
        || upload.pointer("/caseId").and_then(Value::as_str) != Some(&grant.case_id)
        || upload.pointer("/bytes").and_then(Value::as_u64) != Some(report.bytes)
        || upload.pointer("/sha256").and_then(Value::as_str) != Some(&report.sha256)
    {
        return Err(cleanup_granted_failure(
            &client,
            &origin,
            &grant,
            lifecycle,
            "The intake returned an inconsistent upload receipt.".to_string(),
        )
        .await);
    }
    if *cancel.borrow() {
        return cancel_granted_case(&client, &origin, &grant, lifecycle).await;
    }

    emit(
        ReportUploadStateEvent::new("finalizing", id, report.bytes, report.bytes)
            .with_case(grant.case_id.clone()),
    );
    let finalize_url = validated_case_url(&origin, &grant.finalize.url, &grant.case_id, "finalize")
        .map_err(ReportUploadError::Failed)?;
    let finalize_response = match client
        .post(finalize_url)
        .bearer_auth(&grant.finalize.token)
        .send()
        .await
    {
        Ok(response) => response,
        Err(error) => {
            return Err(cleanup_granted_failure(
                &client,
                &origin,
                &grant,
                lifecycle,
                format!(
                    "Could not finalize the run report: {}",
                    transport_detail(&error)
                ),
            )
            .await);
        }
    };
    let receipt: ReportReceipt =
        match response_json(finalize_response, "The run report could not be finalized").await {
            Ok(receipt) => receipt,
            Err(detail) => {
                return Err(
                    cleanup_granted_failure(&client, &origin, &grant, lifecycle, detail).await,
                );
            }
        };
    if let Err(detail) = validate_report_receipt(&origin, &receipt, &grant.case_id, &report) {
        return Err(cleanup_granted_failure(&client, &origin, &grant, lifecycle, detail).await);
    }
    if let Err(detail) = lifecycle.accepted(&receipt) {
        return Err(cleanup_granted_failure(
            &client,
            &origin,
            &grant,
            lifecycle,
            format!(
                "The server accepted case {}, but Preflight could not durably save its deletion authority: {detail}",
                grant.case_id
            ),
        )
        .await);
    }
    Ok(receipt)
}

async fn cancel_granted_case(
    client: &Client,
    origin: &Url,
    grant: &CreateReportCaseResponse,
    lifecycle: &NativeReportAuthorityLifecycle,
) -> Result<ReportReceipt, ReportUploadError> {
    match delete_granted_case(client, origin, grant).await {
        Ok(()) => match lifecycle.cleared(&grant.case_id) {
            Ok(()) => Err(ReportUploadError::Cancelled),
            Err(detail) => Err(ReportUploadError::Failed(format!(
                "Case {} was deleted after cancellation, but local cleanup failed: {detail}",
                grant.case_id
            ))),
        },
        Err(detail) => Err(ReportUploadError::Failed(format!(
            "Upload cancellation could not confirm deletion of case {}: {detail}",
            grant.case_id
        ))),
    }
}

async fn cleanup_unpersisted_grant(
    client: &Client,
    origin: &Url,
    grant: &CreateReportCaseResponse,
    detail: String,
) -> ReportUploadError {
    match delete_granted_case(client, origin, grant).await {
        Ok(()) => ReportUploadError::Failed(format!(
            "{detail} The server case was deleted before any report bytes were sent."
        )),
        Err(cleanup) => ReportUploadError::Failed(format!(
            "{detail} Deletion of case {} could not be confirmed: {cleanup}",
            grant.case_id
        )),
    }
}

async fn cleanup_granted_failure(
    client: &Client,
    origin: &Url,
    grant: &CreateReportCaseResponse,
    lifecycle: &NativeReportAuthorityLifecycle,
    detail: String,
) -> ReportUploadError {
    match delete_granted_case(client, origin, grant).await {
        Ok(()) => match lifecycle.cleared(&grant.case_id) {
            Ok(()) => ReportUploadError::Failed(format!(
                "{detail} The incomplete server case was deleted; the local ZIP is unchanged."
            )),
            Err(local) => ReportUploadError::Failed(format!(
                "{detail} The server case was deleted, but local deletion-authority cleanup failed: {local}"
            )),
        },
        Err(cleanup) => ReportUploadError::Failed(format!(
            "{detail} Deletion of case {} could not be confirmed: {cleanup}",
            grant.case_id
        )),
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
        return Err(response_failure(response, "the cancellation cleanup was rejected").await);
    }
    Ok(())
}

fn validate_case_grant(
    origin: &Url,
    grant: &CreateReportCaseResponse,
    report: &ReportUploadInput,
) -> Result<(), String> {
    if grant.protocol_version != REPORT_PROTOCOL_VERSION || !is_case_id(&grant.case_id) {
        return Err("The intake returned an invalid case identity.".to_string());
    }
    validate_grant_endpoint(origin, &grant.upload, &grant.case_id, "PUT", "archive")?;
    let upload_expiry_is_valid = match grant.upload.expires_at.as_deref() {
        Some(value) => !value.is_empty(),
        None => false,
    };
    if grant.upload.content_type.as_deref() != Some("application/zip") || !upload_expiry_is_valid {
        return Err("The intake returned an invalid upload grant.".to_string());
    }
    validate_grant_endpoint(origin, &grant.finalize, &grant.case_id, "POST", "finalize")?;
    validate_grant_endpoint(origin, &grant.deletion, &grant.case_id, "DELETE", "")?;
    if report.bytes == 0 || !is_lower_sha256(&report.sha256) {
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
        let detail = serde_json::from_slice::<Value>(&bytes)
            .ok()
            .and_then(|value| {
                value
                    .pointer("/error")
                    .and_then(Value::as_str)
                    .map(str::to_string)
            });
        return Err(detail
            .map(|detail| format!("{context}: {detail}"))
            .unwrap_or_else(|| format!("{context}: HTTP {status}")));
    }
    serde_json::from_slice(&bytes)
        .map_err(|error| format!("{context}: unreadable response: {error}"))
}

async fn response_failure(response: Response, context: &str) -> String {
    let status = response.status();
    match bounded_response_body(response).await {
        Ok(bytes) => serde_json::from_slice::<Value>(&bytes)
            .ok()
            .and_then(|value| {
                value
                    .pointer("/error")
                    .and_then(Value::as_str)
                    .map(str::to_string)
            })
            .map(|detail| format!("{context}: {detail}"))
            .unwrap_or_else(|| format!("{context}: HTTP {status}")),
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn verified_bytes_survive_a_later_path_change() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let archive = std::env::temp_dir().join(format!(
            "preflight-report-v2-bytes-{}-{unique}.zip",
            std::process::id()
        ));
        fs::write(&archive, b"abc").unwrap();
        let report = ReportUploadInput {
            output: archive.to_string_lossy().into_owned(),
            bytes: 3,
            sha256: Sha256::digest(b"abc")
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect(),
        };
        let verified = validated_report_archive_bytes(&report).unwrap();
        fs::write(&archive, b"changed after verification").unwrap();
        assert_eq!(b"abc", verified.as_slice());
        fs::remove_file(archive).unwrap();
    }
}
