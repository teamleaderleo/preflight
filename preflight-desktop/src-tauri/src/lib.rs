use futures_util::StreamExt;
use reqwest::{Client, Response, StatusCode, redirect::Policy};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::env;
use std::fs::{self, OpenOptions};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::path::BaseDirectory;
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_updater::{Update, UpdaterExt};
use tokio::io::AsyncReadExt;
use tokio::sync::watch;
use url::Url;

const DEFAULT_UPDATE_ENDPOINT: &str =
    "https://github.com/teamleaderleo/preflight/releases/latest/download/latest.json";
#[cfg(target_os = "macos")]
const MACOS_ACCESSIBILITY_SETTINGS: &str =
    "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";
const DESKTOP_SMOKE_CANCELLATION_FILE: &str = "cancel.requested";
const REPORT_INTAKE_ORIGIN: Option<&str> = option_env!("PREFLIGHT_REPORT_INTAKE_ORIGIN");
const REPORT_PROTOCOL_VERSION: u32 = 1;
const REPORT_RESPONSE_LIMIT: usize = 64 * 1024;
const REPORT_UPLOAD_LIMIT: u64 = 6 * 1024 * 1024;
static NEXT_REPORT_UPLOAD_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Default)]
struct ProcessState {
    game: Option<u32>,
    desktop_smoke: Option<DesktopSmokeProcess>,
    preparation: Option<PreparationProcess>,
    report_upload: Option<ReportUploadProcess>,
    update_installing: bool,
    exit_after_cleanup: bool,
}

struct DesktopSmokeProcess {
    pid: u32,
    run_directory: PathBuf,
}

struct PreparationProcess {
    pid: u32,
    cancel: mpsc::Sender<()>,
}

struct ReportUploadProcess {
    id: u64,
    total_bytes: u64,
    cancel: watch::Sender<bool>,
}

struct ProcessTracker(Mutex<ProcessState>);

struct UpdateTracker(Mutex<Option<Update>>);

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

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopSmokeStateEvent {
    state: &'static str,
    pid: u32,
    success: Option<bool>,
    detail: Option<String>,
    run_directory: String,
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

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateStatus {
    format: &'static str,
    configured: bool,
    current_version: String,
    available: bool,
    version: Option<String>,
    date: Option<String>,
    notes: Option<String>,
    reason: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateProgressEvent {
    state: &'static str,
    downloaded_bytes: u64,
    content_length: Option<u64>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReportIntakeStatus {
    configured: bool,
    origin: Option<String>,
    reason: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReportUploadStateEvent {
    state: &'static str,
    upload_id: u64,
    uploaded_bytes: u64,
    total_bytes: u64,
    case_id: Option<String>,
    receipt: Option<ReportReceipt>,
    detail: Option<String>,
}

impl ReportUploadStateEvent {
    fn new(state: &'static str, upload_id: u64, uploaded_bytes: u64, total_bytes: u64) -> Self {
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

    fn with_case(mut self, case_id: String) -> Self {
        self.case_id = Some(case_id);
        self
    }

    fn with_receipt(mut self, receipt: ReportReceipt) -> Self {
        self.receipt = Some(receipt);
        self
    }

    fn with_detail(mut self, detail: String) -> Self {
        self.detail = Some(detail);
        self
    }
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ReportUploadInput {
    output: String,
    bytes: u64,
    sha256: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ReportDeletion {
    method: String,
    url: String,
    token: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ReportReceipt {
    protocol_version: u32,
    case_id: String,
    object_key: String,
    bytes: u64,
    sha256: String,
    product_version: String,
    received_at: String,
    retention_deadline: String,
    deletion: ReportDeletion,
    signature: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CreateReportCaseRequest<'a> {
    protocol_version: u32,
    product_version: &'a str,
    bytes: u64,
    sha256: &'a str,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ReportGrantEndpoint {
    method: String,
    url: String,
    #[serde(default)]
    content_type: Option<String>,
    #[serde(default)]
    expires_at: Option<String>,
    token: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CreateReportCaseResponse {
    protocol_version: u32,
    case_id: String,
    upload: ReportGrantEndpoint,
    finalize: ReportGrantEndpoint,
    deletion: ReportGrantEndpoint,
}

enum ReportUploadError {
    Cancelled,
    Failed(String),
}

struct EnginePaths {
    java: PathBuf,
    jar: PathBuf,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LaunchSettingsInput {
    resolution: String,
    fullscreen: bool,
    sound: bool,
    antialiasing_samples: u8,
    ui_scale: f64,
    battle_size: u32,
}

impl EnginePaths {
    fn resolve(app: &AppHandle) -> Result<Self, String> {
        let bundled_jar = || {
            app.path()
                .resolve("engine/preflight.jar", BaseDirectory::Resource)
                .ok()
                .filter(|path| path.is_file())
        };
        #[cfg(debug_assertions)]
        let jar = env::var_os("PREFLIGHT_DESKTOP_JAR")
            .map(PathBuf::from)
            .filter(|path| path.is_file())
            .or_else(bundled_jar)
            .or_else(development_jar);
        #[cfg(not(debug_assertions))]
        let jar = bundled_jar();
        let jar = jar.ok_or_else(|| {
            "The bundled Preflight engine is missing. Reinstall Preflight.".to_string()
        })?;

        #[cfg(debug_assertions)]
        let java = env::var_os("PREFLIGHT_DESKTOP_JAVA")
            .map(PathBuf::from)
            .filter(|path| path.is_file())
            .or_else(|| bundled_java(app))
            .unwrap_or_else(system_java);
        #[cfg(not(debug_assertions))]
        let java = bundled_java(app).ok_or_else(|| {
            "The bundled Preflight runtime is missing. Reinstall Preflight.".to_string()
        })?;

        Ok(Self { java, jar })
    }

    fn command(&self) -> Command {
        let mut command = Command::new(&self.java);
        command.arg("-jar").arg(&self.jar);
        configure_child_process(&mut command);
        command
    }
}

#[cfg(debug_assertions)]
fn development_jar() -> Option<PathBuf> {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../preflight-cli/target/preflight.jar")
        .canonicalize()
        .ok()
        .filter(|path| path.is_file())
}

fn bundled_java(app: &AppHandle) -> Option<PathBuf> {
    let executable = if cfg!(windows) { "javaw.exe" } else { "java" };
    app.path()
        .resolve(
            format!("engine/runtime/bin/{executable}"),
            BaseDirectory::Resource,
        )
        .ok()
        .filter(|path| path.is_file())
}

#[cfg(debug_assertions)]
fn system_java() -> PathBuf {
    if let Some(java_home) = env::var_os("JAVA_HOME") {
        let executable = if cfg!(windows) { "java.exe" } else { "java" };
        let candidate = PathBuf::from(java_home).join("bin").join(executable);
        if candidate.is_file() {
            return candidate;
        }
    }
    PathBuf::from(if cfg!(windows) { "java.exe" } else { "java" })
}

fn canonical_game_directory(game: &str) -> Result<PathBuf, String> {
    let path = Path::new(game);
    let canonical = path
        .canonicalize()
        .map_err(|error| format!("Could not open the selected game folder: {error}"))?;
    if !canonical.is_dir() {
        return Err("The selected Starsector location is not a folder.".to_string());
    }
    Ok(canonical)
}

#[tauri::command]
fn get_snapshot(app: AppHandle, game: Option<String>) -> Result<Value, String> {
    let paths = EnginePaths::resolve(&app)?;
    let mut command = paths.command();
    command.arg("desktop").arg("snapshot");
    if let Some(game) = game {
        let directory = canonical_game_directory(&game)?;
        command.arg("--game").arg(directory);
    }

    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() {
        return Err(child_error(
            "Preflight could not inspect the installation",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned an unreadable desktop snapshot: {error}"))
}

#[tauri::command]
fn get_desktop_smoke_probe(app: AppHandle) -> Result<Value, String> {
    let paths = EnginePaths::resolve(&app)?;
    let mut command = paths.command();
    command.arg("desktop").arg("smoke").arg("probe");
    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() {
        return Err(child_error(
            "Preflight could not inspect desktop-test readiness",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned an unreadable desktop-test probe: {error}"))
}

#[tauri::command]
fn open_desktop_accessibility_settings() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let status = Command::new("/usr/bin/open")
            .arg(MACOS_ACCESSIBILITY_SETTINGS)
            .status()
            .map_err(|error| format!("Could not open macOS Accessibility settings: {error}"))?;
        if status.success() {
            Ok(())
        } else {
            Err(format!(
                "macOS could not open Accessibility settings (exit {}).",
                status.code().unwrap_or(-1)
            ))
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        Err("Accessibility settings are available only on macOS.".to_string())
    }
}

fn desktop_smoke_scenario(app: &AppHandle) -> Result<PathBuf, String> {
    let scenario = app
        .path()
        .resolve(
            "engine/scenarios/campaign-roam.json",
            BaseDirectory::Resource,
        )
        .map_err(|error| format!("Could not resolve the automated-test scenario: {error}"))?;
    if !scenario.is_file() {
        return Err(
            "The packaged automated-test scenario is missing. Reinstall Preflight.".to_string(),
        );
    }
    Ok(scenario)
}

fn desktop_smoke_run_directory(app: &AppHandle) -> Result<PathBuf, String> {
    let home = app
        .path()
        .home_dir()
        .map_err(|error| format!("Could not locate the home directory: {error}"))?;
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "The system clock is before 1970.".to_string())?
        .as_millis();
    Ok(home
        .join(".starsector-preflight")
        .join("runs")
        .join(format!("desktop-smoke-{timestamp}-{}", std::process::id())))
}

#[tauri::command]
fn start_desktop_smoke(
    app: AppHandle,
    tracker: State<'_, ProcessTracker>,
    game: String,
) -> Result<RunStarted, String> {
    let directory = canonical_game_directory(&game)?;
    let scenario = desktop_smoke_scenario(&app)?;
    let run_directory = desktop_smoke_run_directory(&app)?;
    fs::create_dir_all(&run_directory)
        .map_err(|error| format!("Could not create the automated-test run folder: {error}"))?;
    let run_directory = run_directory
        .canonicalize()
        .map_err(|error| format!("Could not open the automated-test run folder: {error}"))?;
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
            "Wait for profile preparation to finish before running the automated test.".to_string(),
        );
    }

    let mut command = paths.command();
    command
        .arg("desktop")
        .arg("smoke")
        .arg("launch")
        .arg(scenario)
        .arg(&run_directory)
        .arg("--game")
        .arg(directory)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let child = command
        .spawn()
        .map_err(|error| format!("Could not start the automated game test: {error}"))?;
    let pid = child.id();
    running.game = Some(pid);
    running.desktop_smoke = Some(DesktopSmokeProcess {
        pid,
        run_directory: run_directory.clone(),
    });
    drop(running);

    let _ = app.emit(
        "desktop-smoke-state",
        DesktopSmokeStateEvent {
            state: "started",
            pid,
            success: None,
            detail: None,
            run_directory: run_directory.to_string_lossy().into_owned(),
        },
    );
    watch_desktop_smoke(app, child, run_directory);
    Ok(RunStarted { pid })
}

fn request_desktop_smoke_cancellation(process: &DesktopSmokeProcess) -> Result<bool, String> {
    let marker = process.run_directory.join(DESKTOP_SMOKE_CANCELLATION_FILE);
    match OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&marker)
    {
        Ok(mut file) => {
            file.write_all(b"cancel\n").map_err(|error| {
                format!("Could not write the automated-test stop request: {error}")
            })?;
            Ok(true)
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            let metadata = marker.symlink_metadata().map_err(|problem| {
                format!("Could not inspect the automated-test stop request: {problem}")
            })?;
            if metadata.file_type().is_symlink() || !metadata.is_file() {
                Err("The automated-test stop request isn't a regular file.".to_string())
            } else {
                Ok(false)
            }
        }
        Err(error) => Err(format!(
            "Could not request a safe stop for the automated game test: {error}"
        )),
    }
}

fn desktop_smoke_cancellation_requested(run_directory: &Path) -> bool {
    let marker = run_directory.join(DESKTOP_SMOKE_CANCELLATION_FILE);
    marker
        .symlink_metadata()
        .is_ok_and(|metadata| metadata.is_file() && !metadata.file_type().is_symlink())
}

#[tauri::command]
fn cancel_desktop_smoke(
    app: AppHandle,
    tracker: State<'_, ProcessTracker>,
) -> Result<bool, String> {
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The launch tracker is unavailable.".to_string())?;
    let Some(process) = running.desktop_smoke.as_ref() else {
        return Ok(false);
    };
    request_desktop_smoke_cancellation(process)?;
    let pid = process.pid;
    let run_directory = process.run_directory.to_string_lossy().into_owned();
    drop(running);
    let _ = app.emit(
        "desktop-smoke-state",
        DesktopSmokeStateEvent {
            state: "cancelling",
            pid,
            success: None,
            detail: Some("Stopping the exact game process and sealing its evidence…".to_string()),
            run_directory,
        },
    );
    Ok(true)
}

#[tauri::command]
fn get_cache(app: AppHandle, game: String) -> Result<Value, String> {
    let directory = canonical_game_directory(&game)?;
    let paths = EnginePaths::resolve(&app)?;
    let mut command = paths.command();
    command
        .arg("cache")
        .arg("--json")
        .arg("--game")
        .arg(directory);
    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() {
        return Err(child_error(
            "Preflight could not inspect its cache",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned an unreadable cache snapshot: {error}"))
}

#[tauri::command]
fn get_cache_cleanup(app: AppHandle, game: String) -> Result<Value, String> {
    cache_cleanup_json(&app, &game, false)
}

#[tauri::command]
fn apply_cache_cleanup(
    app: AppHandle,
    tracker: State<'_, ProcessTracker>,
    game: String,
) -> Result<Value, String> {
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The process tracker is unavailable.".to_string())?;
    refuse_update_install(&running)?;
    if running.game.is_some() {
        return Err("Close Starsector before cleaning acceleration data.".to_string());
    }
    if running.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before cleaning acceleration data.".to_string(),
        );
    }
    let result = cache_cleanup_json(&app, &game, true);
    drop(running);
    result
}

fn cache_cleanup_json(app: &AppHandle, game: &str, apply: bool) -> Result<Value, String> {
    let directory = canonical_game_directory(game)?;
    let paths = EnginePaths::resolve(app)?;
    let mut command = paths.command();
    command
        .arg("cache")
        .arg("prune")
        .arg("--json")
        .arg("--keep-named")
        .arg("--game")
        .arg(directory);
    if apply {
        command.arg("--yes");
    }
    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() && output.status.code() != Some(3) {
        return Err(child_error(
            "Preflight could not plan cache cleanup",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned an unreadable cleanup plan: {error}"))
}

fn validate_removal_scope(scope: &str) -> Result<(), String> {
    match scope {
        "launcher" | "all-data" => Ok(()),
        _ => Err("Removal scope must be launcher or all-data.".to_string()),
    }
}

#[tauri::command]
fn get_removal_plan(app: AppHandle, scope: String) -> Result<Value, String> {
    removal_json(&app, &scope, false)
}

#[tauri::command]
fn apply_removal(
    app: AppHandle,
    tracker: State<'_, ProcessTracker>,
    scope: String,
) -> Result<Value, String> {
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The process tracker is unavailable.".to_string())?;
    refuse_update_install(&running)?;
    if running.game.is_some() {
        return Err("Close Starsector before removing Preflight files.".to_string());
    }
    if running.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before removing Preflight files.".to_string(),
        );
    }
    let result = removal_json(&app, &scope, true);
    drop(running);
    result
}

fn removal_json(app: &AppHandle, scope: &str, apply: bool) -> Result<Value, String> {
    validate_removal_scope(scope)?;
    let paths = EnginePaths::resolve(app)?;
    let mut command = paths.command();
    command
        .arg("uninstall")
        .arg("--scope")
        .arg(scope)
        .arg("--json");
    if apply {
        command.arg("--yes");
    }
    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() {
        return Err(child_error(
            "Preflight could not apply the removal plan",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned an unreadable removal plan: {error}"))
}

fn compiled_updater_public_key() -> Option<&'static str> {
    option_env!("PREFLIGHT_UPDATER_PUBLIC_KEY")
        .map(str::trim)
        .filter(|key| !key.is_empty())
}

fn compiled_updater_endpoint() -> &'static str {
    option_env!("PREFLIGHT_UPDATER_ENDPOINT")
        .map(str::trim)
        .filter(|endpoint| !endpoint.is_empty())
        .unwrap_or(DEFAULT_UPDATE_ENDPOINT)
}

fn validated_updater_endpoint(endpoint: &str) -> Result<Url, String> {
    let url = Url::parse(endpoint)
        .map_err(|error| format!("The compiled update endpoint is invalid: {error}"))?;
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.host_str().is_none()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(
            "The compiled update endpoint must be an absolute HTTPS URL without credentials, a query, or a fragment."
                .to_string(),
        );
    }
    Ok(url)
}

fn updater_platform_reason() -> Option<&'static str> {
    #[cfg(target_os = "linux")]
    if env::var_os("APPIMAGE").is_none() {
        return Some(
            "Built-in updates are available in the AppImage. Update this package with the same package manager used to install it.",
        );
    }
    None
}

fn updater_disabled(app: &AppHandle, reason: impl Into<String>) -> UpdateStatus {
    UpdateStatus {
        format: "preflight-update-v1",
        configured: false,
        current_version: app.package_info().version.to_string(),
        available: false,
        version: None,
        date: None,
        notes: None,
        reason: Some(reason.into()),
    }
}

#[tauri::command]
async fn check_for_update(
    app: AppHandle,
    updates: State<'_, UpdateTracker>,
) -> Result<UpdateStatus, String> {
    if let Some(reason) = updater_platform_reason() {
        return Ok(updater_disabled(&app, reason));
    }
    let Some(public_key) = compiled_updater_public_key() else {
        return Ok(updater_disabled(
            &app,
            "This development build has no updater verification key.",
        ));
    };
    let endpoint = validated_updater_endpoint(compiled_updater_endpoint())?;
    let updater = app
        .updater_builder()
        .pubkey(public_key)
        .endpoints(vec![endpoint])
        .map_err(|error| format!("Could not configure verified updates: {error}"))?
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|error| format!("Could not initialize verified updates: {error}"))?;
    let update = updater
        .check()
        .await
        .map_err(|error| format!("Could not check for a verified update: {error}"))?;
    let status = match update.as_ref() {
        Some(update) => UpdateStatus {
            format: "preflight-update-v1",
            configured: true,
            current_version: update.current_version.clone(),
            available: true,
            version: Some(update.version.clone()),
            date: update.date.map(|date| date.to_string()),
            notes: update.body.clone(),
            reason: None,
        },
        None => UpdateStatus {
            format: "preflight-update-v1",
            configured: true,
            current_version: app.package_info().version.to_string(),
            available: false,
            version: None,
            date: None,
            notes: None,
            reason: None,
        },
    };
    *updates
        .0
        .lock()
        .map_err(|_| "The update tracker is unavailable.".to_string())? = update;
    Ok(status)
}

async fn current_verified_update(app: &AppHandle) -> Result<Option<Update>, String> {
    let public_key = compiled_updater_public_key()
        .ok_or_else(|| "This build has no updater verification key.".to_string())?;
    let endpoint = validated_updater_endpoint(compiled_updater_endpoint())?;
    app.updater_builder()
        .pubkey(public_key)
        .endpoints(vec![endpoint])
        .map_err(|error| format!("Could not configure verified updates: {error}"))?
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|error| format!("Could not initialize verified updates: {error}"))?
        .check()
        .await
        .map_err(|error| format!("Could not recheck the verified update: {error}"))
}

fn same_update_offer(left: &Update, right: &Update) -> bool {
    left.current_version == right.current_version
        && left.version == right.version
        && left.target == right.target
        && left.download_url == right.download_url
        && left.signature == right.signature
        && left.body == right.body
        && left.date == right.date
}

#[tauri::command]
async fn install_update(
    app: AppHandle,
    processes: State<'_, ProcessTracker>,
    updates: State<'_, UpdateTracker>,
    requested_version: String,
) -> Result<(), String> {
    {
        let mut running = processes
            .0
            .lock()
            .map_err(|_| "The process tracker is unavailable.".to_string())?;
        if running.game.is_some() {
            return Err("Close Starsector before installing a Preflight update.".to_string());
        }
        if running.preparation.is_some() {
            return Err(
                "Wait for profile preparation to finish before installing an update.".to_string(),
            );
        }
        if running.update_installing {
            return Err("A Preflight update is already being installed.".to_string());
        }
        running.update_installing = true;
    }

    let displayed_update = updates
        .0
        .lock()
        .map_err(|_| "The update tracker is unavailable.".to_string())?
        .take();
    let Some(displayed_update) = displayed_update else {
        processes
            .0
            .lock()
            .map_err(|_| "The process tracker is unavailable.".to_string())?
            .update_installing = false;
        return Err("Check for an update before trying to install one.".to_string());
    };
    if displayed_update.version != requested_version {
        processes
            .0
            .lock()
            .map_err(|_| "The process tracker is unavailable.".to_string())?
            .update_installing = false;
        return Err("The selected update changed. Check again before installing.".to_string());
    }

    let refreshed = current_verified_update(&app).await;
    let update = match refreshed {
        Ok(Some(update)) if same_update_offer(&displayed_update, &update) => update,
        Ok(_) => {
            processes
                .0
                .lock()
                .map_err(|_| "The process tracker is unavailable.".to_string())?
                .update_installing = false;
            return Err(
                "That update is no longer the exact release currently offered. Check again before installing."
                    .to_string(),
            );
        }
        Err(error) => {
            processes
                .0
                .lock()
                .map_err(|_| "The process tracker is unavailable.".to_string())?
                .update_installing = false;
            return Err(error);
        }
    };

    let progress_app = app.clone();
    let finished_app = app.clone();
    let downloaded_bytes = Arc::new(AtomicU64::new(0));
    let progress_bytes = downloaded_bytes.clone();
    let finished_bytes = downloaded_bytes.clone();
    let result = update
        .download_and_install(
            move |chunk_length, content_length| {
                let total = progress_bytes
                    .fetch_add(chunk_length as u64, Ordering::Relaxed)
                    .saturating_add(chunk_length as u64);
                let _ = progress_app.emit(
                    "update-progress",
                    UpdateProgressEvent {
                        state: "downloading",
                        downloaded_bytes: total,
                        content_length,
                    },
                );
            },
            move || {
                let _ = finished_app.emit(
                    "update-progress",
                    UpdateProgressEvent {
                        state: "downloaded",
                        downloaded_bytes: finished_bytes.load(Ordering::Relaxed),
                        content_length: None,
                    },
                );
            },
        )
        .await;

    if let Err(error) = result {
        processes
            .0
            .lock()
            .map_err(|_| "The process tracker is unavailable.".to_string())?
            .update_installing = false;
        *updates
            .0
            .lock()
            .map_err(|_| "The update tracker is unavailable.".to_string())? = Some(update);
        return Err(format!(
            "The verified update could not be installed; this version is unchanged: {error}"
        ));
    }

    let _ = app.emit(
        "update-progress",
        UpdateProgressEvent {
            state: "installed",
            downloaded_bytes: downloaded_bytes.load(Ordering::Relaxed),
            content_length: None,
        },
    );
    app.restart();
}

fn refuse_update_install(running: &ProcessState) -> Result<(), String> {
    if running.update_installing {
        Err("Wait for the Preflight update to finish installing.".to_string())
    } else {
        Ok(())
    }
}

fn diagnostic_output_path(output: &str) -> Result<PathBuf, String> {
    let requested = PathBuf::from(output);
    if !requested.is_absolute() {
        return Err("Choose an absolute location for the diagnostics ZIP.".to_string());
    }
    if !requested
        .extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| extension.eq_ignore_ascii_case("zip"))
    {
        return Err("The diagnostics filename must end in .zip.".to_string());
    }
    let parent = requested
        .parent()
        .ok_or_else(|| "The diagnostics location has no parent folder.".to_string())?
        .canonicalize()
        .map_err(|error| format!("Could not open the diagnostics folder: {error}"))?;
    if !parent.is_dir() {
        return Err("The diagnostics location is not inside a folder.".to_string());
    }
    let name = requested
        .file_name()
        .ok_or_else(|| "The diagnostics filename is missing.".to_string())?;
    let destination = parent.join(name);
    if destination
        .symlink_metadata()
        .is_ok_and(|metadata| metadata.file_type().is_symlink())
    {
        return Err("Refusing to replace a symbolic link with diagnostics.".to_string());
    }
    if destination.exists() && !destination.is_file() {
        return Err("The selected diagnostics location is not a file.".to_string());
    }
    Ok(destination)
}

#[tauri::command]
fn export_diagnostics(app: AppHandle, output: String) -> Result<Value, String> {
    let destination = diagnostic_output_path(&output)?;
    let paths = EnginePaths::resolve(&app)?;
    let mut command = paths.command();
    command
        .arg("evidence")
        .arg("export")
        .arg("--output")
        .arg(destination)
        .arg("--overwrite")
        .arg("--json");
    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() {
        return Err(child_error(
            "Preflight could not export diagnostics",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned an unreadable diagnostics receipt: {error}"))
}

#[tauri::command]
fn get_report_intake_status() -> ReportIntakeStatus {
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
async fn send_run_report(
    app: AppHandle,
    tracker: State<'_, ProcessTracker>,
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

    let outcome = perform_report_upload(
        app.clone(),
        client,
        origin,
        archive,
        report.clone(),
        id,
        cancel_receiver,
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
fn cancel_run_report(app: AppHandle, tracker: State<'_, ProcessTracker>) -> Result<bool, String> {
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
async fn delete_run_report(deletion: ReportDeletion) -> Result<bool, String> {
    let origin = configured_report_origin()?;
    if deletion.method != "DELETE" {
        return Err("The report receipt has an invalid deletion method.".to_string());
    }
    let url = validated_deletion_url(&origin, &deletion.url)?;
    validate_report_token(&deletion.token)?;
    let response = report_client()?
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

async fn perform_report_upload(
    app: AppHandle,
    client: Client,
    origin: Url,
    archive: PathBuf,
    report: ReportUploadInput,
    id: u64,
    mut cancel: watch::Receiver<bool>,
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

    emit_report_state(
        &app,
        ReportUploadStateEvent::new("uploading", id, 0, report.bytes)
            .with_case(grant.case_id.clone()),
    );
    let mut stream_cancel = cancel.clone();
    let stream_app = app.clone();
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
            emit_report_state(
                &stream_app,
                ReportUploadStateEvent::new("uploading", id, uploaded, total)
                    .with_case(case_id.clone()),
            );
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

    emit_report_state(
        &app,
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

fn configured_report_origin() -> Result<Url, String> {
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

fn report_client() -> Result<Client, String> {
    Client::builder()
        .redirect(Policy::none())
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(180))
        .user_agent(format!("Preflight/{}", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|error| format!("Could not configure the report client: {error}"))
}

fn validated_report_archive(report: &ReportUploadInput) -> Result<PathBuf, String> {
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

fn emit_report_state(app: &AppHandle, event: ReportUploadStateEvent) {
    let _ = app.emit("report-upload-state", event);
}

#[tauri::command]
fn get_launch_settings(app: AppHandle, game: String) -> Result<Value, String> {
    let directory = canonical_game_directory(&game)?;
    launch_settings_json(&app, &directory, None)
}

#[tauri::command]
fn update_launch_settings(
    app: AppHandle,
    tracker: State<'_, ProcessTracker>,
    game: String,
    settings: LaunchSettingsInput,
) -> Result<Value, String> {
    let directory = canonical_game_directory(&game)?;
    validate_launch_settings(&settings)?;
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The process tracker is unavailable.".to_string())?;
    refuse_update_install(&running)?;
    if running.game.is_some() {
        return Err("Close Starsector before changing its launch settings.".to_string());
    }
    if running.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before changing launch settings.".to_string(),
        );
    }
    let result = launch_settings_json(&app, &directory, Some(&settings));
    drop(running);
    result
}

fn launch_settings_json(
    app: &AppHandle,
    directory: &Path,
    settings: Option<&LaunchSettingsInput>,
) -> Result<Value, String> {
    let paths = EnginePaths::resolve(app)?;
    let mut command = paths.command();
    command.arg("launch-settings");
    if let Some(settings) = settings {
        command
            .arg("set")
            .arg("--resolution")
            .arg(&settings.resolution)
            .arg("--fullscreen")
            .arg(settings.fullscreen.to_string())
            .arg("--sound")
            .arg(settings.sound.to_string())
            .arg("--antialiasing")
            .arg(settings.antialiasing_samples.to_string())
            .arg("--ui-scale")
            .arg(settings.ui_scale.to_string())
            .arg("--battle-size")
            .arg(settings.battle_size.to_string());
    }
    command.arg("--game").arg(directory).arg("--json");
    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() {
        return Err(child_error(
            "Preflight could not update Starsector's launch settings",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned unreadable launch settings: {error}"))
}

fn validate_launch_settings(settings: &LaunchSettingsInput) -> Result<(), String> {
    let axes: Vec<&str> = settings.resolution.split('x').collect();
    if axes.len() != 2
        || axes.iter().any(|axis| {
            axis.parse::<u16>()
                .map(|value| value == 0 || value.to_string() != *axis)
                .unwrap_or(true)
        })
    {
        return Err("Resolution must be WIDTHxHEIGHT using positive whole numbers.".to_string());
    }
    if ![0, 2, 4, 8, 12, 16, 24, 32].contains(&settings.antialiasing_samples) {
        return Err("Choose one of Starsector's supported antialiasing sample counts.".to_string());
    }
    let scaled = settings.ui_scale * 20.0;
    if !settings.ui_scale.is_finite()
        || !(1.0..=3.0).contains(&settings.ui_scale)
        || (scaled - scaled.round()).abs() > 0.000_001
    {
        return Err("UI scale must be from 1.00 to 3.00 in 0.05 steps.".to_string());
    }
    if settings.battle_size == 0 {
        return Err("Battle size must be positive.".to_string());
    }
    Ok(())
}

#[tauri::command]
fn get_profiles(app: AppHandle, game: String) -> Result<Value, String> {
    profile_json(&app, &game, &["profile", "list"], false)
}

#[tauri::command]
fn save_profile(app: AppHandle, game: String, name: String) -> Result<Value, String> {
    profile_json(&app, &game, &["profile", "save", name.as_str()], false)
}

#[tauri::command]
fn activate_profile(
    app: AppHandle,
    tracker: State<'_, ProcessTracker>,
    game: String,
    name: String,
    confirmed: bool,
) -> Result<Value, String> {
    if !confirmed {
        return profile_json(&app, &game, &["profile", "activate", name.as_str()], true);
    }
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The process tracker is unavailable.".to_string())?;
    refuse_update_install(&running)?;
    if running.game.is_some() {
        return Err("Close Starsector before switching mod profiles.".to_string());
    }
    if running.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before switching profiles.".to_string(),
        );
    }
    let result = profile_json(
        &app,
        &game,
        &["profile", "activate", name.as_str(), "--yes"],
        true,
    );
    drop(running);
    result
}

fn profile_json(
    app: &AppHandle,
    game: &str,
    arguments: &[&str],
    accepts_refusal: bool,
) -> Result<Value, String> {
    let directory = canonical_game_directory(game)?;
    let paths = EnginePaths::resolve(app)?;
    let mut command = paths.command();
    command
        .args(arguments)
        .arg("--game")
        .arg(directory)
        .arg("--json");
    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() && !(accepts_refusal && output.status.code() == Some(2)) {
        return Err(child_error(
            "Preflight could not manage named profiles",
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned unreadable profile data: {error}"))
}

#[tauri::command]
fn start_game(
    app: AppHandle,
    tracker: State<'_, ProcessTracker>,
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
    tracker: State<'_, ProcessTracker>,
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
fn cancel_preparation(app: AppHandle, tracker: State<'_, ProcessTracker>) -> Result<bool, String> {
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
        if let Ok(mut running) = app.state::<ProcessTracker>().0.lock() {
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

fn watch_desktop_smoke(app: AppHandle, mut child: Child, run_directory: PathBuf) {
    let pid = child.id();
    std::thread::spawn(move || {
        let stdout = child
            .stdout
            .take()
            .map(|mut stdout| std::thread::spawn(move || read_tail(&mut stdout, 256 * 1024)));
        let stderr = child
            .stderr
            .take()
            .map(|mut stderr| std::thread::spawn(move || read_tail(&mut stderr, 16 * 1024)));
        let status = child.wait();
        let stdout = stdout
            .and_then(|reader| reader.join().ok())
            .unwrap_or_default();
        let stderr = stderr
            .and_then(|reader| reader.join().ok())
            .unwrap_or_default();
        let cancellation_requested = desktop_smoke_cancellation_requested(&run_directory);
        let (success, detail) = if cancellation_requested {
            desktop_smoke_cancellation_outcome(&status, &stdout, &stderr)
        } else {
            desktop_smoke_outcome(&status, &stdout, &stderr)
        };
        let should_exit = if let Ok(mut running) = app.state::<ProcessTracker>().0.lock() {
            if running.game == Some(pid) {
                running.game = None;
            }
            if running
                .desktop_smoke
                .as_ref()
                .is_some_and(|process| process.pid == pid)
            {
                running.desktop_smoke = None;
            }
            if cancellation_requested && !success {
                running.exit_after_cleanup = false;
                false
            } else {
                take_deferred_exit(&mut running)
            }
        } else {
            false
        };
        let _ = app.emit(
            "desktop-smoke-state",
            DesktopSmokeStateEvent {
                state: if cancellation_requested && success {
                    "cancelled"
                } else {
                    "finished"
                },
                pid,
                success: Some(success),
                detail,
                run_directory: run_directory.to_string_lossy().into_owned(),
            },
        );
        if should_exit {
            app.exit(0);
        }
    });
}

fn desktop_smoke_cancellation_outcome(
    process_status: &std::io::Result<std::process::ExitStatus>,
    stdout: &[u8],
    stderr: &[u8],
) -> (bool, Option<String>) {
    let receipt: Value = match serde_json::from_slice(stdout) {
        Ok(receipt) => receipt,
        Err(_) => return desktop_smoke_outcome(process_status, stdout, stderr),
    };
    if receipt.pointer("/launch/status").and_then(Value::as_str) == Some("cancelled") {
        return (
            true,
            Some("Automated game test stopped safely after exact-process cleanup.".to_string()),
        );
    }
    desktop_smoke_outcome(process_status, stdout, stderr)
}

fn take_deferred_exit(running: &mut ProcessState) -> bool {
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

fn begin_exit_cleanup(running: &mut ProcessState) -> Result<bool, String> {
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

fn desktop_smoke_outcome(
    process_status: &std::io::Result<std::process::ExitStatus>,
    stdout: &[u8],
    stderr: &[u8],
) -> (bool, Option<String>) {
    let receipt: Value = match serde_json::from_slice(stdout) {
        Ok(receipt) => receipt,
        Err(error) => {
            let detail = match process_status {
                Ok(_) => child_error(
                    &format!("Preflight returned an unreadable automated-test result: {error}"),
                    stderr,
                ),
                Err(wait) => format!("Could not wait for the automated game test: {wait}"),
            };
            return (false, Some(detail));
        }
    };
    let status = receipt
        .pointer("/launch/status")
        .and_then(Value::as_str)
        .unwrap_or("failed");
    let process_success = process_status
        .as_ref()
        .is_ok_and(|process_status| process_status.success());
    if process_success && status == "passed" {
        return (true, None);
    }
    let diagnostic = receipt
        .pointer("/launch/diagnostics/0")
        .or_else(|| receipt.pointer("/launch/evidence/diagnostics/0"))
        .and_then(Value::as_str);
    let detail = diagnostic
        .map(str::to_string)
        .unwrap_or_else(|| child_error(&format!("Automated game test {status}"), stderr));
    (false, Some(detail))
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
        let should_exit = if let Ok(mut running) = app.state::<ProcessTracker>().0.lock() {
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

fn child_error(context: &str, stderr: &[u8]) -> String {
    let details = String::from_utf8_lossy(stderr);
    let details = details.trim();
    if details.is_empty() {
        context.to_string()
    } else {
        format!("{context}: {details}")
    }
}

#[cfg(target_os = "windows")]
fn configure_child_process(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(target_os = "windows"))]
fn configure_child_process(_command: &mut Command) {}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(ProcessTracker(Mutex::new(ProcessState::default())))
        .manage(UpdateTracker(Mutex::new(None)))
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
            .state::<ProcessTracker>()
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
        DEFAULT_UPDATE_ENDPOINT, DESKTOP_SMOKE_CANCELLATION_FILE, DesktopSmokeProcess,
        LaunchSettingsInput, PreparationProcess, ProcessState, ReportDeletion, ReportReceipt,
        ReportUploadInput, ReportUploadProcess, begin_exit_cleanup, compiled_updater_endpoint,
        desktop_smoke_cancellation_outcome, desktop_smoke_cancellation_requested,
        desktop_smoke_outcome, diagnostic_output_path, parse_preparation_progress, read_tail,
        refuse_update_install, take_deferred_exit, validate_launch_settings,
        validate_optimization_preset, validate_removal_scope, validate_report_origin,
        validate_report_receipt, validated_case_url, validated_report_archive,
        validated_updater_endpoint,
    };
    use std::fs;
    use std::io::Cursor;
    use std::process::Command;
    use std::sync::mpsc;
    use std::time::{SystemTime, UNIX_EPOCH};
    use tokio::sync::watch;

    #[cfg(target_os = "macos")]
    use super::MACOS_ACCESSIBILITY_SETTINGS;

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
        let mut running = ProcessState {
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
        let mut running = ProcessState::default();
        assert!(refuse_update_install(&running).is_ok());
        running.update_installing = true;
        assert!(refuse_update_install(&running).is_err());
    }
}
