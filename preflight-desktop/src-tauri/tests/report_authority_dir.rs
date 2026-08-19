#[path = "../src/report_authority_dir.rs"]
mod report_authority_dir;

use report_authority_dir::BoundDirectory;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn relative_create_read_and_delete_stay_in_the_opened_directory() {
    let base = temp_root("ordinary");
    let root = base.join("authority");
    fs::create_dir_all(&root).unwrap();
    let directory = BoundDirectory::open(&root).unwrap();

    let mut file = directory.create_new("secret.json", 0o600).unwrap();
    file.write_all(b"secret").unwrap();
    file.sync_all().unwrap();
    drop(file);

    assert_eq!(b"secret", directory.read_bytes("secret.json", 64).unwrap().as_slice());
    directory.delete_file("secret.json").unwrap();
    assert!(!root.join("secret.json").exists());
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn cleanup_after_public_root_replacement_cannot_touch_the_replacement() {
    let base = temp_root("replacement");
    let root = base.join("authority");
    let reviewed = base.join("reviewed-authority");
    fs::create_dir_all(&root).unwrap();
    let directory = BoundDirectory::open(&root).unwrap();
    let mut file = directory.create_new("owned.json", 0o600).unwrap();
    file.write_all(b"owned").unwrap();
    file.sync_all().unwrap();
    drop(file);

    fs::rename(&root, &reviewed).unwrap();
    fs::create_dir(&root).unwrap();
    fs::write(root.join("owned.json"), b"external").unwrap();

    assert!(directory.require_current().is_err());
    directory.delete_file("owned.json").unwrap();
    assert!(!reviewed.join("owned.json").exists());
    assert_eq!(b"external", fs::read(root.join("owned.json")).unwrap().as_slice());
    fs::remove_dir_all(base).unwrap();
}

#[cfg(unix)]
#[test]
fn preexisting_alias_ancestor_is_refused() {
    use std::os::unix::fs::symlink;

    let base = temp_root("alias");
    let public = base.join("public");
    let external = base.join("external");
    fs::create_dir_all(external.join("support").join("report-authority")).unwrap();
    symlink(&external, &public).unwrap();

    assert!(
        BoundDirectory::open(&public.join("support").join("report-authority")).is_err()
    );
    assert!(fs::read_dir(external.join("support").join("report-authority"))
        .unwrap()
        .next()
        .is_none());
    fs::remove_file(public).unwrap();
    fs::remove_dir_all(base).unwrap();
}

fn temp_root(label: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let root = std::env::temp_dir().join(format!(
        "preflight-report-bound-{label}-{}-{nanos}",
        std::process::id()
    ));
    if Path::new(&root).exists() {
        fs::remove_dir_all(&root).unwrap();
    }
    fs::create_dir_all(&root).unwrap();
    root
}
