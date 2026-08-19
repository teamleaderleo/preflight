#[path = "../src/report_authority_journal.rs"]
mod report_authority_journal;

use report_authority_journal::{append, replay, JournalRecord};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn journal_state_debug_exposes_counts_not_bearer_material() {
    let root = temp_root("redacted-debug");
    let journal = root.join("authority.spra");
    append(
        &journal,
        &JournalRecord::Grant {
            case_id: "case-secret-label".to_string(),
            delete_secret: "bearer-must-never-appear-in-debug".to_string(),
            retention_deadline: "2026-09-01T00:00:00Z".to_string(),
        },
    )
    .unwrap();
    append(
        &journal,
        &JournalRecord::AutoClaim {
            run_identity: "run-identity-should-not-appear".to_string(),
        },
    )
    .unwrap();

    let debug = format!("{:?}", replay(&journal).unwrap());

    assert_eq!(
        "JournalState { case_count: 1, automatic_claim_count: 1 }",
        debug
    );
    assert!(!debug.contains("bearer-must-never-appear-in-debug"));
    assert!(!debug.contains("case-secret-label"));
    assert!(!debug.contains("run-identity-should-not-appear"));
    fs::remove_dir_all(root).unwrap();
}

fn temp_root(label: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let root = std::env::temp_dir().join(format!(
        "preflight-report-journal-{label}-{}-{nanos}",
        std::process::id()
    ));
    if Path::new(&root).exists() {
        fs::remove_dir_all(&root).unwrap();
    }
    fs::create_dir_all(&root).unwrap();
    root
}
