use crate::child_error;
use crate::operations::{OperationCoordinator, OperationState, refuse_update_install};
use serde::Deserialize;
use serde_json::Value;
#[cfg(debug_assertions)]
use std::env;
use std::ffi::{OsStr, OsString};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Output, Stdio};
use tauri::path::BaseDirectory;
use tauri::{AppHandle, Manager, State};

pub(crate) struct EnginePaths {
    java: PathBuf,
    jar: PathBuf,
}

impl EnginePaths {
    pub(crate) fn resolve(app: &AppHandle) -> Result<Self, String> {
        let bundled_jar = || bundled_resource_file(app, Path::new("engine/preflight.jar"));
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

    pub(crate) fn command(&self) -> EngineCommand {
        let mut command = Command::new(&self.java);
        command.arg("-jar").arg(&self.jar);
        configure_child_process(&mut command);
        if let Some(locale) = ascii_locale_rescue(|name| std::env::var_os(name)) {
            command.env("LC_ALL", locale);
        }
        EngineCommand {
            inner: command,
            args: Vec::new(),
        }
    }
}

/// The locale a JVM inheriting a pure-ASCII environment should be given instead, if any.
///
/// A Unix JVM takes `sun.jnu.encoding` from the environment's locale, and that charset governs both
/// the arguments it decodes and the bytes it hands the filesystem. Under `C`/`POSIX` the charset is
/// US-ASCII, so a non-ASCII path is unusable no matter how it arrives: encoding the argument vector
/// recovers the string but the file layer still cannot express it. Measured on Debian with OpenJDK
/// 21, `LC_ALL=C.UTF-8` repairs the argument, the filesystem, and the engine jar's own path.
///
/// Only the ASCII-only locales are rescued. A real 8-bit locale is self-consistent with the
/// filenames on that system, and reinterpreting it as UTF-8 would corrupt paths that work today.
/// Windows is excluded because its charset comes from the system code page, which no locale
/// variable changes.
fn ascii_locale_rescue<F>(environment: F) -> Option<&'static str>
where
    F: Fn(&str) -> Option<OsString>,
{
    if cfg!(windows) {
        return None;
    }
    let effective = ["LC_ALL", "LC_CTYPE", "LANG"]
        .into_iter()
        .find_map(|name| {
            environment(name)
                .and_then(|value| value.into_string().ok())
                .filter(|value| !value.is_empty())
        })
        .unwrap_or_default();
    let ascii_only = effective.is_empty()
        || effective.eq_ignore_ascii_case("C")
        || effective.eq_ignore_ascii_case("POSIX");
    ascii_only.then_some("C.UTF-8")
}

/// First argument of an ASCII-encoded vector. Matches `Utf8Argv.SENTINEL` in the engine.
const UTF8_ARGV_SENTINEL: &str = "--preflight-utf8-argv";

/// An engine invocation whose arguments survive the trip into the JVM.
///
/// Windows hands a new process its command line converted to the active ANSI code page, and the
/// Java launcher reads it from there on every released JDK. A game folder, profile name, or user
/// directory holding anything outside that code page reaches `main` as `?` and is unrecoverable.
/// No JVM option avoids this: `sun.jnu.encoding` comes from the System Locale and is not settable
/// with `-D`.
///
/// So arguments are collected here rather than handed to [`Command`] as they arrive, and a vector
/// that needs more than ASCII is Base64 UTF-8 encoded behind [`UTF8_ARGV_SENTINEL`] just before the
/// process starts. The engine reverses it before parsing. Collecting them means a caller cannot add
/// an argument that skips the encoding.
pub(crate) struct EngineCommand {
    inner: Command,
    args: Vec<OsString>,
}

impl EngineCommand {
    pub(crate) fn arg<S: AsRef<OsStr>>(&mut self, argument: S) -> &mut Self {
        self.args.push(argument.as_ref().to_os_string());
        self
    }

    pub(crate) fn args<I, S>(&mut self, arguments: I) -> &mut Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<OsStr>,
    {
        for argument in arguments {
            self.arg(argument);
        }
        self
    }

    pub(crate) fn stdout(&mut self, configuration: Stdio) -> &mut Self {
        self.inner.stdout(configuration);
        self
    }

    pub(crate) fn stderr(&mut self, configuration: Stdio) -> &mut Self {
        self.inner.stderr(configuration);
        self
    }

    pub(crate) fn output(&mut self) -> std::io::Result<Output> {
        self.prepared().output()
    }

    pub(crate) fn spawn(&mut self) -> std::io::Result<Child> {
        self.prepared().spawn()
    }

    fn prepared(&mut self) -> &mut Command {
        let args = std::mem::take(&mut self.args);
        self.inner.args(encode_argv(args));
        &mut self.inner
    }

    /// The arguments as callers supplied them, before any encoding.
    #[cfg(test)]
    pub(crate) fn arguments(&self) -> Vec<String> {
        self.args
            .iter()
            .map(|argument| argument.to_string_lossy().into_owned())
            .collect()
    }

    #[cfg(test)]
    pub(crate) fn for_test(program: &str) -> Self {
        Self {
            inner: Command::new(program),
            args: Vec::new(),
        }
    }
}

/// Returns `args` unchanged when every argument is ASCII, otherwise the sentinel-marked Base64
/// UTF-8 form. Arguments that are not valid UTF-8 are already beyond recovery, so they are passed
/// through rather than silently rewritten.
fn encode_argv(args: Vec<OsString>) -> Vec<OsString> {
    if args
        .iter()
        .all(|argument| argument.to_str().is_some_and(str::is_ascii))
    {
        return args;
    }
    let Some(text) = args
        .iter()
        .map(|argument| argument.to_str())
        .collect::<Option<Vec<&str>>>()
    else {
        return args;
    };
    let mut encoded = Vec::with_capacity(args.len() + 1);
    encoded.push(OsString::from(UTF8_ARGV_SENTINEL));
    encoded.extend(
        text.into_iter()
            .map(|argument| OsString::from(base64_url(argument.as_bytes()))),
    );
    encoded
}

/// Base64 with the URL-safe alphabet and no padding, so an encoded argument needs no quoting.
fn base64_url(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut encoded = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let mut block = 0_u32;
        for (index, byte) in chunk.iter().enumerate() {
            block |= u32::from(*byte) << (16 - 8 * index);
        }
        for index in 0..=chunk.len() {
            let sextet = (block >> (18 - 6 * index)) & 0x3f;
            encoded.push(char::from(ALPHABET[sextet as usize]));
        }
    }
    encoded
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
    bundled_resource_file(
        app,
        Path::new("engine/runtime/bin").join(executable).as_path(),
    )
}

pub(crate) fn bundled_resource_file(app: &AppHandle, relative: &Path) -> Option<PathBuf> {
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

#[tauri::command]
pub(crate) fn rename_profile(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    game: String,
    name: String,
    new_name: String,
    expected_profile: Option<String>,
    confirmed: bool,
) -> Result<Value, String> {
    mutate_profile_json(
        &app,
        &tracker,
        &game,
        "rename",
        &name,
        Some(&new_name),
        expected_profile.as_deref(),
        confirmed,
    )
}

#[tauri::command]
pub(crate) fn delete_profile(
    app: AppHandle,
    tracker: State<'_, OperationCoordinator>,
    game: String,
    name: String,
    expected_profile: Option<String>,
    confirmed: bool,
) -> Result<Value, String> {
    mutate_profile_json(
        &app,
        &tracker,
        &game,
        "delete",
        &name,
        None,
        expected_profile.as_deref(),
        confirmed,
    )
}

#[allow(clippy::too_many_arguments)]
fn mutate_profile_json(
    app: &AppHandle,
    tracker: &OperationCoordinator,
    game: &str,
    operation: &str,
    name: &str,
    target_name: Option<&str>,
    expected_profile: Option<&str>,
    confirmed: bool,
) -> Result<Value, String> {
    let mut arguments = vec!["profile", operation, name];
    if let Some(target_name) = target_name {
        arguments.push(target_name);
    }
    if confirmed {
        let expected_profile = expected_profile.ok_or_else(|| {
            "Review this named-profile change again before applying it.".to_string()
        })?;
        arguments.extend(["--expected-profile", expected_profile, "--yes"]);
        let running = tracker
            .0
            .lock()
            .map_err(|_| "The process tracker is unavailable.".to_string())?;
        validate_profile_mutation_state(&running)?;
        let result = profile_json(app, game, &arguments, false);
        drop(running);
        return result;
    }
    profile_json(app, game, &arguments, false)
}

pub(crate) fn validate_profile_mutation_state(state: &OperationState) -> Result<(), String> {
    refuse_update_install(state)?;
    if state.game.is_some() {
        return Err("Close Starsector before changing named profiles.".to_string());
    }
    if state.preparation.is_some() {
        return Err(
            "Wait for profile preparation to finish before changing named profiles.".to_string(),
        );
    }
    Ok(())
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
    command: &mut EngineCommand,
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

#[cfg(test)]
mod tests {
    use super::{UTF8_ARGV_SENTINEL, ascii_locale_rescue, base64_url, encode_argv};
    use std::ffi::OsString;

    fn vector(args: &[&str]) -> Vec<OsString> {
        args.iter().map(OsString::from).collect()
    }

    fn locale_for(variables: &[(&str, &str)]) -> Option<&'static str> {
        let owned: Vec<(String, OsString)> = variables
            .iter()
            .map(|(name, value)| ((*name).to_string(), OsString::from(*value)))
            .collect();
        ascii_locale_rescue(|name| {
            owned
                .iter()
                .find(|(key, _)| key == name)
                .map(|(_, value)| value.clone())
        })
    }

    #[test]
    fn a_utf8_locale_is_left_alone() {
        assert_eq!(locale_for(&[("LANG", "en_US.UTF-8")]), None);
        assert_eq!(locale_for(&[("LC_ALL", "ja_JP.UTF-8")]), None);
        assert_eq!(locale_for(&[("LC_CTYPE", "de_DE.utf8")]), None);
    }

    #[test]
    fn a_real_eight_bit_locale_is_left_alone() {
        // Its filenames are that charset's bytes. Reading them as UTF-8 would break paths that
        // work today, which is worse than the non-ASCII case this rescues.
        assert_eq!(locale_for(&[("LANG", "de_DE.ISO-8859-1")]), None);
        assert_eq!(locale_for(&[("LC_ALL", "ru_RU.KOI8-R")]), None);
    }

    #[cfg_attr(windows, ignore = "Windows takes its charset from the system code page")]
    #[test]
    fn only_the_ascii_only_locales_are_rescued() {
        assert_eq!(locale_for(&[("LC_ALL", "C")]), Some("C.UTF-8"));
        assert_eq!(locale_for(&[("LC_ALL", "POSIX")]), Some("C.UTF-8"));
        assert_eq!(locale_for(&[("LANG", "c")]), Some("C.UTF-8"));
        assert_eq!(locale_for(&[]), Some("C.UTF-8"));
        assert_eq!(locale_for(&[("LANG", "")]), Some("C.UTF-8"));
    }

    #[cfg_attr(windows, ignore = "Windows takes its charset from the system code page")]
    #[test]
    fn lc_all_outranks_the_narrower_variables() {
        // The shell's own precedence: LC_ALL wins, so a UTF-8 LC_CTYPE under LC_ALL=C is still
        // an ASCII environment.
        assert_eq!(
            locale_for(&[("LC_ALL", "C"), ("LC_CTYPE", "en_US.UTF-8")]),
            Some("C.UTF-8")
        );
        assert_eq!(
            locale_for(&[("LC_CTYPE", "en_US.UTF-8"), ("LANG", "C")]),
            None
        );
    }

    #[test]
    fn ascii_vectors_reach_the_engine_unchanged() {
        let args = vector(&["desktop", "snapshot", "--game", "C:\\Games\\Starsector", "--json"]);
        assert_eq!(encode_argv(args.clone()), args);
    }

    #[test]
    fn a_vector_needing_more_than_ascii_is_marked_and_encoded() {
        let args = vector(&["desktop", "snapshot", "--game", "C:\\Synthetic Game – path Ω"]);
        let encoded = encode_argv(args);
        assert_eq!(encoded[0], OsString::from(UTF8_ARGV_SENTINEL));
        assert_eq!(encoded.len(), 5);
        for argument in &encoded[1..] {
            let text = argument.to_str().unwrap();
            assert!(text.is_ascii(), "{text} would not survive an ANSI code page");
            assert!(
                text.bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_'),
                "{text} needs quoting"
            );
        }
    }

    #[test]
    fn encoding_matches_the_engine_decoder() {
        // Base64url without padding, the exact form Utf8Argv reverses.
        assert_eq!(base64_url(b""), "");
        assert_eq!(base64_url(b"f"), "Zg");
        assert_eq!(base64_url(b"fo"), "Zm8");
        assert_eq!(base64_url(b"foo"), "Zm9v");
        assert_eq!(base64_url(b"foob"), "Zm9vYg");
        assert_eq!(base64_url(b"fooba"), "Zm9vYmE");
        assert_eq!(base64_url(b"foobar"), "Zm9vYmFy");
        assert_eq!(base64_url("Ω".as_bytes()), "zqk");
        assert_eq!(base64_url(&[0xff, 0xfe, 0xfd]), "__79");
    }

    #[test]
    fn every_argument_of_a_mixed_vector_is_encoded() {
        // A decoder that only reverses the non-ASCII arguments would misread the rest, so the
        // marker applies to the whole vector or to none of it.
        let encoded = encode_argv(vector(&["cache", "prune", "--game", "Ω"]));
        assert_eq!(encoded[1], OsString::from(base64_url(b"cache")));
        assert_eq!(encoded[4], OsString::from(base64_url("Ω".as_bytes())));
    }
}
