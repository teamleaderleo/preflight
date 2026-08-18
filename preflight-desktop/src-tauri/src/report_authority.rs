use crate::reports::{CreateReportCaseResponse, ReportDeletion, ReportReceipt, ReportUploadInput};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Manager};

const AUTHORITY_FORMAT: &str = "preflight-report-authority-v1";
const MAX_AUTHORITIES: usize = 32;
const MAX_CLAIMS: usize = 64;
const MAX_AUTHORITY_BYTES: u64 = 64 * 1024;
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReportCaseView {
    pub(crate) state: &'static str,
    pub(crate) case_id: String,
    pub(crate) bytes: u64,
    pub(crate) sha256: String,
    pub(crate) product_version: Option<String>,
    pub(crate) received_at: Option<String>,
    pub(crate) retention_deadline: Option<String>,
}

impl ReportCaseView {
    pub(crate) fn accepted(receipt: &ReportReceipt) -> Self {
        Self {
            state: "accepted",
            case_id: receipt.case_id.clone(),
            bytes: receipt.bytes,
            sha256: receipt.sha256.clone(),
            product_version: Some(receipt.product_version.clone()),
            received_at: Some(receipt.received_at.clone()),
            retention_deadline: Some(receipt.retention_deadline.clone()),
        }
    }

    fn pending(authority: &PendingAuthority) -> Self {
        Self {
            state: "pending",
            case_id: authority.case_id.clone(),
            bytes: authority.bytes,
            sha256: authority.sha256.clone(),
            product_version: None,
            received_at: None,
            retention_deadline: None,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PendingAuthority {
    format: String,
    case_id: String,
    bytes: u64,
    sha256: String,
    deletion: ReportDeletion,
    created_at_millis: u64,
}

pub(crate) struct ReportAuthorityStore {
    root: PathBuf,
    generation: String,
}

#[derive(Clone)]
pub(crate) struct NativeReportAuthorityLifecycle {
    root: PathBuf,
    generation: String,
}

impl ReportAuthorityStore {
    pub(crate) fn create(app: &AppHandle) -> Result<Self, String> {
        Self::create_at(authority_root(app)?)
    }

    fn create_at(root: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&root)
            .map_err(|error| format!("Could not create report-authority storage: {error}"))?;
        make_private_directory(&root)?;
        let marker = root.join("generation");
        let generation = match read_regular_text(&marker) {
            Ok(value) if !value.trim().is_empty() => value.trim().to_string(),
            _ => create_or_read_generation(&marker)?,
        };
        Ok(Self { root, generation })
    }

    pub(crate) fn lifecycle(&self) -> NativeReportAuthorityLifecycle {
        NativeReportAuthorityLifecycle {
            root: self.root.clone(),
            generation: self.generation.clone(),
        }
    }

    pub(crate) fn reports(&self) -> Result<Vec<ReportCaseView>, String> {
        list_report_views_at(&self.root)
    }

    pub(crate) fn persist_accepted(&self, receipt: &ReportReceipt) -> Result<(), String> {
        self.ensure_generation()?;
        persist_accepted_at(&self.root, receipt)
    }

    pub(crate) fn deletion(&self, case_id: &str) -> Result<ReportDeletion, String> {
        validate_case_id(case_id)?;
        let accepted = accepted_path(&self.root, case_id);
        if accepted.exists() {
            let receipt: ReportReceipt = read_json_regular(&accepted)?;
            return Ok(receipt.deletion);
        }
        let pending: PendingAuthority = read_json_regular(&pending_path(&self.root, case_id))?;
        Ok(pending.deletion)
    }

    pub(crate) fn dismiss(&self, case_id: &str) -> Result<(), String> {
        remove_case_at(&self.root, case_id)
    }

    pub(crate) fn clear_all(app: &AppHandle) -> Result<(), String> {
        for root in [authority_root(app)?, claim_root(app)?] {
            match fs::remove_dir_all(&root) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(format!("Could not clear saved report authority: {error}")),
            }
        }
        Ok(())
    }

    fn ensure_generation(&self) -> Result<(), String> {
        let current = read_regular_text(&self.root.join("generation"))?;
        if current.trim() == self.generation {
            Ok(())
        } else {
            Err("Report-authority storage changed while the report was active.".to_string())
        }
    }
}

impl NativeReportAuthorityLifecycle {
    pub(crate) fn granted(
        &self,
        grant: &CreateReportCaseResponse,
        report: &ReportUploadInput,
    ) -> Result<(), String> {
        self.ensure_generation()?;
        if !accepted_path(&self.root, &grant.case_id).exists()
            && !pending_path(&self.root, &grant.case_id).exists()
            && authority_count(&self.root)? >= MAX_AUTHORITIES
        {
            return Err("Preflight already has the maximum number of actionable report cases. Delete or dismiss an older case before sending another report.".to_string());
        }
        let pending = PendingAuthority {
            format: AUTHORITY_FORMAT.to_string(),
            case_id: grant.case_id.clone(),
            bytes: report.bytes,
            sha256: report.sha256.clone(),
            deletion: ReportDeletion {
                method: grant.deletion.method.clone(),
                url: grant.deletion.url.clone(),
                token: grant.deletion.token.clone(),
            },
            created_at_millis: now_millis()?,
        };
        persist_json_new(&pending_path(&self.root, &grant.case_id), &pending)
    }

    pub(crate) fn accepted(&self, receipt: &ReportReceipt) -> Result<(), String> {
        self.ensure_generation()?;
        persist_accepted_at(&self.root, receipt)
    }

    pub(crate) fn cleared(&self, case_id: &str) -> Result<(), String> {
        if self.ensure_generation().is_err() {
            return Ok(());
        }
        remove_case_at(&self.root, case_id)
    }

    fn ensure_generation(&self) -> Result<(), String> {
        let current = read_regular_text(&self.root.join("generation"))?;
        if current.trim() == self.generation {
            Ok(())
        } else {
            Err("Report-authority storage changed while the report was active.".to_string())
        }
    }
}

pub(crate) fn claim_automatic_report(app: &AppHandle, identity: &str) -> Result<bool, String> {
    claim_automatic_report_at(&claim_root(app)?, identity)
}

fn claim_automatic_report_at(root: &Path, identity: &str) -> Result<bool, String> {
    if identity.is_empty() || identity.len() > 1024 || identity.contains('\0') {
        return Err("The automatic report identity is invalid.".to_string());
    }
    fs::create_dir_all(root)
        .map_err(|error| format!("Could not create automatic-report claim storage: {error}"))?;
    make_private_directory(root)?;
    prune_claims(root)?;
    let digest = Sha256::digest(identity.as_bytes())
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    let path = root.join(format!("{digest}.claim"));
    match OpenOptions::new().write(true).create_new(true).open(&path) {
        Ok(mut file) => {
            make_private_file(&file)?;
            file.write_all(format!("{}\n", now_millis()?).as_bytes())
                .map_err(|error| format!("Could not write automatic-report claim: {error}"))?;
            file.sync_all()
                .map_err(|error| format!("Could not save automatic-report claim: {error}"))?;
            sync_directory(root)?;
            Ok(true)
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => Ok(false),
        Err(error) => Err(format!("Could not claim the failed run for automatic reporting: {error}")),
    }
}

fn prune_claims(root: &Path) -> Result<(), String> {
    let mut claims = Vec::new();
    for entry in fs::read_dir(root)
        .map_err(|error| format!("Could not read automatic-report claim storage: {error}"))?
    {
        let entry = match entry {
            Ok(entry) => entry,
            Err(_) => continue,
        };
        let name = entry.file_name().to_string_lossy().into_owned();
        if !name.ends_with(".claim") {
            continue;
        }
        let Ok(metadata) = entry.metadata() else { continue };
        let Ok(modified) = metadata.modified() else { continue };
        claims.push((modified, entry.path()));
    }
    claims.sort_by_key(|(modified, _)| *modified);
    while claims.len() >= MAX_CLAIMS {
        let (_, path) = claims.remove(0);
        remove_file_if_regular(&path)?;
    }
    Ok(())
}

fn authority_root(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(support_root(app)?.join("report-authority"))
}

fn claim_root(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(support_root(app)?.join("automatic-report-claims"))
}

fn support_root(app: &AppHandle) -> Result<PathBuf, String> {
    let home = app
        .path()
        .home_dir()
        .map_err(|error| format!("Could not locate the home directory: {error}"))?;
    Ok(home.join(".starsector-preflight").join("support"))
}

fn create_or_read_generation(marker: &Path) -> Result<String, String> {
    let value = format!(
        "{}-{}-{}",
        std::process::id(),
        now_millis()?,
        NEXT_GENERATION.fetch_add(1, Ordering::Relaxed)
    );
    match OpenOptions::new().write(true).create_new(true).open(marker) {
        Ok(mut file) => {
            make_private_file(&file)?;
            file.write_all(value.as_bytes())
                .map_err(|error| format!("Could not write report-authority generation: {error}"))?;
            file.sync_all()
                .map_err(|error| format!("Could not save report-authority generation: {error}"))?;
            if let Some(parent) = marker.parent() {
                sync_directory(parent)?;
            }
            Ok(value)
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            let value = read_regular_text(marker)?;
            if value.trim().is_empty() {
                Err("Report-authority generation is empty.".to_string())
            } else {
                Ok(value.trim().to_string())
            }
        }
        Err(error) => Err(format!("Could not create report-authority generation: {error}")),
    }
}

fn persist_accepted_at(root: &Path, receipt: &ReportReceipt) -> Result<(), String> {
    validate_case_id(&receipt.case_id)?;
    let path = accepted_path(root, &receipt.case_id);
    if path.exists() {
        let existing: ReportReceipt = read_json_regular(&path)?;
        if existing.case_id == receipt.case_id
            && existing.bytes == receipt.bytes
            && existing.sha256 == receipt.sha256
            && existing.deletion.url == receipt.deletion.url
            && existing.deletion.token == receipt.deletion.token
        {
            remove_file_if_regular(&pending_path(root, &receipt.case_id))?;
            return Ok(());
        }
        return Err("A different report authority already exists for this case.".to_string());
    }
    if !pending_path(root, &receipt.case_id).exists() && authority_count(root)? >= MAX_AUTHORITIES {
        return Err("Preflight already has the maximum number of actionable report cases.".to_string());
    }
    persist_json_new(&path, receipt)?;
    remove_file_if_regular(&pending_path(root, &receipt.case_id))?;
    Ok(())
}

fn list_report_views_at(root: &Path) -> Result<Vec<ReportCaseView>, String> {
    let entries = match fs::read_dir(root) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(format!("Could not read saved report authority: {error}")),
    };
    let mut reports = BTreeMap::<String, ReportCaseView>::new();
    for entry in entries {
        let entry = entry.map_err(|error| format!("Could not read saved report authority: {error}"))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if let Some(case_id) = name.strip_suffix(".pending.json") {
            validate_case_id(case_id)?;
            let pending: PendingAuthority = read_json_regular(&entry.path())?;
            if pending.format != AUTHORITY_FORMAT || pending.case_id != case_id {
                return Err("Saved pending report authority is inconsistent.".to_string());
            }
            reports.entry(case_id.to_string()).or_insert_with(|| ReportCaseView::pending(&pending));
        } else if let Some(case_id) = name.strip_suffix(".accepted.json") {
            validate_case_id(case_id)?;
            let receipt: ReportReceipt = read_json_regular(&entry.path())?;
            if receipt.case_id != case_id {
                return Err("Saved accepted report authority is inconsistent.".to_string());
            }
            reports.insert(case_id.to_string(), ReportCaseView::accepted(&receipt));
        }
    }
    let mut reports = reports.into_values().collect::<Vec<_>>();
    reports.sort_by(|left, right| {
        right
            .received_at
            .cmp(&left.received_at)
            .then_with(|| right.case_id.cmp(&left.case_id))
    });
    Ok(reports)
}

fn authority_count(root: &Path) -> Result<usize, String> {
    Ok(list_report_views_at(root)?.len())
}

fn persist_json_new<T: Serialize>(path: &Path, value: &T) -> Result<(), String> {
    let parent = path.parent().ok_or_else(|| "Saved report authority has no parent directory.".to_string())?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("Could not create report-authority storage: {error}"))?;
    make_private_directory(parent)?;
    let bytes = serde_json::to_vec(value)
        .map_err(|error| format!("Could not serialize saved report authority: {error}"))?;
    if bytes.len() as u64 > MAX_AUTHORITY_BYTES {
        return Err("Saved report authority is unexpectedly large.".to_string());
    }
    let mut file = match OpenOptions::new().write(true).create_new(true).open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            return Err("Saved report authority already exists for this case.".to_string());
        }
        Err(error) => return Err(format!("Could not create saved report authority: {error}")),
    };
    if let Err(error) = make_private_file(&file) {
        drop(file);
        let _ = fs::remove_file(path);
        return Err(error);
    }
    let write_result = file
        .write_all(&bytes)
        .map_err(|error| format!("Could not write saved report authority: {error}"))
        .and_then(|()| {
            file.sync_all()
                .map_err(|error| format!("Could not save report authority: {error}"))
        });
    drop(file);
    if let Err(error) = write_result {
        let _ = fs::remove_file(path);
        return Err(error);
    }
    sync_directory(parent)?;
    Ok(())
}

fn read_json_regular<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T, String> {
    let metadata = path.symlink_metadata()
        .map_err(|error| format!("Could not inspect saved report authority: {error}"))?;
    if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() > MAX_AUTHORITY_BYTES {
        return Err("Saved report authority is not a bounded regular file.".to_string());
    }
    let mut file = fs::File::open(path)
        .map_err(|error| format!("Could not open saved report authority: {error}"))?;
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    file.read_to_end(&mut bytes)
        .map_err(|error| format!("Could not read saved report authority: {error}"))?;
    serde_json::from_slice(&bytes)
        .map_err(|error| format!("Saved report authority is unreadable: {error}"))
}

fn read_regular_text(path: &Path) -> Result<String, String> {
    let metadata = path.symlink_metadata()
        .map_err(|error| format!("Could not inspect report-authority generation: {error}"))?;
    if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() > 256 {
        return Err("Report-authority generation is not a bounded regular file.".to_string());
    }
    fs::read_to_string(path)
        .map_err(|error| format!("Could not read report-authority generation: {error}"))
}

fn remove_case_at(root: &Path, case_id: &str) -> Result<(), String> {
    validate_case_id(case_id)?;
    remove_file_if_regular(&accepted_path(root, case_id))?;
    remove_file_if_regular(&pending_path(root, case_id))?;
    if root.exists() {
        sync_directory(root)?;
    }
    Ok(())
}

fn remove_file_if_regular(path: &Path) -> Result<(), String> {
    let metadata = match path.symlink_metadata() {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(format!("Could not inspect saved report authority: {error}")),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err("Saved report authority is not a regular file.".to_string());
    }
    fs::remove_file(path).map_err(|error| format!("Could not remove saved report authority: {error}"))
}

fn accepted_path(root: &Path, case_id: &str) -> PathBuf {
    root.join(format!("{case_id}.accepted.json"))
}

fn pending_path(root: &Path, case_id: &str) -> PathBuf {
    root.join(format!("{case_id}.pending.json"))
}

fn now_millis() -> Result<u64, String> {
    let millis = SystemTime::now().duration_since(UNIX_EPOCH)
        .map_err(|_| "The system clock is before 1970.".to_string())?
        .as_millis();
    u64::try_from(millis).map_err(|_| "The system clock is outside the supported range.".to_string())
}

fn validate_case_id(value: &str) -> Result<(), String> {
    let valid = value.len() == 36 && value.bytes().enumerate().all(|(index, byte)| {
        if matches!(index, 8 | 13 | 18 | 23) {
            byte == b'-'
        } else {
            byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()
        }
    });
    if valid { Ok(()) } else { Err("The report case identity is invalid.".to_string()) }
}

#[cfg(unix)]
fn make_private_directory(path: &Path) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
        .map_err(|error| format!("Could not protect report-authority storage: {error}"))
}

#[cfg(not(unix))]
fn make_private_directory(_path: &Path) -> Result<(), String> { Ok(()) }

#[cfg(unix)]
fn make_private_file(file: &fs::File) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    file.set_permissions(fs::Permissions::from_mode(0o600))
        .map_err(|error| format!("Could not protect saved report authority: {error}"))
}

#[cfg(not(unix))]
fn make_private_file(_file: &fs::File) -> Result<(), String> { Ok(()) }

#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<(), String> {
    fs::File::open(path)
        .and_then(|directory| directory.sync_all())
        .map_err(|error| format!("Could not durably save report authority: {error}"))
}

#[cfg(not(unix))]
fn sync_directory(_path: &Path) -> Result<(), String> { Ok(()) }

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Barrier};

    #[test]
    fn case_files_keep_multiple_deletion_authorities_independent() {
        let root = temp_root("multiple");
        let store = ReportAuthorityStore::create_at(root.clone()).unwrap();
        let first = test_receipt("11111111-1111-1111-1111-111111111111", "aa");
        let second = test_receipt("22222222-2222-2222-2222-222222222222", "bb");
        store.persist_accepted(&first).unwrap();
        store.persist_accepted(&second).unwrap();
        assert_eq!(2, store.reports().unwrap().len());
        store.dismiss(&first.case_id).unwrap();
        let remaining = store.reports().unwrap();
        assert_eq!(1, remaining.len());
        assert_eq!(second.case_id, remaining[0].case_id);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn removed_generation_prevents_late_receipt_resurrection() {
        let root = temp_root("generation");
        let store = ReportAuthorityStore::create_at(root.clone()).unwrap();
        let lifecycle = store.lifecycle();
        fs::remove_dir_all(&root).unwrap();
        let replacement = ReportAuthorityStore::create_at(root.clone()).unwrap();
        assert_ne!(store.generation, replacement.generation);
        assert!(lifecycle.accepted(&test_receipt("33333333-3333-3333-3333-333333333333", "cc")).is_err());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn automatic_claim_is_atomic_across_threads() {
        let root = temp_root("claims");
        let barrier = Arc::new(Barrier::new(3));
        let workers = (0..2).map(|_| {
            let root = root.clone();
            let barrier = barrier.clone();
            std::thread::spawn(move || {
                barrier.wait();
                claim_automatic_report_at(&root, "same-run-identity").unwrap()
            })
        }).collect::<Vec<_>>();
        barrier.wait();
        let winners = workers.into_iter().map(|worker| worker.join().unwrap()).filter(|won| *won).count();
        assert_eq!(1, winners);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn authority_publication_never_replaces_an_existing_case_file() {
        let root = temp_root("create-new");
        fs::create_dir_all(&root).unwrap();
        let path = root.join("authority.json");
        persist_json_new(&path, &serde_json::json!({"value": "first"})).unwrap();
        assert!(persist_json_new(&path, &serde_json::json!({"value": "second"})).is_err());
        let saved: serde_json::Value = read_json_regular(&path).unwrap();
        assert_eq!("first", saved["value"]);
        fs::remove_dir_all(root).unwrap();
    }

    fn temp_root(label: &str) -> PathBuf {
        std::env::temp_dir().join(format!("preflight-report-{label}-{}-{}", std::process::id(), now_millis().unwrap()))
    }

    fn test_receipt(case_id: &str, byte: &str) -> ReportReceipt {
        ReportReceipt {
            protocol_version: 1,
            case_id: case_id.to_string(),
            object_key: format!("accepted/{case_id}.zip"),
            bytes: 1024,
            sha256: byte.repeat(32),
            product_version: env!("CARGO_PKG_VERSION").to_string(),
            received_at: "2026-08-18T10:00:00Z".to_string(),
            retention_deadline: "2026-09-02T10:00:00Z".to_string(),
            deletion: ReportDeletion {
                method: "DELETE".to_string(),
                url: format!("https://reports.example.com/v1/cases/{case_id}"),
                token: "token.value".to_string(),
            },
            signature: "signature".to_string(),
        }
    }
}
