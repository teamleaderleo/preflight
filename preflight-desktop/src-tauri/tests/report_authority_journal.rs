#[path = "../src/report_authority_journal.rs"]
mod report_authority_journal;

use report_authority_journal::{append, replay, JournalRecord};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn grant_is_durable_before_acceptance_and_restart_replays_it() {
    let root = temp_root("grant");
    let journal = root.join("authority.spra");

    append(
        &journal,
        &JournalRecord::Grant {
            case_id: "PF-CASE-A".to_string(),
            delete_secret: "delete-a".to_string(),
            retention_deadline: "2026-09-01T00:00:00Z".to_string(),
        },
    )
    .unwrap();

    let pending = replay(&journal).unwrap();
    assert!(!pending.cases["PF-CASE-A"].accepted);
    assert_eq!("delete-a", pending.cases["PF-CASE-A"].delete_secret);

    append(
        &journal,
        &JournalRecord::Accepted {
            case_id: "PF-CASE-A".to_string(),
        },
    )
    .unwrap();
    assert!(replay(&journal).unwrap().cases["PF-CASE-A"].accepted);
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn expiring_one_case_does_not_remove_another() {
    let root = temp_root("expiry");
    let journal = root.join("authority.spra");
    for case in ["A", "B"] {
        append(
            &journal,
            &JournalRecord::Grant {
                case_id: case.to_string(),
                delete_secret: format!("delete-{case}"),
                retention_deadline: "2026-09-01T00:00:00Z".to_string(),
            },
        )
        .unwrap();
        append(
            &journal,
            &JournalRecord::Accepted {
                case_id: case.to_string(),
            },
        )
        .unwrap();
    }
    append(
        &journal,
        &JournalRecord::Expired {
            case_id: "A".to_string(),
        },
    )
    .unwrap();

    let state = replay(&journal).unwrap();
    assert!(!state.cases.contains_key("A"));
    assert_eq!("delete-B", state.cases["B"].delete_secret);
    assert!(state.cases["B"].accepted);
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn duplicate_automatic_claim_is_rejected_before_append() {
    let root = temp_root("claim-duplicate");
    let journal = root.join("authority.spra");
    let claim = JournalRecord::AutoClaim {
        run_identity: "run-a".to_string(),
    };
    append(&journal, &claim).unwrap();
    let before = fs::read(&journal).unwrap();

    let error = append(&journal, &claim).unwrap_err();

    assert!(error.contains("Duplicate automatic report claim"));
    assert_eq!(before, fs::read(&journal).unwrap());
    assert!(replay(&journal).unwrap().automatic_claims.contains("run-a"));
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn automatic_claim_can_be_released() {
    let root = temp_root("claim-release");
    let journal = root.join("authority.spra");
    append(
        &journal,
        &JournalRecord::AutoClaim {
            run_identity: "run-a".to_string(),
        },
    )
    .unwrap();
    append(
        &journal,
        &JournalRecord::AutoClaimReleased {
            run_identity: "run-a".to_string(),
        },
    )
    .unwrap();
    assert!(replay(&journal).unwrap().automatic_claims.is_empty());
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn checksum_validity_and_torn_tail_fail_closed() {
    let root = temp_root("corrupt");
    let journal = root.join("authority.spra");
    append(
        &journal,
        &JournalRecord::Grant {
            case_id: "A".to_string(),
            delete_secret: "delete-a".to_string(),
            retention_deadline: "2026-09-01T00:00:00Z".to_string(),
        },
    )
    .unwrap();

    let original = fs::read(&journal).unwrap();
    let mut corrupt = original.clone();
    let payload_byte = 12;
    corrupt[payload_byte] ^= 1;
    fs::write(&journal, &corrupt).unwrap();
    assert!(replay(&journal).unwrap_err().contains("checksum"));

    fs::write(&journal, &original[..original.len() - 1]).unwrap();
    assert!(replay(&journal).unwrap_err().contains("inside a record"));
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn accepted_without_grant_is_rejected_before_append() {
    let root = temp_root("accepted-without-grant");
    let journal = root.join("authority.spra");

    let error = append(
        &journal,
        &JournalRecord::Accepted {
            case_id: "A".to_string(),
        },
    )
    .unwrap_err();

    assert!(error.contains("no durable grant"));
    assert!(!journal.exists());
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
