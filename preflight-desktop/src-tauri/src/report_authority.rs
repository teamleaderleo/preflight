use crate::reports::{CreateReportCaseResponse, ReportDeletion, ReportReceipt, ReportUploadInput};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
#[cfg(test)]
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Manager};

const AUTHORITY_FORMAT: &str = "preflight-report-authority-v1";
const MAX_AUTHORITIES: usize = 32;
const MAX_CLAIMS: usize = 64;
const MAX_AUTHORITY_BYTES: u64 = 64 * 1024;
const PREFLIGHT_HOME_DIRECTORY: &str = ".starsector-preflight";
const REMOVAL_MARKER_NAME: &str = "removal.pending";
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);

#[cfg(test)]
enum TestPersistMutation {
    RemoveRoot,
    AliasRoot(PathBuf),
}

#[cfg(test)]
static TEST_PERSIST_MUTATION: Mutex<Option<TestPersistMutation>> = Mutex::new(None);
#[cfg(test)]
static TEST_PERSIST_TEST_SERIAL: Mutex<()> = Mutex::new(());

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

#[derive(Clone, Serialize, Deserialize)]
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
        ensure_protected_directory_chain(&root)?;
        let marker = root.join("generation");
        let generation = match read_regular_text(&marker) {
            Ok(value) if !value.trim().is_empty() => value.trim().to_string(),
            _ => create_or_read_generation(&marker)?,
        };
        let store = Self { root, generation };
        prune_expired_accepted_at(&store.root, now_millis()?)?;
        Ok(store)
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
        refuse_if_removal_pending(&self.root)?;
        self.ensure_generation()?;
        persist_accepted_at(&self.root, receipt)
    }

    pub(crate) fn deletion(&self, case_id: &str) -> Result<ReportDeletion, String> {
        self.ensure_generation()?;
        prune_expired_accepted_at(&self.root, now_millis()?)?;
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
        self.ensure_generation()?;
        remove_case_at(&self.root, case_id)
    }

    pub(crate) fn clear_all(app: &AppHandle) -> Result<(), String> {
        let authority = authority_root(app)?;
        if authority.symlink_metadata().is_ok() {
            verify_protected_directory_chain(&authority)?;
            if !list_report_views_at(&authority)?.is_empty() {
                return Err("Delete uploaded reports, or explicitly dismiss their saved deletion authorization, before clearing report authority.".to_string());
            }
        }

        // Establish the same durable removal fence used by the Java operation lease before
        // removing the empty native authority namespace. Other Preflight processes then
        // fail closed instead of recreating bearer credentials during all-data removal.
        let fence = establish_removal_fence(&authority)?;
        if authority.symlink_metadata().is_ok() {
            verify_protected_directory_chain(&authority)?;
            if !list_report_views_at(&authority)?.is_empty() {
                release_new_removal_fence(&fence)?;
                return Err("A report case became actionable while all-data removal was starting. Delete it, or explicitly dismiss its saved deletion authorization, then retry removal.".to_string());
            }
        }

        for root in [authority, claim_root(app)?] {
            let metadata = match root.symlink_metadata() {
                Ok(metadata) => metadata,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
                Err(error) => {
                    return Err(format!("Could not inspect saved report authority: {error}"));
                }
            };
            if metadata_is_alias(&metadata) || !metadata.is_dir() {
                return Err(
                    "Refusing to clear report authority through an aliased or non-directory path."
                        .to_string(),
                );
            }
            verify_protected_directory_chain(&root)?;
            fs::remove_dir_all(&root)
                .map_err(|error| format!("Could not clear saved report authority: {error}"))?;
        }
        Ok(())
    }

    fn ensure_generation(&self) -> Result<(), String> {
        verify_protected_directory_chain(&self.root)?;
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
        refuse_if_removal_pending(&self.root)?;
        self.ensure_generation()?;
        prune_expired_accepted_at(&self.root, now_millis()?)?;
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
        refuse_if_removal_pending(&self.root)?;
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
        verify_protected_directory_chain(&self.root)?;
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
    refuse_if_removal_pending(root)?;
    ensure_protected_directory_chain(root)?;
    prune_claims(root)?;
    let digest = Sha256::digest(identity.as_bytes())
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    let path = root.join(format!("{digest}.claim"));
    verify_protected_directory_chain(root)?;
    match OpenOptions::new().write(true).create_new(true).open(&path) {
        Ok(mut file) => {
            if let Err(error) = make_private_file(&file) {
                drop(file);
                let _ = remove_file_if_regular(&path);
                return Err(error);
            }
            let write_result = file
                .write_all(format!("{}\n", now_millis()?).as_bytes())
                .map_err(|error| format!("Could not write automatic-report claim: {error}"))
                .and_then(|()| {
                    file.sync_all()
                        .map_err(|error| format!("Could not save automatic-report claim: {error}"))
                });
            drop(file);
            if let Err(error) = write_result {
                let _ = remove_file_if_regular(&path);
                return Err(error);
            }
            if let Err(error) = refuse_if_removal_pending(root) {
                let _ = remove_file_if_regular(&path);
                return Err(error);
            }
            verify_protected_directory_chain(root)?;
            sync_directory(root)?;
            Ok(true)
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => Ok(false),
        Err(error) => Err(format!(
            "Could not claim the failed run for automatic reporting: {error}"
        )),
    }
}

fn prune_claims(root: &Path) -> Result<(), String> {
    verify_protected_directory_chain(root)?;
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
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        let Ok(modified) = metadata.modified() else {
            continue;
        };
        claims.push((modified, entry.path()));
    }
    claims.sort_by_key(|(modified, _)| *modified);
    while claims.len() >= MAX_CLAIMS {
        let (_, path) = claims.remove(0);
        verify_protected_directory_chain(root)?;
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
    let home = fs::canonicalize(home)
        .map_err(|error| format!("Could not resolve the home directory: {error}"))?;
    verify_real_directory(&home, "home directory")?;
    Ok(home.join(".starsector-preflight").join("support"))
}

fn create_or_read_generation(marker: &Path) -> Result<String, String> {
    let parent = marker
        .parent()
        .ok_or_else(|| "Report-authority generation has no parent directory.".to_string())?;
    verify_protected_directory_chain(parent)?;
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
            verify_protected_directory_chain(parent)?;
            sync_directory(parent)?;
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
        Err(error) => Err(format!(
            "Could not create report-authority generation: {error}"
        )),
    }
}

fn persist_accepted_at(root: &Path, receipt: &ReportReceipt) -> Result<(), String> {
    verify_protected_directory_chain(root)?;
    let now = now_millis()?;
    prune_expired_accepted_at(root, now)?;
    validate_case_id(&receipt.case_id)?;
    let deadline = parse_retention_deadline_millis(&receipt.retention_deadline)
        .map_err(|error| format!("Report receipt has an invalid retention deadline: {error}"))?;
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
    if i128::from(deadline) <= i128::from(now) {
        remove_file_if_regular(&pending_path(root, &receipt.case_id))?;
        if root.exists() {
            verify_protected_directory_chain(root)?;
            sync_directory(root)?;
        }
        return Ok(());
    }
    if !pending_path(root, &receipt.case_id).exists() && authority_count(root)? >= MAX_AUTHORITIES {
        return Err(
            "Preflight already has the maximum number of actionable report cases.".to_string(),
        );
    }
    persist_json_new(&path, receipt)?;
    remove_file_if_regular(&pending_path(root, &receipt.case_id))?;
    Ok(())
}

fn prune_expired_accepted_at(root: &Path, now_millis: u64) -> Result<usize, String> {
    if root.exists() {
        verify_protected_directory_chain(root)?;
    }
    let entries = match fs::read_dir(root) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(0),
        Err(error) => return Err(format!("Could not read saved report authority: {error}")),
    };
    let mut accepted = Vec::new();
    for entry in entries {
        let entry =
            entry.map_err(|error| format!("Could not read saved report authority: {error}"))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if let Some(case_id) = name.strip_suffix(".accepted.json") {
            accepted.push((case_id.to_string(), entry.path()));
        }
    }
    accepted.sort_by(|left, right| left.0.cmp(&right.0));

    let mut expired = Vec::new();
    for (case_id, path) in accepted {
        validate_case_id(&case_id)?;
        let receipt: ReportReceipt = read_json_regular(&path)?;
        if receipt.case_id != case_id {
            return Err("Saved accepted report authority is inconsistent.".to_string());
        }
        let deadline = parse_retention_deadline_millis(&receipt.retention_deadline).map_err(|error| {
            format!(
                "Saved accepted report authority for case {case_id} has an invalid retention deadline: {error}"
            )
        })?;
        if i128::from(deadline) <= i128::from(now_millis) {
            let pending = pending_path(root, &case_id);
            if let Ok(metadata) = pending.symlink_metadata() {
                if metadata_is_alias(&metadata) || !metadata.is_file() {
                    return Err("Saved report authority is not a regular file.".to_string());
                }
            }
            expired.push((pending, path));
        }
    }

    let removed = expired.len();
    for (pending, accepted) in expired {
        verify_protected_directory_chain(root)?;
        // Remove a crash-residue pending credential first. A process stop between these deletions
        // leaves the accepted receipt available for the next deterministic expiry pass.
        remove_file_if_regular(&pending)?;
        remove_file_if_regular(&accepted)?;
    }
    if removed > 0 {
        verify_protected_directory_chain(root)?;
        sync_directory(root)?;
    }
    Ok(removed)
}

fn parse_retention_deadline_millis(value: &str) -> Result<i64, String> {
    let bytes = value.as_bytes();
    if bytes.len() < 20
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
    {
        return Err("expected a UTC RFC 3339 timestamp".to_string());
    }
    let fraction = if bytes.len() == 20 && bytes[19] == b'Z' {
        &bytes[19..19]
    } else if bytes.len() >= 22 && bytes[19] == b'.' && bytes.last() == Some(&b'Z') {
        let fraction = &bytes[20..bytes.len() - 1];
        if fraction.len() > 9 || fraction.iter().any(|byte| !byte.is_ascii_digit()) {
            return Err("expected one to nine fractional-second digits".to_string());
        }
        fraction
    } else {
        return Err("expected a UTC RFC 3339 timestamp".to_string());
    };

    let year = parse_fixed_decimal(&bytes[0..4])?;
    let month = parse_fixed_decimal(&bytes[5..7])?;
    let day = parse_fixed_decimal(&bytes[8..10])?;
    let hour = parse_fixed_decimal(&bytes[11..13])?;
    let minute = parse_fixed_decimal(&bytes[14..16])?;
    let second = parse_fixed_decimal(&bytes[17..19])?;
    let max_day = days_in_month(year, month)
        .ok_or_else(|| "retention deadline month is outside the supported range".to_string())?;
    if day == 0 || day > max_day || hour > 23 || minute > 59 || second > 59 {
        return Err("retention deadline date or time is outside the supported range".to_string());
    }

    let mut fraction_millis = 0i64;
    for index in 0..3 {
        fraction_millis *= 10;
        if let Some(byte) = fraction.get(index) {
            fraction_millis += i64::from(*byte - b'0');
        }
    }
    if fraction.len() > 3 && fraction[3..].iter().any(|byte| *byte != b'0') {
        fraction_millis += 1;
    }

    let seconds = days_from_civil(i64::from(year), month, day)
        .checked_mul(86_400)
        .and_then(|value| value.checked_add(i64::from(hour) * 3_600))
        .and_then(|value| value.checked_add(i64::from(minute) * 60))
        .and_then(|value| value.checked_add(i64::from(second)))
        .ok_or_else(|| "retention deadline is outside the supported range".to_string())?;
    seconds
        .checked_mul(1_000)
        .and_then(|value| value.checked_add(fraction_millis))
        .ok_or_else(|| "retention deadline is outside the supported range".to_string())
}

fn parse_fixed_decimal(bytes: &[u8]) -> Result<u32, String> {
    let mut value = 0u32;
    for byte in bytes {
        if !byte.is_ascii_digit() {
            return Err("retention deadline contains a non-decimal field".to_string());
        }
        value = value
            .checked_mul(10)
            .and_then(|value| value.checked_add(u32::from(*byte - b'0')))
            .ok_or_else(|| "retention deadline is outside the supported range".to_string())?;
    }
    Ok(value)
}

fn days_in_month(year: u32, month: u32) -> Option<u32> {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => Some(31),
        4 | 6 | 9 | 11 => Some(30),
        2 if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => Some(29),
        2 => Some(28),
        _ => None,
    }
}

fn days_from_civil(year: i64, month: u32, day: u32) -> i64 {
    let year = year - if month <= 2 { 1 } else { 0 };
    let era = if year >= 0 { year } else { year - 399 } / 400;
    let year_of_era = year - era * 400;
    let month_prime = i64::from(month) + if month > 2 { -3 } else { 9 };
    let day_of_year = (153 * month_prime + 2) / 5 + i64::from(day) - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

fn list_report_views_at(root: &Path) -> Result<Vec<ReportCaseView>, String> {
    if root.exists() {
        verify_protected_directory_chain(root)?;
    }
    prune_expired_accepted_at(root, now_millis()?)?;
    let entries = match fs::read_dir(root) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(format!("Could not read saved report authority: {error}")),
    };
    let mut reports = BTreeMap::<String, ReportCaseView>::new();
    for entry in entries {
        let entry =
            entry.map_err(|error| format!("Could not read saved report authority: {error}"))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if let Some(case_id) = name.strip_suffix(".pending.json") {
            validate_case_id(case_id)?;
            let pending: PendingAuthority = read_json_regular(&entry.path())?;
            if pending.format != AUTHORITY_FORMAT || pending.case_id != case_id {
                return Err("Saved pending report authority is inconsistent.".to_string());
            }
            reports
                .entry(case_id.to_string())
                .or_insert_with(|| ReportCaseView::pending(&pending));
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
    let parent = path
        .parent()
        .ok_or_else(|| "Saved report authority has no parent directory.".to_string())?;
    refuse_if_removal_pending(parent)?;
    test_mutate_before_persist(parent)?;
    // Parent creation belongs to store/claim initialization. Final credential publication never
    // recreates an absent parent, so a clear that wins after generation validation stays final.
    verify_protected_directory_chain(parent)?;
    let bytes = serde_json::to_vec(value)
        .map_err(|error| format!("Could not serialize saved report authority: {error}"))?;
    if bytes.len() as u64 > MAX_AUTHORITY_BYTES {
        return Err("Saved report authority is unexpectedly large.".to_string());
    }
    verify_protected_directory_chain(parent)?;
    let mut file = match OpenOptions::new().write(true).create_new(true).open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            return Err("Saved report authority already exists for this case.".to_string());
        }
        Err(error) => return Err(format!("Could not create saved report authority: {error}")),
    };
    if let Err(error) = make_private_file(&file) {
        drop(file);
        let _ = remove_file_if_regular(path);
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
        let _ = remove_file_if_regular(path);
        return Err(error);
    }
    if let Err(error) = refuse_if_removal_pending(parent) {
        let _ = remove_file_if_regular(path);
        return Err(error);
    }
    verify_protected_directory_chain(parent)?;
    sync_directory(parent)?;
    Ok(())
}

fn read_json_regular<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T, String> {
    if let Some(parent) = path.parent() {
        verify_protected_directory_chain(parent)?;
    }
    let metadata = path
        .symlink_metadata()
        .map_err(|error| format!("Could not inspect saved report authority: {error}"))?;
    if metadata_is_alias(&metadata) || !metadata.is_file() || metadata.len() > MAX_AUTHORITY_BYTES {
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
    if let Some(parent) = path.parent() {
        verify_protected_directory_chain(parent)?;
    }
    let metadata = path
        .symlink_metadata()
        .map_err(|error| format!("Could not inspect report-authority generation: {error}"))?;
    if metadata_is_alias(&metadata) || !metadata.is_file() || metadata.len() > 256 {
        return Err("Report-authority generation is not a bounded regular file.".to_string());
    }
    fs::read_to_string(path)
        .map_err(|error| format!("Could not read report-authority generation: {error}"))
}

fn remove_case_at(root: &Path, case_id: &str) -> Result<(), String> {
    validate_case_id(case_id)?;
    verify_protected_directory_chain(root)?;
    remove_file_if_regular(&accepted_path(root, case_id))?;
    verify_protected_directory_chain(root)?;
    remove_file_if_regular(&pending_path(root, case_id))?;
    verify_protected_directory_chain(root)?;
    sync_directory(root)?;
    Ok(())
}

fn remove_file_if_regular(path: &Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        verify_protected_directory_chain(parent)?;
    }
    let metadata = match path.symlink_metadata() {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(format!("Could not inspect saved report authority: {error}")),
    };
    if metadata_is_alias(&metadata) || !metadata.is_file() {
        return Err("Saved report authority is not a regular file.".to_string());
    }
    if let Some(parent) = path.parent() {
        verify_protected_directory_chain(parent)?;
    }
    fs::remove_file(path)
        .map_err(|error| format!("Could not remove saved report authority: {error}"))
}

fn accepted_path(root: &Path, case_id: &str) -> PathBuf {
    root.join(format!("{case_id}.accepted.json"))
}

fn pending_path(root: &Path, case_id: &str) -> PathBuf {
    root.join(format!("{case_id}.pending.json"))
}

#[derive(Clone, Debug)]
struct RemovalFence {
    path: Option<PathBuf>,
    created: bool,
}

fn preflight_root_for_owned_path(path: &Path) -> Option<PathBuf> {
    path.ancestors()
        .find(|candidate| {
            candidate
                .file_name()
                .is_some_and(|name| name == PREFLIGHT_HOME_DIRECTORY)
        })
        .map(Path::to_path_buf)
}

fn removal_marker_for_owned_path(path: &Path) -> Option<PathBuf> {
    preflight_root_for_owned_path(path).map(|root| root.join("state").join(REMOVAL_MARKER_NAME))
}

fn refuse_if_removal_pending(path: &Path) -> Result<(), String> {
    let Some(marker) = removal_marker_for_owned_path(path) else {
        return Ok(());
    };
    match marker.symlink_metadata() {
        Ok(metadata) if !metadata_is_alias(&metadata) && metadata.is_file() => Err(
            "Preflight data removal is in progress. Finish all-data removal before creating or publishing report authority."
                .to_string(),
        ),
        Ok(_) => Err(
            "Refusing report authority while the all-data removal marker is aliased or malformed."
                .to_string(),
        ),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(format!("Could not inspect the all-data removal marker: {error}")),
    }
}

fn establish_removal_fence(path: &Path) -> Result<RemovalFence, String> {
    let Some(marker) = removal_marker_for_owned_path(path) else {
        return Ok(RemovalFence {
            path: None,
            created: false,
        });
    };
    let state = marker
        .parent()
        .ok_or_else(|| "All-data removal marker has no parent directory.".to_string())?;
    ensure_protected_directory_chain(state)?;
    match OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&marker)
    {
        Ok(mut file) => {
            if let Err(error) = make_private_file(&file) {
                drop(file);
                let _ = remove_file_if_regular(&marker);
                return Err(error);
            }
            let saved = file
                .write_all(b"Preflight data removal is in progress. Re-run uninstall --purge --yes if interrupted.
")
                .map_err(|error| format!("Could not write the all-data removal marker: {error}"))
                .and_then(|()| {
                    file.sync_all()
                        .map_err(|error| format!("Could not save the all-data removal marker: {error}"))
                });
            drop(file);
            if let Err(error) = saved {
                let _ = remove_file_if_regular(&marker);
                return Err(error);
            }
            verify_protected_directory_chain(state)?;
            sync_directory(state)?;
            Ok(RemovalFence {
                path: Some(marker),
                created: true,
            })
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            let metadata = marker.symlink_metadata().map_err(|error| {
                format!("Could not inspect the all-data removal marker: {error}")
            })?;
            if metadata_is_alias(&metadata) || !metadata.is_file() {
                return Err(
                    "Refusing all-data removal through an aliased or malformed removal marker."
                        .to_string(),
                );
            }
            Ok(RemovalFence {
                path: Some(marker),
                created: false,
            })
        }
        Err(error) => Err(format!(
            "Could not create the all-data removal marker: {error}"
        )),
    }
}

fn release_new_removal_fence(fence: &RemovalFence) -> Result<(), String> {
    if !fence.created {
        return Ok(());
    }
    let Some(path) = fence.path.as_deref() else {
        return Ok(());
    };
    remove_file_if_regular(path)?;
    if let Some(parent) = path.parent() {
        sync_directory(parent)?;
    }
    Ok(())
}

fn protected_owned_chain(root: &Path) -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    let mut current = Some(root);
    let mut found_preflight_root = false;
    while let Some(path) = current {
        candidates.push(path.to_path_buf());
        if path
            .file_name()
            .is_some_and(|name| name == PREFLIGHT_HOME_DIRECTORY)
        {
            found_preflight_root = true;
            break;
        }
        current = path.parent();
    }
    if !found_preflight_root {
        return vec![root.to_path_buf()];
    }
    candidates.reverse();
    candidates
}

fn ensure_protected_directory_chain(root: &Path) -> Result<(), String> {
    let chain = protected_owned_chain(root);
    let anchor = chain
        .first()
        .and_then(|path| path.parent())
        .ok_or_else(|| "Protected report storage has no parent directory.".to_string())?;
    verify_real_directory(anchor, "report storage parent")?;
    let mut parent = anchor.to_path_buf();
    for directory in chain {
        verify_real_directory(&parent, "report storage parent")?;
        match directory.symlink_metadata() {
            Ok(metadata) => {
                if metadata_is_alias(&metadata) || !metadata.is_dir() {
                    return Err(format!(
                        "Refusing report storage through an aliased or non-directory path: {}",
                        directory.display()
                    ));
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                fs::create_dir(&directory).map_err(|error| {
                    format!(
                        "Could not create protected report storage {}: {error}",
                        directory.display()
                    )
                })?;
            }
            Err(error) => {
                return Err(format!(
                    "Could not inspect protected report storage {}: {error}",
                    directory.display()
                ));
            }
        }
        verify_real_directory(&directory, "protected report storage")?;
        make_private_directory(&directory)?;
        verify_real_directory(&parent, "report storage parent")?;
        parent = directory;
    }
    Ok(())
}

fn verify_protected_directory_chain(root: &Path) -> Result<(), String> {
    let chain = protected_owned_chain(root);
    let anchor = chain
        .first()
        .and_then(|path| path.parent())
        .ok_or_else(|| "Protected report storage has no parent directory.".to_string())?;
    verify_real_directory(anchor, "report storage parent")?;
    for directory in chain {
        verify_real_directory(&directory, "protected report storage")?;
    }
    Ok(())
}

fn verify_real_directory(path: &Path, label: &str) -> Result<(), String> {
    let metadata = path
        .symlink_metadata()
        .map_err(|error| format!("Could not inspect {label} {}: {error}", path.display()))?;
    if metadata_is_alias(&metadata) || !metadata.is_dir() {
        return Err(format!(
            "Refusing {label} through an aliased or non-directory path: {}",
            path.display()
        ));
    }
    Ok(())
}

#[cfg(windows)]
fn metadata_is_alias(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;
    metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
}

#[cfg(not(windows))]
fn metadata_is_alias(metadata: &fs::Metadata) -> bool {
    metadata.file_type().is_symlink()
}

#[cfg(test)]
fn test_mutate_before_persist(parent: &Path) -> Result<(), String> {
    let mutation = TEST_PERSIST_MUTATION
        .lock()
        .map_err(|_| "Test persist mutation lock is unavailable.".to_string())?
        .take();
    match mutation {
        None => Ok(()),
        Some(TestPersistMutation::RemoveRoot) => fs::remove_dir_all(parent)
            .map_err(|error| format!("Could not remove test authority root: {error}")),
        Some(TestPersistMutation::AliasRoot(external)) => {
            fs::remove_dir_all(parent)
                .map_err(|error| format!("Could not remove test authority root: {error}"))?;
            #[cfg(unix)]
            {
                std::os::unix::fs::symlink(external, parent)
                    .map_err(|error| format!("Could not alias test authority root: {error}"))
            }
            #[cfg(not(unix))]
            {
                let _ = external;
                Err("Alias-root test mutation is only available on Unix.".to_string())
            }
        }
    }
}

#[cfg(not(test))]
fn test_mutate_before_persist(_parent: &Path) -> Result<(), String> {
    Ok(())
}

fn now_millis() -> Result<u64, String> {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "The system clock is before 1970.".to_string())?
        .as_millis();
    u64::try_from(millis)
        .map_err(|_| "The system clock is outside the supported range.".to_string())
}

fn validate_case_id(value: &str) -> Result<(), String> {
    let valid = value.len() == 36
        && value.bytes().enumerate().all(|(index, byte)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()
            }
        });
    if valid {
        Ok(())
    } else {
        Err("The report case identity is invalid.".to_string())
    }
}

#[cfg(unix)]
fn make_private_directory(path: &Path) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
        .map_err(|error| format!("Could not protect report-authority storage: {error}"))
}

#[cfg(not(unix))]
fn make_private_directory(_path: &Path) -> Result<(), String> {
    Ok(())
}

#[cfg(unix)]
fn make_private_file(file: &fs::File) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    file.set_permissions(fs::Permissions::from_mode(0o600))
        .map_err(|error| format!("Could not protect saved report authority: {error}"))
}

#[cfg(not(unix))]
fn make_private_file(_file: &fs::File) -> Result<(), String> {
    Ok(())
}

#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<(), String> {
    fs::File::open(path)
        .and_then(|directory| directory.sync_all())
        .map_err(|error| format!("Could not durably save report authority: {error}"))
}

#[cfg(not(unix))]
fn sync_directory(_path: &Path) -> Result<(), String> {
    Ok(())
}

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
    fn expiry_pruning_removes_only_the_expired_authority() {
        let root = temp_root("expiry");
        let store = ReportAuthorityStore::create_at(root.clone()).unwrap();
        let expired = test_receipt_with_deadline(
            "11111111-1111-1111-1111-111111111111",
            "aa",
            "1970-01-01T00:00:00Z",
        );
        let live = test_receipt_with_deadline(
            "22222222-2222-2222-2222-222222222222",
            "bb",
            "9999-12-31T23:59:59.999Z",
        );
        let expired_path = accepted_path(&root, &expired.case_id);
        let live_path = accepted_path(&root, &live.case_id);
        persist_json_new(&expired_path, &expired).unwrap();
        persist_json_new(&live_path, &live).unwrap();
        let live_before = fs::read(&live_path).unwrap();

        let remaining = store.reports().unwrap();

        assert_eq!(1, remaining.len());
        assert_eq!(live.case_id, remaining[0].case_id);
        assert!(!expired_path.exists());
        assert_eq!(live_before, fs::read(&live_path).unwrap());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn restart_prunes_expired_authority_and_preserves_the_live_authority() {
        let root = temp_root("restart-expiry");
        let store = ReportAuthorityStore::create_at(root.clone()).unwrap();
        let expired = test_receipt_with_deadline(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "aa",
            "1970-01-01T00:00:00.000Z",
        );
        let live = test_receipt("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "bb");
        let expired_path = accepted_path(&root, &expired.case_id);
        let expired_pending_path = pending_path(&root, &expired.case_id);
        let live_path = accepted_path(&root, &live.case_id);
        persist_json_new(&expired_path, &expired).unwrap();
        persist_json_new(
            &expired_pending_path,
            &PendingAuthority {
                format: AUTHORITY_FORMAT.to_string(),
                case_id: expired.case_id.clone(),
                bytes: expired.bytes,
                sha256: expired.sha256.clone(),
                deletion: expired.deletion.clone(),
                created_at_millis: 1,
            },
        )
        .unwrap();
        persist_json_new(&live_path, &live).unwrap();
        let live_before = fs::read(&live_path).unwrap();
        drop(store);

        let restarted = ReportAuthorityStore::create_at(root.clone()).unwrap();
        let reports = restarted.reports().unwrap();

        assert_eq!(1, reports.len());
        assert_eq!(live.case_id, reports[0].case_id);
        assert!(!expired_path.exists());
        assert!(!expired_pending_path.exists());
        assert_eq!(live_before, fs::read(&live_path).unwrap());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn capacity_check_prunes_expired_authority_without_touching_live_cases() {
        let root = temp_root("capacity-expiry");
        let store = ReportAuthorityStore::create_at(root.clone()).unwrap();
        let expired = test_receipt_with_deadline(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "aa",
            "1970-01-01T00:00:00Z",
        );
        let expired_path = accepted_path(&root, &expired.case_id);
        persist_json_new(&expired_path, &expired).unwrap();
        let mut live_files = Vec::new();
        for index in 1..MAX_AUTHORITIES {
            let case_id = indexed_case_id(index);
            let byte = format!("{:02x}", index % 256);
            let receipt = test_receipt(&case_id, &byte);
            let path = accepted_path(&root, &case_id);
            persist_json_new(&path, &receipt).unwrap();
            live_files.push((case_id, fs::read(path).unwrap()));
        }

        let replacement = test_receipt("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "ee");
        let report = ReportUploadInput {
            output: "/tmp/replacement.zip".to_string(),
            bytes: replacement.bytes,
            sha256: replacement.sha256.clone(),
        };
        store
            .lifecycle()
            .granted(&test_grant(&replacement), &report)
            .unwrap();
        assert!(!expired_path.exists());
        for (case_id, before) in &live_files {
            let after = fs::read(accepted_path(&root, case_id)).unwrap();
            assert_eq!(before, &after);
        }

        store.persist_accepted(&replacement).unwrap();
        let reports = store.reports().unwrap();
        assert_eq!(MAX_AUTHORITIES, reports.len());
        assert!(
            reports
                .iter()
                .any(|report| report.case_id.as_str() == replacement.case_id.as_str())
        );
        for (case_id, _) in &live_files {
            assert!(
                reports
                    .iter()
                    .any(|report| report.case_id.as_str() == case_id.as_str())
            );
        }
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn invalid_deadline_fails_closed_before_expiry_deletion() {
        let root = temp_root("invalid-expiry");
        let store = ReportAuthorityStore::create_at(root.clone()).unwrap();
        let expired = test_receipt_with_deadline(
            "11111111-1111-1111-1111-111111111111",
            "aa",
            "1970-01-01T00:00:00Z",
        );
        let invalid =
            test_receipt_with_deadline("22222222-2222-2222-2222-222222222222", "bb", "invalid");
        let expired_path = accepted_path(&root, &expired.case_id);
        let invalid_path = accepted_path(&root, &invalid.case_id);
        persist_json_new(&expired_path, &expired).unwrap();
        persist_json_new(&invalid_path, &invalid).unwrap();

        let error = store.reports().unwrap_err();

        assert!(error.contains("invalid retention deadline"));
        assert!(expired_path.exists());
        assert!(invalid_path.exists());
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
        assert!(
            lifecycle
                .accepted(&test_receipt("33333333-3333-3333-3333-333333333333", "cc"))
                .is_err()
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn clear_after_generation_check_cannot_recreate_authority_storage() {
        let _serial = TEST_PERSIST_TEST_SERIAL.lock().unwrap();
        let root = temp_root("late-clear");
        let store = ReportAuthorityStore::create_at(root.clone()).unwrap();
        let receipt = test_receipt("44444444-4444-4444-4444-444444444444", "dd");
        let report = ReportUploadInput {
            output: "/tmp/report.zip".to_string(),
            bytes: receipt.bytes,
            sha256: receipt.sha256.clone(),
        };
        *TEST_PERSIST_MUTATION.lock().unwrap() = Some(TestPersistMutation::RemoveRoot);

        let error = store
            .lifecycle()
            .granted(&test_grant(&receipt), &report)
            .unwrap_err();

        assert!(error.contains("protected report storage") || error.contains("Could not inspect"));
        assert!(!root.exists());
    }

    #[cfg(unix)]
    #[test]
    fn preexisting_aliased_authority_root_is_refused_without_external_side_effects() {
        use std::os::unix::fs::{PermissionsExt, symlink};
        let root = temp_root("authority-alias");
        let external = temp_root("authority-external");
        fs::create_dir(&external).unwrap();
        fs::set_permissions(&external, fs::Permissions::from_mode(0o755)).unwrap();
        let sentinel = external.join("sentinel");
        fs::write(&sentinel, b"keep").unwrap();
        let mode_before = fs::metadata(&external).unwrap().permissions().mode() & 0o777;
        symlink(&external, &root).unwrap();

        assert!(ReportAuthorityStore::create_at(root.clone()).is_err());

        assert_eq!(b"keep", fs::read(&sentinel).unwrap().as_slice());
        assert_eq!(
            mode_before,
            fs::metadata(&external).unwrap().permissions().mode() & 0o777
        );
        fs::remove_file(root).unwrap();
        fs::remove_dir_all(external).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn preexisting_aliased_claim_root_is_refused_without_external_side_effects() {
        use std::os::unix::fs::{PermissionsExt, symlink};
        let root = temp_root("claim-alias");
        let external = temp_root("claim-external");
        fs::create_dir(&external).unwrap();
        fs::set_permissions(&external, fs::Permissions::from_mode(0o755)).unwrap();
        let sentinel = external.join("sentinel");
        fs::write(&sentinel, b"keep").unwrap();
        let mode_before = fs::metadata(&external).unwrap().permissions().mode() & 0o777;
        symlink(&external, &root).unwrap();

        assert!(claim_automatic_report_at(&root, "run-identity").is_err());

        assert_eq!(b"keep", fs::read(&sentinel).unwrap().as_slice());
        assert_eq!(
            mode_before,
            fs::metadata(&external).unwrap().permissions().mode() & 0o777
        );
        assert_eq!(1, fs::read_dir(&external).unwrap().count());
        fs::remove_file(root).unwrap();
        fs::remove_dir_all(external).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn root_swap_before_credential_publication_is_refused_without_external_write() {
        let _serial = TEST_PERSIST_TEST_SERIAL.lock().unwrap();
        use std::os::unix::fs::PermissionsExt;
        let root = temp_root("publish-swap");
        let external = temp_root("publish-external");
        let store = ReportAuthorityStore::create_at(root.clone()).unwrap();
        fs::create_dir(&external).unwrap();
        fs::set_permissions(&external, fs::Permissions::from_mode(0o755)).unwrap();
        let sentinel = external.join("sentinel");
        fs::write(&sentinel, b"keep").unwrap();
        let mode_before = fs::metadata(&external).unwrap().permissions().mode() & 0o777;
        let receipt = test_receipt("55555555-5555-5555-5555-555555555555", "ee");
        let report = ReportUploadInput {
            output: "/tmp/report.zip".to_string(),
            bytes: receipt.bytes,
            sha256: receipt.sha256.clone(),
        };
        *TEST_PERSIST_MUTATION.lock().unwrap() =
            Some(TestPersistMutation::AliasRoot(external.clone()));

        assert!(
            store
                .lifecycle()
                .granted(&test_grant(&receipt), &report)
                .is_err()
        );

        assert_eq!(b"keep", fs::read(&sentinel).unwrap().as_slice());
        assert_eq!(
            mode_before,
            fs::metadata(&external).unwrap().permissions().mode() & 0o777
        );
        assert_eq!(1, fs::read_dir(&external).unwrap().count());
        fs::remove_file(&root).unwrap();
        fs::remove_dir_all(external).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn case_removal_refuses_aliased_authority_root() {
        use std::os::unix::fs::symlink;
        let root = temp_root("remove-alias");
        let external = temp_root("remove-external");
        fs::create_dir(&external).unwrap();
        let case_id = "66666666-6666-6666-6666-666666666666";
        let external_case = accepted_path(&external, case_id);
        fs::write(&external_case, b"external authority").unwrap();
        symlink(&external, &root).unwrap();

        assert!(remove_case_at(&root, case_id).is_err());
        assert_eq!(
            b"external authority",
            fs::read(&external_case).unwrap().as_slice()
        );
        fs::remove_file(root).unwrap();
        fs::remove_dir_all(external).unwrap();
    }

    #[test]
    fn removal_fence_blocks_new_credentials_and_automatic_claims() {
        let base = temp_root("removal-fence");
        fs::create_dir(&base).unwrap();
        let preflight = base.join(PREFLIGHT_HOME_DIRECTORY);
        let root = preflight.join("support").join("report-authority");
        let store = ReportAuthorityStore::create_at(root.clone()).unwrap();
        let fence = establish_removal_fence(&root).unwrap();
        assert!(fence.created);

        let receipt = test_receipt("77777777-7777-7777-7777-777777777777", "ff");
        let report = ReportUploadInput {
            output: "/tmp/report.zip".to_string(),
            bytes: receipt.bytes,
            sha256: receipt.sha256.clone(),
        };
        let error = store
            .lifecycle()
            .granted(&test_grant(&receipt), &report)
            .unwrap_err();
        assert!(error.contains("removal is in progress"));
        assert!(!pending_path(&root, &receipt.case_id).exists());

        let claims = preflight.join("support").join("automatic-report-claims");
        assert!(claim_automatic_report_at(&claims, "blocked-run").is_err());
        assert!(!claims.exists());
        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn automatic_claim_is_atomic_across_threads() {
        let root = temp_root("claims");
        ensure_protected_directory_chain(&root).unwrap();
        let barrier = Arc::new(Barrier::new(3));
        let workers = (0..2)
            .map(|_| {
                let root = root.clone();
                let barrier = barrier.clone();
                std::thread::spawn(move || {
                    barrier.wait();
                    claim_automatic_report_at(&root, "same-run-identity").unwrap()
                })
            })
            .collect::<Vec<_>>();
        barrier.wait();
        let winners = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .filter(|won| *won)
            .count();
        assert_eq!(1, winners);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn authority_publication_never_replaces_an_existing_case_file() {
        let root = temp_root("create-new");
        ensure_protected_directory_chain(&root).unwrap();
        let path = root.join("authority.json");
        persist_json_new(&path, &serde_json::json!({"value": "first"})).unwrap();
        assert!(persist_json_new(&path, &serde_json::json!({"value": "second"})).is_err());
        let saved: serde_json::Value = read_json_regular(&path).unwrap();
        assert_eq!("first", saved["value"]);
        fs::remove_dir_all(root).unwrap();
    }

    fn temp_root(label: &str) -> PathBuf {
        let base = fs::canonicalize(std::env::temp_dir()).unwrap_or_else(|_| std::env::temp_dir());
        base.join(format!(
            "preflight-report-{label}-{}-{}",
            std::process::id(),
            now_millis().unwrap()
        ))
    }

    fn indexed_case_id(index: usize) -> String {
        format!("{index:08x}-0000-0000-0000-{index:012x}")
    }

    fn test_grant(receipt: &ReportReceipt) -> CreateReportCaseResponse {
        serde_json::from_value(serde_json::json!({
            "protocolVersion": 1,
            "caseId": receipt.case_id,
            "upload": {
                "method": "PUT",
                "url": format!("https://reports.example.com/v1/cases/{}/archive", receipt.case_id),
                "contentType": "application/zip",
                "expiresAt": "9999-12-31T23:59:59.999Z",
                "token": "upload.token"
            },
            "finalize": {
                "method": "POST",
                "url": format!("https://reports.example.com/v1/cases/{}/finalize", receipt.case_id),
                "token": "upload.token"
            },
            "deletion": {
                "method": receipt.deletion.method,
                "url": receipt.deletion.url,
                "token": receipt.deletion.token
            }
        }))
        .unwrap()
    }

    fn test_receipt(case_id: &str, byte: &str) -> ReportReceipt {
        test_receipt_with_deadline(case_id, byte, "9999-12-31T23:59:59.999Z")
    }

    fn test_receipt_with_deadline(
        case_id: &str,
        byte: &str,
        retention_deadline: &str,
    ) -> ReportReceipt {
        ReportReceipt {
            protocol_version: 1,
            case_id: case_id.to_string(),
            object_key: format!("accepted/{case_id}.zip"),
            bytes: 1024,
            sha256: byte.repeat(32),
            product_version: env!("CARGO_PKG_VERSION").to_string(),
            received_at: "2026-08-18T10:00:00Z".to_string(),
            retention_deadline: retention_deadline.to_string(),
            deletion: ReportDeletion {
                method: "DELETE".to_string(),
                url: format!("https://reports.example.com/v1/cases/{case_id}"),
                token: "token.value".to_string(),
            },
            signature: "signature".to_string(),
        }
    }
}
