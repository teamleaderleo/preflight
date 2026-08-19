use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::Path;

const MAGIC: &[u8; 4] = b"SPRA";
const VERSION: u32 = 1;
const HEADER_BYTES: usize = 8;
const CHECKSUM_BYTES: usize = 32;
const MAX_RECORD_BYTES: usize = 64 * 1024;
const MAX_JOURNAL_BYTES: u64 = 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case", deny_unknown_fields)]
pub(crate) enum JournalRecord {
    Grant {
        case_id: String,
        delete_secret: String,
        retention_deadline: String,
    },
    Accepted {
        case_id: String,
    },
    Deleted {
        case_id: String,
    },
    Dismissed {
        case_id: String,
    },
    Expired {
        case_id: String,
    },
    AutoClaim {
        run_identity: String,
    },
    AutoClaimReleased {
        run_identity: String,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct LiveCase {
    pub(crate) delete_secret: String,
    pub(crate) retention_deadline: String,
    pub(crate) accepted: bool,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct JournalState {
    pub(crate) cases: BTreeMap<String, LiveCase>,
    pub(crate) automatic_claims: BTreeSet<String>,
}

pub(crate) fn append(path: &Path, record: &JournalRecord) -> Result<(), String> {
    let payload = serde_json::to_vec(record)
        .map_err(|error| format!("Could not encode report-authority journal record: {error}"))?;
    if payload.len() > MAX_RECORD_BYTES {
        return Err("Report-authority journal record exceeds the safety limit.".to_string());
    }

    let current_len = fs::metadata(path).map(|metadata| metadata.len()).unwrap_or(0);
    let additional = if current_len == 0 {
        HEADER_BYTES as u64
    } else {
        0
    } + 4
        + payload.len() as u64
        + CHECKSUM_BYTES as u64;
    if current_len.saturating_add(additional) > MAX_JOURNAL_BYTES {
        return Err("Report-authority journal exceeds the safety limit.".to_string());
    }

    if current_len != 0 {
        validate_header(path)?;
    }

    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .map_err(|error| format!("Could not open report-authority journal: {error}"))?;
    if current_len == 0 {
        file.write_all(MAGIC)
            .and_then(|_| file.write_all(&VERSION.to_be_bytes()))
            .map_err(|error| format!("Could not initialize report-authority journal: {error}"))?;
    }
    file.write_all(&(payload.len() as u32).to_be_bytes())
        .and_then(|_| file.write_all(&payload))
        .and_then(|_| file.write_all(&Sha256::digest(&payload)))
        .map_err(|error| format!("Could not append report-authority journal: {error}"))?;
    file.sync_all()
        .map_err(|error| format!("Could not durably save report authority: {error}"))
}

pub(crate) fn replay(path: &Path) -> Result<JournalState, String> {
    let metadata = fs::metadata(path)
        .map_err(|error| format!("Could not inspect report-authority journal: {error}"))?;
    if metadata.len() < HEADER_BYTES as u64 || metadata.len() > MAX_JOURNAL_BYTES {
        return Err("Report-authority journal size is invalid.".to_string());
    }

    let mut file = File::open(path)
        .map_err(|error| format!("Could not open report-authority journal: {error}"))?;
    let mut limited = (&mut file).take(MAX_JOURNAL_BYTES + 1);
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    limited
        .read_to_end(&mut bytes)
        .map_err(|error| format!("Could not read report-authority journal: {error}"))?;
    if bytes.len() as u64 > MAX_JOURNAL_BYTES {
        return Err("Report-authority journal exceeds the safety limit while reading.".to_string());
    }
    decode(&bytes)
}

fn validate_header(path: &Path) -> Result<(), String> {
    let mut file = File::open(path)
        .map_err(|error| format!("Could not open report-authority journal: {error}"))?;
    let mut header = [0_u8; HEADER_BYTES];
    file.read_exact(&mut header)
        .map_err(|error| format!("Could not read report-authority journal header: {error}"))?;
    if &header[..4] != MAGIC || u32::from_be_bytes(header[4..8].try_into().unwrap()) != VERSION {
        return Err("Report-authority journal header is invalid.".to_string());
    }
    Ok(())
}

fn decode(bytes: &[u8]) -> Result<JournalState, String> {
    if bytes.len() < HEADER_BYTES
        || &bytes[..4] != MAGIC
        || u32::from_be_bytes(bytes[4..8].try_into().unwrap()) != VERSION
    {
        return Err("Report-authority journal header is invalid.".to_string());
    }

    let mut state = JournalState::default();
    let mut offset = HEADER_BYTES;
    while offset < bytes.len() {
        if bytes.len() - offset < 4 {
            return Err("Report-authority journal has a torn record length.".to_string());
        }
        let length = u32::from_be_bytes(bytes[offset..offset + 4].try_into().unwrap()) as usize;
        offset += 4;
        if length == 0 || length > MAX_RECORD_BYTES {
            return Err("Report-authority journal record length is invalid.".to_string());
        }
        let end = offset
            .checked_add(length)
            .and_then(|value| value.checked_add(CHECKSUM_BYTES))
            .ok_or_else(|| "Report-authority journal record length overflowed.".to_string())?;
        if end > bytes.len() {
            return Err("Report-authority journal ends inside a record.".to_string());
        }
        let payload = &bytes[offset..offset + length];
        let checksum = &bytes[offset + length..end];
        if checksum != Sha256::digest(payload).as_slice() {
            return Err("Report-authority journal record checksum mismatch.".to_string());
        }
        let record: JournalRecord = serde_json::from_slice(payload)
            .map_err(|error| format!("Report-authority journal record is invalid: {error}"))?;
        apply(&mut state, record)?;
        offset = end;
    }
    Ok(state)
}

fn apply(state: &mut JournalState, record: JournalRecord) -> Result<(), String> {
    match record {
        JournalRecord::Grant {
            case_id,
            delete_secret,
            retention_deadline,
        } => {
            if state.cases.contains_key(&case_id) {
                return Err(format!("Duplicate report-authority grant for case {case_id}."));
            }
            state.cases.insert(
                case_id,
                LiveCase {
                    delete_secret,
                    retention_deadline,
                    accepted: false,
                },
            );
        }
        JournalRecord::Accepted { case_id } => {
            let case = state
                .cases
                .get_mut(&case_id)
                .ok_or_else(|| format!("Accepted report case {case_id} has no durable grant."))?;
            case.accepted = true;
        }
        JournalRecord::Deleted { case_id }
        | JournalRecord::Dismissed { case_id }
        | JournalRecord::Expired { case_id } => {
            state.cases.remove(&case_id);
        }
        JournalRecord::AutoClaim { run_identity } => {
            if !state.automatic_claims.insert(run_identity.clone()) {
                return Err(format!("Duplicate automatic report claim for {run_identity}."));
            }
        }
        JournalRecord::AutoClaimReleased { run_identity } => {
            state.automatic_claims.remove(&run_identity);
        }
    }
    Ok(())
}
