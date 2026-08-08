use crate::child_error;
use crate::operations::{OperationCoordinator, refuse_update_install};
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
