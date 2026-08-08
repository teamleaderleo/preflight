use crate::operations::{OperationCoordinator, begin_update_install, refuse_update_install};
use serde::Serialize;
#[cfg(target_os = "linux")]
use std::env;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, State};
use tauri_plugin_updater::{Update, UpdaterExt};
use url::Url;

pub(crate) const DEFAULT_UPDATE_ENDPOINT: &str =
    "https://github.com/teamleaderleo/preflight/releases/latest/download/latest.json";

#[derive(Default)]
pub(crate) struct UpdateTracker(pub(crate) Mutex<Option<Update>>);

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UpdateStatus {
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

fn compiled_updater_public_key() -> Option<&'static str> {
    option_env!("PREFLIGHT_UPDATER_PUBLIC_KEY")
        .map(str::trim)
        .filter(|key| !key.is_empty())
}

pub(crate) fn compiled_updater_endpoint() -> &'static str {
    option_env!("PREFLIGHT_UPDATER_ENDPOINT")
        .map(str::trim)
        .filter(|endpoint| !endpoint.is_empty())
        .unwrap_or(DEFAULT_UPDATE_ENDPOINT)
}

pub(crate) fn validated_updater_endpoint(endpoint: &str) -> Result<Url, String> {
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
pub(crate) async fn check_for_update(
    app: AppHandle,
    processes: State<'_, OperationCoordinator>,
    updates: State<'_, UpdateTracker>,
) -> Result<UpdateStatus, String> {
    {
        let running = processes
            .0
            .lock()
            .map_err(|_| "The process tracker is unavailable.".to_string())?;
        refuse_update_install(&running)?;
    }
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
pub(crate) async fn install_update(
    app: AppHandle,
    processes: State<'_, OperationCoordinator>,
    updates: State<'_, UpdateTracker>,
    requested_version: String,
) -> Result<(), String> {
    let _install = begin_update_install(&processes.0)?;

    let displayed_update = updates
        .0
        .lock()
        .map_err(|_| "The update tracker is unavailable.".to_string())?
        .take();
    let Some(displayed_update) = displayed_update else {
        return Err("Check for an update before trying to install one.".to_string());
    };
    if displayed_update.version != requested_version {
        return Err("The selected update changed. Check again before installing.".to_string());
    }

    let refreshed = current_verified_update(&app).await;
    let update = match refreshed {
        Ok(Some(update)) if same_update_offer(&displayed_update, &update) => update,
        Ok(_) => {
            return Err(
                "That update is no longer the exact release currently offered. Check again before installing."
                    .to_string(),
            );
        }
        Err(error) => return Err(error),
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
