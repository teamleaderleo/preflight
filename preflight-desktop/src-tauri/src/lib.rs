use futures_util::StreamExt;
use reqwest::{Client, Response, StatusCode, redirect::Policy};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{BufRead, BufReader, Read};
use std::path::PathBuf;
use std::process::{Child, Stdio};
use std::sync::{Mutex, mpsc};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::io::AsyncReadExt;
use tokio::sync::watch;
use url::Url;

mod automation;
mod engine;
mod operations;
mod reports;
mod updates;

use automation::{
    cancel_desktop_smoke, get_desktop_smoke_probe, open_desktop_accessibility_settings,
    request_desktop_smoke_cancellation, start_desktop_smoke,
};
use engine::{
    EnginePaths, activate_profile, apply_cache_cleanup, apply_removal, canonical_game_directory,
    export_diagnostics, get_cache, get_cache_cleanup, get_launch_settings, get_profiles,
    get_removal_plan, get_snapshot, save_profile, update_launch_settings,
};
use operations::{OperationCoordinator, OperationState, PreparationProcess, refuse_update_install};
use reports::{
    CreateReportCaseRequest, CreateReportCaseResponse, ReportDeletion, ReportGrantEndpoint,
    ReportReceipt, ReportUploadError, ReportUploadInput, ReportUploadStateEvent, cancel_run_report,
    delete_run_report, get_report_intake_status, send_run_report,
};
use updates::{UpdateTracker, check_for_update, install_update};

const REPORT_INTAKE_ORIGIN: Option<&str> = option_env!("PREFLIGHT_REPORT_INTAKE_ORIGIN");
const REPORT_PROTOCOL_VERSION: u32 = 1;
const REPORT_RESPONSE_LIMIT: usize = 64 * 1024;
const REPORT_UPLOAD_LIMIT: u64 = 6 * 1024 * 1024;
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RunStarted {
    pid: u32,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RunStateEvent {
    state: &'static str,
    pid: u32,
    success: Option<bool>,
    detail: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PreparationStateEvent {
    state: &'static str,
    pid: u32,
    success: Option<bool>,
    detail: Option<String>,
    report: Option<String>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PreparationProgressEvent {
    #[serde(default)]
    pid: u32,
    format: String,
    phase: String,
    state: String,
    total_phases: u32,
    status: Option<String>,
    duration_ms: Option<f64>,
    #[serde(default)]
    metrics: Value,
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
        .map_err(|error| format!("Could not request report deletion: {error}"))?;
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
            format!("Could not create a run-report case: {error}")
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
                    format!("Could not upload the run report: {error}"),
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
                format!("Could not finalize the run report: {error}"),
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
        .map_err(|error| format!("could not contact the deletion endpoint: {error}"))?;
    if response.status() != StatusCode::NO_CONTENT {
        return Err(response_failure(response, "the cancellation cleanup was rejected").await);
    }
    Ok(())
}

pub(crate) fn configured_report_origin() -> Result<Url, String> {
    validate_report_origin(REPORT_INTAKE_ORIGIN)
}

fn validate_report_origin(configured: Option<&str>) -> Result<Url, String> {
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

fn validated_case_url(
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

fn validate_report_receipt(
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
        let chunk =
            chunk.map_err(|error| format!("Could not read the report intake response: {error}"))?;
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

#[tauri::command]
fn start_game(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    game: String,
    optimization_preset: String,
) -> Result<RunStarted, String> {
    let directory = canonical_game_directory(&game)?;
    let optimization_preset = validate_optimization_preset(&optimization_preset)?;
    let paths = EnginePaths::resolve(&app)?;

    let mut running = tracker
        .0
        .lock()
        .map_err(|_| "The launch tracker is unavailable.".to_string())?;
    refuse_update_install(&running)?;
    if running.game.is_some() {
        return Err("Starsector is already running through Preflight.".to_string());
    }
    if running.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before launching Starsector.".to_string(),
        );
    }

    let mut command = paths.command();
    command
        .arg("run")
        .arg("--optimization-preset")
        .arg(optimization_preset)
        .arg("--game")
        .arg(directory);
    command.stderr(Stdio::piped());
    let child = command
        .spawn()
        .map_err(|error| format!("Could not launch Starsector: {error}"))?;
    let pid = child.id();
    running.game = Some(pid);
    drop(running);

    let _ = app.emit(
        "run-state",
        RunStateEvent {
            state: "started",
            pid,
            success: None,
            detail: None,
        },
    );

    watch_child(app, child);
    Ok(RunStarted { pid })
}

fn validate_optimization_preset(value: &str) -> Result<&str, String> {
    match value {
        "recommended" | "conservative" | "off" => Ok(value),
        _ => Err("Optimization preset must be recommended, conservative, or off.".to_string()),
    }
}

#[tauri::command]
fn start_preparation(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    game: String,
    texture_storage: String,
    workers: u8,
    memory_mib: u32,
) -> Result<RunStarted, String> {
    let directory = canonical_game_directory(&game)?;
    if texture_storage != "balanced" && texture_storage != "fastest" {
        return Err("Texture storage must be balanced or fastest.".to_string());
    }
    if !(1..=64).contains(&workers) {
        return Err("Preparation workers must be between 1 and 64.".to_string());
    }
    if !(16..=65_536).contains(&memory_mib) {
        return Err("Preparation memory must be between 16 and 65536 MiB.".to_string());
    }
    let paths = EnginePaths::resolve(&app)?;
    let mut running = tracker
        .0
        .lock()
        .map_err(|_| "The preparation tracker is unavailable.".to_string())?;
    refuse_update_install(&running)?;
    if running.preparation.is_some() {
        return Err("This profile is already being prepared.".to_string());
    }
    if running.game.is_some() {
        return Err("Close Starsector before preparing its current profile.".to_string());
    }

    let mut command = paths.command();
    command
        .arg("prepare")
        .arg("--game")
        .arg(directory)
        .arg("--texture-storage")
        .arg(texture_storage)
        .arg("--workers")
        .arg(workers.to_string())
        .arg("--memory-mb")
        .arg(memory_mib.to_string())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let child = command
        .spawn()
        .map_err(|error| format!("Could not start profile preparation: {error}"))?;
    let pid = child.id();
    let (cancel, cancel_receiver) = mpsc::channel();
    running.preparation = Some(PreparationProcess { pid, cancel });
    drop(running);

    let _ = app.emit(
        "prepare-state",
        PreparationStateEvent {
            state: "started",
            pid,
            success: None,
            detail: None,
            report: None,
        },
    );
    watch_preparation(app, child, cancel_receiver);
    Ok(RunStarted { pid })
}

#[tauri::command]
fn cancel_preparation(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
) -> Result<bool, String> {
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The preparation tracker is unavailable.".to_string())?;
    let Some(preparation) = running.preparation.as_ref() else {
        return Ok(false);
    };
    let pid = preparation.pid;
    preparation
        .cancel
        .send(())
        .map_err(|_| "Preparation has already stopped.".to_string())?;
    drop(running);
    let _ = app.emit(
        "prepare-state",
        PreparationStateEvent {
            state: "cancelling",
            pid,
            success: None,
            detail: None,
            report: None,
        },
    );
    Ok(true)
}

#[tauri::command]
fn get_preparation_plan(
    app: AppHandle,
    game: String,
    texture_storage: String,
    workers: u8,
) -> Result<Value, String> {
    let directory = canonical_game_directory(&game)?;
    if texture_storage != "balanced" && texture_storage != "fastest" {
        return Err("Texture storage must be balanced or fastest.".to_string());
    }
    if !(1..=64).contains(&workers) {
        return Err("Preparation workers must be between 1 and 64.".to_string());
    }
    let paths = EnginePaths::resolve(&app)?;
    let output = paths
        .command()
        .arg("prepare")
        .arg("--plan")
        .arg("--json")
        .arg("--game")
        .arg(directory)
        .arg("--texture-storage")
        .arg(texture_storage)
        .arg("--workers")
        .arg(workers.to_string())
        .output()
        .map_err(|error| format!("Could not calculate preparation storage: {error}"))?;
    if !output.status.success() {
        return Err(child_error(
            "Preflight could not calculate preparation storage",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned an unreadable storage plan: {error}"))
}

fn watch_child(app: AppHandle, mut child: Child) {
    let pid = child.id();
    std::thread::spawn(move || {
        let stderr = child
            .stderr
            .take()
            .map(|mut stderr| read_tail(&mut stderr, 8 * 1024))
            .unwrap_or_default();
        let status = child.wait();
        let success = status.as_ref().is_ok_and(|status| status.success());
        let detail = if success {
            None
        } else {
            Some(child_error("Starsector exited with an error", &stderr))
        };
        if let Ok(mut running) = app.state::<OperationCoordinator>().0.lock() {
            running.game = None;
        }
        let _ = app.emit(
            "run-state",
            RunStateEvent {
                state: "finished",
                pid,
                success: Some(success),
                detail,
            },
        );
    });
}

pub(crate) fn take_deferred_exit(running: &mut OperationState) -> bool {
    if running.exit_after_cleanup
        && running.desktop_smoke.is_none()
        && running.preparation.is_none()
        && running.report_upload.is_none()
    {
        running.exit_after_cleanup = false;
        true
    } else {
        false
    }
}

fn begin_exit_cleanup(running: &mut OperationState) -> Result<bool, String> {
    let mut pending = false;
    if let Some(process) = running.desktop_smoke.as_ref() {
        request_desktop_smoke_cancellation(process)?;
        pending = true;
    }
    if let Some(preparation) = running.preparation.as_ref() {
        if preparation.cancel.send(()).is_ok() {
            pending = true;
        } else {
            running.preparation = None;
        }
    }
    if let Some(upload) = running.report_upload.as_ref() {
        if upload.cancel.send(true).is_ok() {
            pending = true;
        } else {
            running.report_upload = None;
        }
    }
    if pending {
        running.exit_after_cleanup = true;
    }
    Ok(pending)
}

fn watch_preparation(app: AppHandle, mut child: Child, cancel: mpsc::Receiver<()>) {
    let pid = child.id();
    std::thread::spawn(move || {
        let stdout = child
            .stdout
            .take()
            .map(|mut stdout| std::thread::spawn(move || read_tail(&mut stdout, 16 * 1024)));
        let stderr_app = app.clone();
        let stderr = child.stderr.take().map(|stderr| {
            std::thread::spawn(move || read_preparation_stderr(stderr_app, pid, stderr))
        });

        let mut cancelled = false;
        let status = loop {
            if cancel.try_recv().is_ok() {
                cancelled = true;
                let _ = child.kill();
                break child.wait();
            }
            match child.try_wait() {
                Ok(Some(status)) => break Ok(status),
                Ok(None) => std::thread::sleep(Duration::from_millis(50)),
                Err(error) => break Err(error),
            }
        };
        let stdout = stdout
            .and_then(|reader| reader.join().ok())
            .unwrap_or_default();
        let stderr = stderr
            .and_then(|reader| reader.join().ok())
            .unwrap_or_default();
        let success = !cancelled && status.as_ref().is_ok_and(|status| status.success());
        let report = if success {
            let value = String::from_utf8_lossy(&stdout).trim().to_string();
            (!value.is_empty()).then_some(value)
        } else {
            None
        };
        let detail = if cancelled {
            Some(
                "Preparation stopped safely. Finished cache artifacts remain reusable.".to_string(),
            )
        } else {
            match &status {
                Ok(status) if status.success() => None,
                Ok(_) => Some(child_error("Profile preparation failed", &stderr)),
                Err(error) => Some(format!("Could not wait for profile preparation: {error}")),
            }
        };
        let should_exit = if let Ok(mut running) = app.state::<OperationCoordinator>().0.lock() {
            if running
                .preparation
                .as_ref()
                .is_some_and(|process| process.pid == pid)
            {
                running.preparation = None;
            }
            take_deferred_exit(&mut running)
        } else {
            false
        };
        let _ = app.emit(
            "prepare-state",
            PreparationStateEvent {
                state: if cancelled { "cancelled" } else { "finished" },
                pid,
                success: Some(success),
                detail,
                report,
            },
        );
        if should_exit {
            app.exit(0);
        }
    });
}

const PREPARATION_PROGRESS_PREFIX: &str = "PREFLIGHT_PROGRESS ";
const PREPARATION_PROGRESS_FORMAT: &str = "preflight-preparation-progress-v1";

fn parse_preparation_progress(line: &str, pid: u32) -> Option<PreparationProgressEvent> {
    let json = line.strip_prefix(PREPARATION_PROGRESS_PREFIX)?;
    let mut event: PreparationProgressEvent = serde_json::from_str(json).ok()?;
    if event.format != PREPARATION_PROGRESS_FORMAT || event.phase.is_empty() {
        return None;
    }
    event.pid = pid;
    Some(event)
}

fn read_preparation_stderr(app: AppHandle, pid: u32, stderr: impl Read) -> Vec<u8> {
    let mut tail = Vec::with_capacity(8 * 1024);
    for line in BufReader::new(stderr).lines() {
        let Ok(line) = line else { break };
        if let Some(progress) = parse_preparation_progress(&line, pid) {
            let _ = app.emit("prepare-progress", progress);
        }
        append_tail(&mut tail, line.as_bytes(), 8 * 1024);
        append_tail(&mut tail, b"\n", 8 * 1024);
    }
    tail
}

fn append_tail(tail: &mut Vec<u8>, bytes: &[u8], limit: usize) {
    if bytes.len() >= limit {
        tail.clear();
        tail.extend_from_slice(&bytes[bytes.len() - limit..]);
        return;
    }
    let overflow = tail.len().saturating_add(bytes.len()).saturating_sub(limit);
    if overflow > 0 {
        tail.drain(..overflow);
    }
    tail.extend_from_slice(bytes);
}

fn read_tail(reader: &mut dyn Read, limit: usize) -> Vec<u8> {
    let mut tail = Vec::with_capacity(limit);
    let mut chunk = [0_u8; 4096];
    loop {
        let read = match reader.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(read) => read,
        };
        if read >= limit {
            tail.clear();
            tail.extend_from_slice(&chunk[read - limit..read]);
            continue;
        }
        let overflow = tail.len().saturating_add(read).saturating_sub(limit);
        if overflow > 0 {
            tail.drain(..overflow);
        }
        tail.extend_from_slice(&chunk[..read]);
    }
    tail
}

pub(crate) fn child_error(context: &str, stderr: &[u8]) -> String {
    let details = String::from_utf8_lossy(stderr);
    let details = details.trim();
    if details.is_empty() {
        context.to_string()
    } else {
        format!("{context}: {details}")
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(OperationCoordinator::default())
        .manage(UpdateTracker(Mutex::new(None)))
        .setup(|app| {
            if std::env::var_os("PREFLIGHT_DESKTOP_BOOT_SMOKE").as_deref()
                == Some(std::ffi::OsStr::new("1"))
            {
                println!("PREFLIGHT_DESKTOP_BOOT_SMOKE_OK");
                app.handle().exit(0);
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_snapshot,
            get_desktop_smoke_probe,
            open_desktop_accessibility_settings,
            start_desktop_smoke,
            cancel_desktop_smoke,
            get_cache,
            get_cache_cleanup,
            apply_cache_cleanup,
            get_removal_plan,
            apply_removal,
            check_for_update,
            install_update,
            export_diagnostics,
            get_report_intake_status,
            send_run_report,
            cancel_run_report,
            delete_run_report,
            get_launch_settings,
            update_launch_settings,
            get_profiles,
            save_profile,
            activate_profile,
            start_game,
            get_preparation_plan,
            start_preparation,
            cancel_preparation
        ])
        .build(tauri::generate_context!())
        .expect("error while running Preflight");
    app.run(|app, event| {
        let tauri::RunEvent::ExitRequested { code, api, .. } = event else {
            return;
        };
        if code == Some(tauri::RESTART_EXIT_CODE) {
            return;
        }
        let cleanup = app
            .state::<OperationCoordinator>()
            .0
            .lock()
            .map_err(|_| "The process tracker is unavailable during shutdown.".to_string())
            .and_then(|mut running| begin_exit_cleanup(&mut running));
        match cleanup {
            Ok(true) => api.prevent_exit(),
            Ok(false) => {}
            Err(error) => {
                api.prevent_exit();
                eprintln!("Preflight delayed shutdown: {error}");
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::{
        ReportDeletion, ReportReceipt, ReportUploadError, ReportUploadInput, begin_exit_cleanup,
        parse_preparation_progress, perform_report_deletion, perform_report_upload, read_tail,
        report_client, take_deferred_exit, validate_optimization_preset, validate_report_origin,
        validate_report_receipt, validated_case_url, validated_report_archive,
    };
    use crate::automation::{
        DESKTOP_SMOKE_CANCELLATION_FILE, desktop_smoke_cancellation_outcome,
        desktop_smoke_cancellation_requested, desktop_smoke_outcome,
    };
    use crate::engine::{
        LaunchSettingsInput, diagnostic_output_path, validate_launch_settings,
        validate_removal_scope,
    };
    use crate::operations::{
        DesktopSmokeProcess, OperationState, PreparationProcess, ReportUploadProcess,
        begin_update_install, refuse_update_install,
    };
    use crate::updates::{
        DEFAULT_UPDATE_ENDPOINT, compiled_updater_endpoint, validated_updater_endpoint,
    };
    use sha2::{Digest, Sha256};
    use std::fs;
    use std::io::{Cursor, Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::process::Command;
    use std::sync::{Arc, Mutex, mpsc};
    use std::thread;
    use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
    use tokio::sync::{Mutex as AsyncMutex, watch};
    use url::Url;

    #[cfg(target_os = "macos")]
    use crate::automation::MACOS_ACCESSIBILITY_SETTINGS;

    // Each case owns a blocking loopback server. Serialize those cases and handshake with the
    // accept thread below so rapid parallel suites never race a freshly opened listener.
    static REPORT_SERVER_TEST_LOCK: AsyncMutex<()> = AsyncMutex::const_new(());

    #[test]
    fn keeps_only_the_bounded_end_of_child_stderr() {
        let mut stderr = Cursor::new(b"0123456789abcdef");

        assert_eq!(b"89abcdef", read_tail(&mut stderr, 8).as_slice());
    }

    #[test]
    fn keeps_short_child_stderr_in_full() {
        let mut stderr = Cursor::new(b"useful failure");

        assert_eq!(b"useful failure", read_tail(&mut stderr, 1024).as_slice());
    }

    #[test]
    fn desktop_smoke_receipt_controls_success_instead_of_the_process_alone() {
        let status = successful_status();
        let passed = br#"{"protocol":1,"launch":{"status":"passed","diagnostics":[]}}"#;
        let failed =
            br#"{"protocol":1,"launch":{"status":"failed","diagnostics":["bounded failure"]}}"#;

        assert_eq!(
            (true, None),
            desktop_smoke_outcome(&Ok(status), passed, b"")
        );
        let status = successful_status();
        assert_eq!(
            (false, Some("bounded failure".to_string())),
            desktop_smoke_outcome(&Ok(status), failed, b""),
        );
    }

    #[test]
    fn cancellation_requires_a_sealed_cancelled_receipt() {
        let status = successful_status();
        let cancelled = br#"{"protocol":1,"launch":{"status":"cancelled","diagnostics":[]}}"#;
        assert_eq!(
            (
                true,
                Some("Automated game test stopped safely after exact-process cleanup.".to_string())
            ),
            desktop_smoke_cancellation_outcome(&Ok(status), cancelled, b"")
        );

        let status = successful_status();
        let failed =
            br#"{"protocol":1,"launch":{"status":"failed","diagnostics":["cleanup failed"]}}"#;
        assert_eq!(
            (false, Some("cleanup failed".to_string())),
            desktop_smoke_cancellation_outcome(&Ok(status), failed, b"")
        );
    }

    #[test]
    fn app_exit_requests_owned_cleanup_before_it_can_finish() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let run_directory = std::env::temp_dir().join(format!(
            "preflight-desktop-exit-test-{}-{unique}",
            std::process::id()
        ));
        fs::create_dir(&run_directory).unwrap();
        let (cancel, cancelled) = mpsc::channel();
        let (report_cancel, report_cancelled) = watch::channel(false);
        let mut running = OperationState {
            game: Some(41),
            desktop_smoke: Some(DesktopSmokeProcess {
                pid: 41,
                run_directory: run_directory.clone(),
            }),
            preparation: Some(PreparationProcess { pid: 42, cancel }),
            report_upload: Some(ReportUploadProcess {
                id: 43,
                total_bytes: 1_024,
                cancel: report_cancel,
            }),
            update_installing: false,
            exit_after_cleanup: false,
        };

        assert!(begin_exit_cleanup(&mut running).unwrap());
        assert!(desktop_smoke_cancellation_requested(&run_directory));
        assert!(cancelled.try_recv().is_ok());
        assert!(*report_cancelled.borrow());
        assert!(running.exit_after_cleanup);
        assert!(!take_deferred_exit(&mut running));
        running.desktop_smoke = None;
        running.preparation = None;
        running.report_upload = None;
        assert!(take_deferred_exit(&mut running));
        assert!(!running.exit_after_cleanup);

        fs::remove_file(run_directory.join(DESKTOP_SMOKE_CANCELLATION_FILE)).unwrap();
        fs::remove_dir(run_directory).unwrap();
    }

    #[test]
    fn report_origin_and_case_urls_fail_closed() {
        assert!(validate_report_origin(None).is_err());
        assert!(validate_report_origin(Some("http://reports.example.com")).is_err());
        assert!(validate_report_origin(Some("https://reports.preflight.invalid")).is_err());
        assert!(validate_report_origin(Some("https://reports.example.com/path")).is_err());
        let origin = validate_report_origin(Some("https://reports.example.com")).unwrap();
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        assert!(
            validated_case_url(
                &origin,
                &format!("https://reports.example.com/v1/cases/{case_id}/archive"),
                case_id,
                "archive",
            )
            .is_ok()
        );
        assert!(
            validated_case_url(
                &origin,
                &format!("https://elsewhere.example/v1/cases/{case_id}/archive"),
                case_id,
                "archive",
            )
            .is_err()
        );
    }

    #[test]
    fn report_receipt_matches_the_flat_operator_resolvable_object_key() {
        let origin = validate_report_origin(Some("https://reports.example.com")).unwrap();
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        let report = ReportUploadInput {
            output: "/unused/report.zip".to_string(),
            bytes: 197_375,
            sha256: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".to_string(),
        };
        let receipt = ReportReceipt {
            protocol_version: 1,
            case_id: case_id.to_string(),
            object_key: format!("accepted/{case_id}.zip"),
            bytes: report.bytes,
            sha256: report.sha256.clone(),
            product_version: env!("CARGO_PKG_VERSION").to_string(),
            received_at: "2026-08-07T18:00:00.000Z".to_string(),
            retention_deadline: "2026-08-22T18:00:00.000Z".to_string(),
            deletion: ReportDeletion {
                method: "DELETE".to_string(),
                url: format!("https://reports.example.com/v1/cases/{case_id}"),
                token: "header.signature".to_string(),
            },
            signature: "signed-receipt".to_string(),
        };

        assert!(validate_report_receipt(&origin, &receipt, case_id, &report).is_ok());
        let mut stale = receipt;
        stale.object_key = format!("accepted/2026-08-07/{case_id}.zip");
        assert!(validate_report_receipt(&origin, &stale, case_id, &report).is_err());
    }

    #[test]
    fn report_archive_must_still_match_its_disclosed_digest() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let archive = std::env::temp_dir().join(format!(
            "preflight-report-archive-test-{}-{unique}.zip",
            std::process::id()
        ));
        fs::write(&archive, b"abc").unwrap();
        let report = ReportUploadInput {
            output: archive.to_string_lossy().into_owned(),
            bytes: 3,
            sha256: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".to_string(),
        };

        assert_eq!(
            archive.canonicalize().unwrap(),
            validated_report_archive(&report).unwrap()
        );
        fs::write(&archive, b"changed").unwrap();
        assert!(validated_report_archive(&report).is_err());
        fs::remove_file(archive).unwrap();
    }

    #[tokio::test(flavor = "current_thread")]
    async fn cancelled_report_upload_deletes_its_incomplete_server_case() {
        let _server_test = REPORT_SERVER_TEST_LOCK.lock().await;
        let (origin, requests, server) = local_report_server();
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let archive = std::env::temp_dir().join(format!(
            "preflight-report-cancel-test-{}-{unique}.zip",
            std::process::id()
        ));
        let payload = vec![0x5a; 256 * 1024];
        fs::write(&archive, &payload).unwrap();
        let report = ReportUploadInput {
            output: archive.to_string_lossy().into_owned(),
            bytes: payload.len() as u64,
            sha256: Sha256::digest(&payload)
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect(),
        };
        let (cancel, cancel_receiver) = watch::channel(false);
        let cancel_on_upload = cancel.clone();

        let outcome = perform_report_upload(
            report_client().unwrap(),
            origin,
            archive.clone(),
            report,
            71,
            cancel_receiver,
            move |event| {
                if event.state == "uploading" && event.uploaded_bytes == 0 {
                    let _ = cancel_on_upload.send(true);
                }
            },
        )
        .await;

        assert!(matches!(outcome, Err(ReportUploadError::Cancelled)));
        server.join().unwrap();
        let requests = requests.lock().unwrap();
        assert!(
            requests
                .iter()
                .any(|request| { request.method == "POST" && request.path == "/v1/cases" })
        );
        assert!(requests.iter().any(|request| {
            request.method == "DELETE"
                && request.path == "/v1/cases/3961d5f3-cd4c-4b62-b915-e9cc5a68d5db"
                && request.authorization.as_deref() == Some("Bearer delete.signature")
        }));
        assert_eq!(payload, fs::read(&archive).unwrap());
        drop(requests);
        fs::remove_file(archive).unwrap();
    }

    #[tokio::test(flavor = "current_thread")]
    async fn successful_report_upload_streams_and_finalizes_the_disclosed_archive() {
        let _server_test = REPORT_SERVER_TEST_LOCK.lock().await;
        let (origin, requests, server) = local_report_server();
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let archive = std::env::temp_dir().join(format!(
            "preflight-report-success-test-{}-{unique}.zip",
            std::process::id()
        ));
        let payload = b"bounded local diagnostics archive".to_vec();
        fs::write(&archive, &payload).unwrap();
        let digest: String = Sha256::digest(&payload)
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        let report = ReportUploadInput {
            output: archive.to_string_lossy().into_owned(),
            bytes: payload.len() as u64,
            sha256: digest.clone(),
        };
        let (_cancel, cancel_receiver) = watch::channel(false);
        let states = Arc::new(Mutex::new(Vec::new()));
        let emitted_states = states.clone();

        let receipt = perform_report_upload(
            report_client().unwrap(),
            origin,
            archive.clone(),
            report,
            72,
            cancel_receiver,
            move |event| emitted_states.lock().unwrap().push(event.state),
        )
        .await
        .unwrap();

        server.join().unwrap();
        assert_eq!(payload.len() as u64, receipt.bytes);
        assert_eq!(digest, receipt.sha256);
        assert_eq!(
            vec!["uploading", "uploading", "finalizing"],
            *states.lock().unwrap()
        );
        let requests = requests.lock().unwrap();
        assert!(requests.iter().any(|request| {
            request.method == "PUT"
                && request.path == "/v1/cases/3961d5f3-cd4c-4b62-b915-e9cc5a68d5db/archive"
                && request.authorization.as_deref() == Some("Bearer upload.signature")
                && request.body == payload
        }));
        assert!(requests.iter().any(|request| {
            request.method == "POST"
                && request.path == "/v1/cases/3961d5f3-cd4c-4b62-b915-e9cc5a68d5db/finalize"
                && request.authorization.as_deref() == Some("Bearer finalize.signature")
        }));
        drop(requests);
        fs::remove_file(archive).unwrap();
    }

    #[tokio::test(flavor = "current_thread")]
    async fn report_receipt_deletion_uses_its_bearer_grant() {
        let _server_test = REPORT_SERVER_TEST_LOCK.lock().await;
        let (origin, requests, server) = local_report_server();
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        let deletion = ReportDeletion {
            method: "DELETE".to_string(),
            url: origin
                .join(&format!("v1/cases/{case_id}"))
                .unwrap()
                .to_string(),
            token: "receipt.signature".to_string(),
        };

        assert!(
            perform_report_deletion(report_client().unwrap(), origin, deletion)
                .await
                .unwrap()
        );
        server.join().unwrap();
        let requests = requests.lock().unwrap();
        assert_eq!(1, requests.len());
        assert_eq!("DELETE", requests[0].method);
        assert_eq!(format!("/v1/cases/{case_id}"), requests[0].path);
        assert_eq!(
            Some("Bearer receipt.signature"),
            requests[0].authorization.as_deref()
        );
    }

    #[derive(Debug)]
    struct RecordedRequest {
        method: String,
        path: String,
        authorization: Option<String>,
        body: Vec<u8>,
    }

    fn local_report_server() -> (
        Url,
        Arc<Mutex<Vec<RecordedRequest>>>,
        thread::JoinHandle<()>,
    ) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap();
        let origin = Url::parse(&format!("http://{address}/")).unwrap();
        let server_origin = origin.clone();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let server_requests = requests.clone();
        let (ready, server_started) = mpsc::sync_channel(1);
        let server = thread::spawn(move || {
            let mut report_identity = None;
            let deadline = Instant::now() + Duration::from_secs(10);
            ready.send(()).unwrap();
            while Instant::now() < deadline {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let Some(request) = read_local_request(&mut stream) else {
                            continue;
                        };
                        if request.method == "POST" && request.path == "/v1/cases" {
                            let value: serde_json::Value =
                                serde_json::from_slice(&request.body).unwrap();
                            report_identity = Some((
                                value
                                    .pointer("/bytes")
                                    .and_then(serde_json::Value::as_u64)
                                    .unwrap(),
                                value
                                    .pointer("/sha256")
                                    .and_then(serde_json::Value::as_str)
                                    .unwrap()
                                    .to_string(),
                            ));
                        }
                        let stop = request.method == "DELETE"
                            || (request.method == "POST" && request.path.ends_with("/finalize"));
                        let response = local_report_response(
                            &server_origin,
                            &request,
                            report_identity.as_ref(),
                        );
                        server_requests.lock().unwrap().push(request);
                        stream.write_all(response.as_bytes()).unwrap();
                        stream.flush().unwrap();
                        if stop {
                            return;
                        }
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(error) => panic!("local report server failed: {error}"),
                }
            }
            panic!("local report server timed out before deletion");
        });
        server_started
            .recv_timeout(Duration::from_secs(2))
            .expect("local report server did not start");
        (origin, requests, server)
    }

    fn read_local_request(stream: &mut TcpStream) -> Option<RecordedRequest> {
        stream
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let mut bytes = Vec::new();
        let mut buffer = [0_u8; 8192];
        let header_end = loop {
            let read = stream.read(&mut buffer).ok()?;
            if read == 0 {
                return None;
            }
            bytes.extend_from_slice(&buffer[..read]);
            if let Some(end) = bytes.windows(4).position(|window| window == b"\r\n\r\n") {
                break end + 4;
            }
            if bytes.len() > 64 * 1024 {
                return None;
            }
        };
        let headers = String::from_utf8(bytes[..header_end].to_vec()).ok()?;
        let mut lines = headers.split("\r\n");
        let mut request_line = lines.next()?.split_whitespace();
        let method = request_line.next()?.to_string();
        let path = request_line.next()?.to_string();
        let mut content_length = 0_usize;
        let mut authorization = None;
        for line in lines {
            let Some((name, value)) = line.split_once(':') else {
                continue;
            };
            if name.eq_ignore_ascii_case("content-length") {
                content_length = value.trim().parse().unwrap_or(0);
            } else if name.eq_ignore_ascii_case("authorization") {
                authorization = Some(value.trim().to_string());
            }
        }
        let mut body = bytes[header_end..].to_vec();
        while body.len() < content_length {
            match stream.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(read) => body.extend_from_slice(&buffer[..read]),
            }
        }
        body.truncate(content_length.min(body.len()));
        Some(RecordedRequest {
            method,
            path,
            authorization,
            body,
        })
    }

    fn local_report_response(
        origin: &Url,
        request: &RecordedRequest,
        report_identity: Option<&(u64, String)>,
    ) -> String {
        let case_id = "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db";
        if request.method == "POST" && request.path == "/v1/cases" {
            let body = serde_json::json!({
                "protocolVersion": 1,
                "caseId": case_id,
                "upload": {
                    "method": "PUT",
                    "url": origin.join(&format!("v1/cases/{case_id}/archive")).unwrap(),
                    "contentType": "application/zip",
                    "expiresAt": "2026-08-09T00:00:00.000Z",
                    "token": "upload.signature"
                },
                "finalize": {
                    "method": "POST",
                    "url": origin.join(&format!("v1/cases/{case_id}/finalize")).unwrap(),
                    "token": "finalize.signature"
                },
                "deletion": {
                    "method": "DELETE",
                    "url": origin.join(&format!("v1/cases/{case_id}")).unwrap(),
                    "token": "delete.signature"
                }
            })
            .to_string();
            return http_response("201 Created", &body);
        }
        if request.method == "PUT" && request.path == format!("/v1/cases/{case_id}/archive") {
            let Some((bytes, sha256)) = report_identity else {
                return http_response("409 Conflict", r#"{"error":"missing identity"}"#);
            };
            if request.body.len() as u64 != *bytes
                || Sha256::digest(&request.body)
                    .iter()
                    .map(|byte| format!("{byte:02x}"))
                    .collect::<String>()
                    != *sha256
            {
                return http_response("499 Client Closed Request", "{}");
            }
            let body = serde_json::json!({
                "status": "uploaded",
                "caseId": case_id,
                "bytes": bytes,
                "sha256": sha256
            })
            .to_string();
            return http_response("200 OK", &body);
        }
        if request.method == "POST" && request.path == format!("/v1/cases/{case_id}/finalize") {
            let Some((bytes, sha256)) = report_identity else {
                return http_response("409 Conflict", r#"{"error":"missing identity"}"#);
            };
            let body = serde_json::json!({
                "protocolVersion": 1,
                "caseId": case_id,
                "objectKey": format!("accepted/{case_id}.zip"),
                "bytes": bytes,
                "sha256": sha256,
                "productVersion": env!("CARGO_PKG_VERSION"),
                "receivedAt": "2026-08-08T00:00:00.000Z",
                "retentionDeadline": "2026-08-22T00:00:00.000Z",
                "deletion": {
                    "method": "DELETE",
                    "url": origin.join(&format!("v1/cases/{case_id}")).unwrap(),
                    "token": "receipt.signature"
                },
                "signature": "signed-receipt"
            })
            .to_string();
            return http_response("200 OK", &body);
        }
        if request.method == "DELETE" && request.path == format!("/v1/cases/{case_id}") {
            return http_response("204 No Content", "");
        }
        http_response("404 Not Found", r#"{"error":"unexpected request"}"#)
    }

    fn http_response(status: &str, body: &str) -> String {
        format!(
            "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        )
    }

    fn successful_status() -> std::process::ExitStatus {
        if cfg!(windows) {
            Command::new("cmd")
                .args(["/d", "/c", "exit", "0"])
                .status()
                .unwrap()
        } else {
            Command::new("sh").args(["-c", "exit 0"]).status().unwrap()
        }
    }

    #[test]
    fn removal_scopes_are_a_closed_product_contract() {
        assert!(validate_removal_scope("launcher").is_ok());
        assert!(validate_removal_scope("all-data").is_ok());
        assert!(validate_removal_scope("cache").is_err());
        assert!(validate_removal_scope("../game").is_err());
    }

    #[test]
    fn parses_only_versioned_preparation_progress() {
        let parsed = parse_preparation_progress(
            "PREFLIGHT_PROGRESS {\"format\":\"preflight-preparation-progress-v1\",\"phase\":\"textures\",\"state\":\"completed\",\"totalPhases\":7,\"status\":\"SUCCESS\",\"durationMs\":12.5,\"metrics\":{\"builtBlobs\":3}}",
            42,
        )
        .unwrap();
        assert_eq!(42, parsed.pid);
        assert_eq!("textures", parsed.phase);
        assert_eq!(Some("SUCCESS".to_string()), parsed.status);
        assert!(parse_preparation_progress("prepare: textures started", 42).is_none());
    }

    #[test]
    fn diagnostics_output_requires_an_absolute_zip_path() {
        let temporary = std::env::temp_dir().canonicalize().unwrap();
        let text = temporary.join("diagnostics.txt");
        let zip = temporary.join("diagnostics.zip");
        assert!(diagnostic_output_path("relative.zip").is_err());
        assert!(diagnostic_output_path(text.to_str().unwrap()).is_err());
        assert_eq!(zip, diagnostic_output_path(zip.to_str().unwrap()).unwrap());
    }

    #[test]
    fn launch_settings_validation_matches_the_engine_contract() {
        let valid = LaunchSettingsInput {
            resolution: "1920x1080".to_string(),
            fullscreen: false,
            sound: true,
            antialiasing_samples: 12,
            ui_scale: 1.25,
            battle_size: 400,
            memory_mib: Some(6144),
        };
        assert!(validate_launch_settings(&valid).is_ok());

        let invalid = LaunchSettingsInput {
            resolution: "1920 by 1080".to_string(),
            ..valid
        };
        assert!(validate_launch_settings(&invalid).is_err());
    }

    #[test]
    fn optimization_presets_are_a_closed_product_contract() {
        assert_eq!(
            "recommended",
            validate_optimization_preset("recommended").unwrap()
        );
        assert_eq!(
            "conservative",
            validate_optimization_preset("conservative").unwrap()
        );
        assert_eq!("off", validate_optimization_preset("off").unwrap());
        assert!(validate_optimization_preset("custom").is_err());
        assert!(validate_optimization_preset("recommended --no-adapter").is_err());
    }

    #[test]
    fn default_updater_endpoint_is_the_fixed_https_release_feed() {
        assert_eq!(DEFAULT_UPDATE_ENDPOINT, compiled_updater_endpoint());
        let endpoint = validated_updater_endpoint(DEFAULT_UPDATE_ENDPOINT).unwrap();
        assert_eq!("https", endpoint.scheme());
        assert_eq!(Some("github.com"), endpoint.host_str());
        assert_eq!(
            "/teamleaderleo/preflight/releases/latest/download/latest.json",
            endpoint.path()
        );
    }

    #[test]
    fn updater_endpoint_rejects_insecure_or_credentialed_urls() {
        assert!(validated_updater_endpoint("http://updates.example.com/latest.json").is_err());
        assert!(
            validated_updater_endpoint("https://token@updates.example.com/latest.json").is_err()
        );
        assert!(
            validated_updater_endpoint("https://updates.example.com/latest.json?client=1").is_err()
        );
        assert!(
            validated_updater_endpoint("https://updates.example.com/latest.json#fragment").is_err()
        );
        assert!(validated_updater_endpoint("https://updates.example.com/latest.json").is_ok());
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_accessibility_link_targets_the_system_privacy_pane() {
        assert_eq!(
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
            MACOS_ACCESSIBILITY_SETTINGS,
        );
    }

    #[test]
    fn mutable_operations_stop_during_update_installation() {
        let mut running = OperationState::default();
        assert!(refuse_update_install(&running).is_ok());
        running.update_installing = true;
        assert!(refuse_update_install(&running).is_err());
    }

    #[test]
    fn update_install_guard_releases_every_error_path() {
        let processes = Mutex::new(OperationState::default());
        {
            let _install = begin_update_install(&processes).unwrap();
            assert!(processes.lock().unwrap().update_installing);
            assert!(begin_update_install(&processes).is_err());
        }
        assert!(!processes.lock().unwrap().update_installing);
        assert!(begin_update_install(&processes).is_ok());
    }
}
