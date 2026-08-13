use crate::reports::{
    CreateReportCaseRequest, CreateReportCaseResponse, ReportDeletion, ReportGrantEndpoint,
    ReportReceipt, ReportUploadError, ReportUploadInput, ReportUploadStateEvent,
};
use futures_util::StreamExt;
use reqwest::{Client, Response, StatusCode, redirect::Policy};
use serde::de::DeserializeOwned;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use tokio::io::AsyncReadExt;
use tokio::sync::watch;
use url::Url;

const REPORT_INTAKE_ORIGIN: Option<&str> = option_env!("PREFLIGHT_REPORT_INTAKE_ORIGIN");
const REPORT_PROTOCOL_VERSION: u32 = 1;
const REPORT_RESPONSE_LIMIT: usize = 64 * 1024;
const REPORT_UPLOAD_LIMIT: u64 = 6 * 1024 * 1024;

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

pub(crate) async fn perform_report_upload(
    client: Client,
    origin: Url,
    archive: PathBuf,
    report: ReportUploadInput,
    id: u64,
    mut cancel: watch::Receiver<bool>,
    emit: impl Fn(ReportUploadStateEvent) + Clone + Send + Sync + 'static,
) -> Result<ReportReceipt, ReportUploadError> {
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

    emit(
        ReportUploadStateEvent::new("uploading", id, 0, report.bytes)
            .with_case(grant.case_id.clone()),
    );
    if *cancel.borrow() {
        delete_granted_case(&client, &origin, &grant)
            .await
            .map_err(|detail| {
                ReportUploadError::Failed(format!(
                    "Upload cancellation could not confirm deletion of case {}: {detail}",
                    grant.case_id,
                ))
            })?;
        return Err(ReportUploadError::Cancelled);
    }
    let mut stream_cancel = cancel.clone();
    let stream_emit = emit.clone();
    let case_id = grant.case_id.clone();
    let total = report.bytes;
    let stream = async_stream::stream! {
        let mut file = match tokio::fs::File::open(&archive).await {
            Ok(file) => file,
            Err(error) => {
                yield Err::<Vec<u8>, std::io::Error>(error);
                return;
            }
        };
        let mut buffer = vec![0_u8; 64 * 1024];
        let mut uploaded = 0_u64;
        loop {
            if *stream_cancel.borrow() {
                yield Err(std::io::Error::new(
                    std::io::ErrorKind::Interrupted,
                    "report upload cancelled",
                ));
                return;
            }
            let read_result: std::io::Result<usize> = tokio::select! {
                changed = stream_cancel.changed() => {
                    let _ = changed;
                    Err(std::io::Error::new(std::io::ErrorKind::Interrupted, "report upload cancelled"))
                }
                read = file.read(&mut buffer) => read,
            };
            let read = match read_result {
                Ok(read) => read,
                Err(error) => {
                    yield Err(error);
                    return;
                }
            };
            if read == 0 {
                break;
            }
            uploaded = uploaded.saturating_add(read as u64);
            stream_emit(ReportUploadStateEvent::new("uploading", id, uploaded, total)
                .with_case(case_id.clone()));
            yield Ok(buffer[..read].to_vec());
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
            delete_granted_case(&client, &origin, &grant).await.map_err(|detail| {
                ReportUploadError::Failed(format!(
                    "Upload cancellation could not confirm deletion of case {}: {detail}",
                    grant.case_id,
                ))
            })?;
            return Err(ReportUploadError::Cancelled);
        }
        response = upload_request.as_mut() => match response {
            Ok(response) => response,
            Err(_) if *cancel.borrow() => {
                delete_granted_case(&client, &origin, &grant).await.map_err(|detail| {
                    ReportUploadError::Failed(format!(
                        "Upload cancellation could not confirm deletion of case {}: {detail}",
                        grant.case_id,
                    ))
                })?;
                return Err(ReportUploadError::Cancelled);
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
    let upload: Value =
        match response_json(upload_response, "The run-report archive was rejected").await {
            Ok(upload) => upload,
            Err(detail) => {
                return Err(cleanup_granted_failure(&client, &origin, &grant, detail).await);
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
            "The intake returned an inconsistent upload receipt.".to_string(),
        )
        .await);
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
                return Err(cleanup_granted_failure(&client, &origin, &grant, detail).await);
            }
        };
    if let Err(detail) = validate_report_receipt(&origin, &receipt, &grant.case_id, &report) {
        return Err(cleanup_granted_failure(&client, &origin, &grant, detail).await);
    }
    Ok(receipt)
}

async fn cleanup_granted_failure(
    client: &Client,
    origin: &Url,
    grant: &CreateReportCaseResponse,
    detail: String,
) -> ReportUploadError {
    match delete_granted_case(client, origin, grant).await {
        Ok(()) => ReportUploadError::Failed(format!(
            "{detail} The incomplete server case was deleted; the local ZIP is unchanged."
        )),
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

/// Renders a transport error together with everything underneath it.
///
/// `reqwest::Error` prints only its own layer, so a failure to reach the intake reads as
/// "error sending request for url (...)" and names neither the reason nor the operating system's
/// answer. The cause is always one or two `source()` hops down -- a refused connection, a DNS
/// failure, a closed stream -- and without it a report that will not send is indistinguishable from
/// one that was refused, both for a player asking why and for a failing test.
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

pub(crate) fn validated_report_archive(report: &ReportUploadInput) -> Result<PathBuf, String> {
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
    if before.len() != report.bytes {
        return Err("The diagnostics ZIP size changed after its disclosure.".to_string());
    }
    let before_modified = before.modified().ok();
    let mut file = fs::File::open(&archive)
        .map_err(|error| format!("Could not open the diagnostics ZIP: {error}"))?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .map_err(|error| format!("Could not verify the diagnostics ZIP: {error}"))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    let after = archive
        .metadata()
        .map_err(|error| format!("Could not recheck the diagnostics ZIP: {error}"))?;
    if after.len() != before.len() || after.modified().ok() != before_modified {
        return Err("The diagnostics ZIP changed while it was being verified.".to_string());
    }
    let digest = hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    if digest != report.sha256 {
        return Err("The diagnostics ZIP SHA-256 changed after its disclosure.".to_string());
    }
    Ok(archive)
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
    if grant.upload.content_type.as_deref() != Some("application/zip")
        || grant.upload.expires_at.as_deref().is_none_or(str::is_empty)
    {
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

pub(crate) fn validate_report_receipt(
    origin: &Url,
    receipt: &ReportReceipt,
    case_id: &str,
    report: &ReportUploadInput,
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
        || receipt.product_version != env!("CARGO_PKG_VERSION")
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

pub(crate) fn emit_report_state(app: &AppHandle, event: ReportUploadStateEvent) {
    let _ = app.emit("report-upload-state", event);
}
