use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::env;
use std::io::{BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Mutex, mpsc};
use std::time::Duration;
use tauri::path::BaseDirectory;
use tauri::{AppHandle, Emitter, Manager, State};

#[derive(Default)]
struct ProcessState {
    game: Option<u32>,
    preparation: Option<PreparationProcess>,
}

struct PreparationProcess {
    pid: u32,
    cancel: mpsc::Sender<()>,
}

struct ProcessTracker(Mutex<ProcessState>);

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
        if let Ok(mut running) = app.state::<ProcessTracker>().0.lock() {
            if running
                .preparation
                .as_ref()
                .is_some_and(|process| process.pid == pid)
            {
                running.preparation = None;
            }
        }
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
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(ProcessTracker(Mutex::new(ProcessState::default())))
        .invoke_handler(tauri::generate_handler![
            get_snapshot,
            get_cache,
            get_cache_cleanup,
            apply_cache_cleanup,
            export_diagnostics,
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
        .run(tauri::generate_context!())
        .expect("error while running Preflight");
}

#[cfg(test)]
mod tests {
    use super::{
        LaunchSettingsInput, diagnostic_output_path, parse_preparation_progress, read_tail,
        validate_launch_settings, validate_optimization_preset,
    };
    use std::io::Cursor;

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
}
