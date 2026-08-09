use crate::child_error;
use crate::operations::{OperationCoordinator, OperationState, refuse_update_install};
use serde::Deserialize;
use serde_json::Value;
#[cfg(debug_assertions)]
use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;
use tauri::path::BaseDirectory;
use tauri::{AppHandle, Manager, State};

pub(crate) struct EnginePaths {
    java: PathBuf,
    jar: PathBuf,
}

impl EnginePaths {
    pub(crate) fn resolve(app: &AppHandle) -> Result<Self, String> {
        let bundled_jar = || bundled_resource(app, Path::new("engine/preflight.jar"));
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

    pub(crate) fn command(&self) -> Command {
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
    bundled_resource(
        app,
        Path::new("engine/runtime/bin").join(executable).as_path(),
    )
}

fn bundled_resource(app: &AppHandle, relative: &Path) -> Option<PathBuf> {
    app.path()
        .resolve(relative, BaseDirectory::Resource)
        .ok()
        .filter(|path| path.is_file())
        .or_else(|| macos_bundle_resource(relative))
}

#[cfg(target_os = "macos")]
fn macos_bundle_resource(relative: &Path) -> Option<PathBuf> {
    let executable = std::env::current_exe().ok()?.canonicalize().ok()?;
    let macos = executable.parent()?;
    if macos.file_name()? != "MacOS" {
        return None;
    }
    let contents = macos.parent()?;
    if contents.file_name()? != "Contents" {
        return None;
    }
    let resources = contents.join("Resources").canonicalize().ok()?;
    let candidate = resources.join(relative).canonicalize().ok()?;
    candidate
        .starts_with(&resources)
        .then_some(candidate)
        .filter(|path| path.is_file())
}

#[cfg(not(target_os = "macos"))]
fn macos_bundle_resource(_relative: &Path) -> Option<PathBuf> {
    None
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

pub(crate) fn canonical_game_directory(game: &str) -> Result<PathBuf, String> {
    let path = Path::new(game);
    let canonical = path
        .canonicalize()
        .map_err(|error| format!("Could not open the selected game folder: {error}"))?;
    if !canonical.is_dir() {
        return Err("The selected Starsector location is not a folder.".to_string());
    }
    Ok(canonical)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LaunchSettingsInput {
    pub(crate) resolution: String,
    pub(crate) fullscreen: bool,
    pub(crate) sound: bool,
    pub(crate) antialiasing_samples: u8,
    pub(crate) ui_scale: f64,
    pub(crate) battle_size: u32,
    #[serde(rename = "memoryMiB")]
    pub(crate) memory_mib: Option<u32>,
}

#[tauri::command]
pub(crate) fn get_launch_settings(app: AppHandle, game: String) -> Result<Value, String> {
    let directory = canonical_game_directory(&game)?;
    launch_settings_json(&app, &directory, None)
}

#[tauri::command]
pub(crate) fn update_launch_settings(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
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
        if let Some(memory_mib) = settings.memory_mib {
            command.arg("--memory-mb").arg(memory_mib.to_string());
        }
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

pub(crate) fn validate_launch_settings(settings: &LaunchSettingsInput) -> Result<(), String> {
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
    if settings
        .memory_mib
        .is_some_and(|memory| !(512..=32768).contains(&memory) || memory % 256 != 0)
    {
        return Err("Memory must be 512-32768 MiB in 256 MiB steps.".to_string());
    }
    Ok(())
}

#[tauri::command]
pub(crate) fn get_profiles(app: AppHandle, game: String) -> Result<Value, String> {
    profile_json(&app, &game, &["profile", "list"], false)
}

#[tauri::command]
pub(crate) fn save_profile(app: AppHandle, game: String, name: String) -> Result<Value, String> {
    profile_json(&app, &game, &["profile", "save", name.as_str()], false)
}

#[tauri::command]
pub(crate) fn activate_profile(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
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
pub(crate) fn get_snapshot(app: AppHandle, game: Option<String>) -> Result<Value, String> {
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
pub(crate) fn get_cache(app: AppHandle, game: String) -> Result<Value, String> {
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
pub(crate) fn get_cache_health(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    game: String,
) -> Result<Value, String> {
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The process tracker is unavailable.".to_string())?;
    if running.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before inspecting prepared data.".to_string(),
        );
    }
    cache_health_json(&app, &game, None)
}

#[tauri::command]
pub(crate) fn repair_cache(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    game: String,
    expected_profile: String,
) -> Result<Value, String> {
    let running = tracker
        .0
        .lock()
        .map_err(|_| "The process tracker is unavailable.".to_string())?;
    validate_cache_repair_state(&running)?;
    let result = cache_health_json(&app, &game, Some(&expected_profile));
    drop(running);
    result
}

pub(crate) fn validate_cache_repair_state(state: &OperationState) -> Result<(), String> {
    refuse_update_install(state)?;
    if state.game.is_some() {
        return Err("Close Starsector before repairing prepared data.".to_string());
    }
    if state.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before repairing prepared data.".to_string(),
        );
    }
    Ok(())
}

fn cache_health_json(
    app: &AppHandle,
    game: &str,
    expected_profile: Option<&str>,
) -> Result<Value, String> {
    let directory = canonical_game_directory(game)?;
    let paths = EnginePaths::resolve(app)?;
    let mut command = paths.command();
    configure_cache_health_command(&mut command, &directory, expected_profile);
    let output = command
        .output()
        .map_err(|error| format!("Could not start the Preflight engine: {error}"))?;
    if !output.status.success() && output.status.code() != Some(3) {
        return Err(child_error(
            if expected_profile.is_some() {
                "Preflight could not repair prepared data"
            } else {
                "Preflight could not inspect prepared data"
            },
            &output.stderr,
        ));
    }
    serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("Preflight returned unreadable cache health data: {error}"))
}

pub(crate) fn configure_cache_health_command(
    command: &mut Command,
    directory: &Path,
    expected_profile: Option<&str>,
) {
    command.arg("cache");
    if let Some(profile) = expected_profile {
        command
            .arg("repair")
            .arg("--yes")
            .arg("--expected-profile")
            .arg(profile);
    } else {
        command.arg("health");
    }
    command.arg("--json").arg("--game").arg(directory);
}

#[tauri::command]
pub(crate) fn get_cache_cleanup(app: AppHandle, game: String) -> Result<Value, String> {
    cache_cleanup_json(&app, &game, false)
}

#[tauri::command]
pub(crate) fn apply_cache_cleanup(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
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

pub(crate) fn validate_removal_scope(scope: &str) -> Result<(), String> {
    match scope {
        "launcher" | "all-data" => Ok(()),
        _ => Err("Removal scope must be launcher or all-data.".to_string()),
    }
}

#[tauri::command]
pub(crate) fn get_removal_plan(app: AppHandle, scope: String) -> Result<Value, String> {
    removal_json(&app, &scope, false)
}

#[tauri::command]
pub(crate) fn apply_removal(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
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

pub(crate) fn diagnostic_output_path(output: &str) -> Result<PathBuf, String> {
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
pub(crate) fn export_diagnostics(app: AppHandle, output: String) -> Result<Value, String> {
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

#[cfg(windows)]
fn configure_child_process(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    command.creation_flags(0x0800_0000);
}

#[cfg(not(windows))]
fn configure_child_process(_command: &mut Command) {}
