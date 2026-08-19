#[path = "../src/report_authority_dir.rs"]
mod report_authority_dir;

use report_authority_dir::BoundDirectory;
use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn relative_create_read_enumerate_and_delete_stay_in_the_opened_directory() {
    let base = temp_root("ordinary");
    let root = base.join("authority");
    fs::create_dir_all(&root).unwrap();
    let directory = BoundDirectory::open(&root).unwrap();

    let mut file = directory.create_new("secret.json", 0o600).unwrap();
    file.write_all(b"secret").unwrap();
    file.sync_all().unwrap();
    drop(file);

    assert_eq!(
        b"secret",
        directory.read_bytes("secret.json", 64).unwrap().as_slice()
    );
    assert_eq!(
        vec!["secret.json".to_string()],
        directory.list_names().unwrap()
    );
    directory.delete_file("secret.json").unwrap();
    assert!(!root.join("secret.json").exists());
    fs::remove_dir_all(base).unwrap();
}

#[cfg(unix)]
#[test]
fn retained_directory_and_entry_handles_are_close_on_exec() {
    use std::os::fd::AsRawFd;

    let base = temp_root("close-on-exec");
    let root = base.join("authority");
    fs::create_dir_all(&root).unwrap();
    let directory = BoundDirectory::open(&root).unwrap();
    assert!(directory.is_close_on_exec().unwrap());

    let file = directory.create_new("secret.json", 0o600).unwrap();
    let flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFD) };
    assert!(flags >= 0);
    assert_ne!(flags & libc::FD_CLOEXEC, 0);
    drop(file);

    let file = directory.open_regular("secret.json").unwrap();
    let flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFD) };
    assert!(flags >= 0);
    assert_ne!(flags & libc::FD_CLOEXEC, 0);
    drop(file);

    fs::remove_dir_all(base).unwrap();
}

#[test]
fn create_after_public_root_replacement_stays_in_the_reviewed_generation() {
    let base = temp_root("create-replacement");
    let root = base.join("authority");
    let reviewed = base.join("reviewed-authority");
    fs::create_dir_all(&root).unwrap();
    let directory = BoundDirectory::open(&root).unwrap();

    fs::rename(&root, &reviewed).unwrap();
    fs::create_dir(&root).unwrap();
    fs::write(root.join("secret.json"), b"external").unwrap();
    let external_before = snapshot(&root);

    let mut file = directory.create_new("secret.json", 0o600).unwrap();
    file.write_all(b"owned").unwrap();
    file.sync_all().unwrap();
    drop(file);

    assert!(directory.require_current().is_err());
    assert_eq!(
        b"owned",
        fs::read(reviewed.join("secret.json")).unwrap().as_slice()
    );
    assert_eq!(external_before, snapshot(&root));
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn prune_delete_after_public_root_replacement_cannot_touch_the_replacement() {
    let base = temp_root("delete-replacement");
    let root = base.join("authority");
    let reviewed = base.join("reviewed-authority");
    fs::create_dir_all(&root).unwrap();
    fs::write(root.join("expired.accepted.json"), b"owned").unwrap();
    let directory = BoundDirectory::open(&root).unwrap();

    fs::rename(&root, &reviewed).unwrap();
    fs::create_dir(&root).unwrap();
    fs::write(root.join("expired.accepted.json"), b"external").unwrap();
    fs::write(root.join("sentinel"), b"external-sentinel").unwrap();
    let external_before = snapshot(&root);

    assert_eq!(
        vec!["expired.accepted.json".to_string()],
        directory.list_names().unwrap()
    );
    directory.delete_file("expired.accepted.json").unwrap();

    assert!(directory.require_current().is_err());
    assert!(!reviewed.join("expired.accepted.json").exists());
    assert_eq!(external_before, snapshot(&root));
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn clear_after_public_root_replacement_clears_only_the_reviewed_generation() {
    let base = temp_root("clear-replacement");
    let root = base.join("authority");
    let reviewed = base.join("reviewed-authority");
    fs::create_dir_all(&root).unwrap();
    fs::write(root.join("generation"), b"owned-generation").unwrap();
    fs::write(root.join("case.pending.json"), b"owned-pending").unwrap();
    let directory = BoundDirectory::open(&root).unwrap();

    fs::rename(&root, &reviewed).unwrap();
    fs::create_dir(&root).unwrap();
    fs::write(root.join("generation"), b"external-generation").unwrap();
    fs::write(root.join("case.pending.json"), b"external-pending").unwrap();
    fs::write(root.join("sentinel"), b"external-sentinel").unwrap();
    let external_before = snapshot(&root);

    directory.clear_regular_files().unwrap();

    assert!(directory.require_current().is_err());
    assert!(fs::read_dir(&reviewed).unwrap().next().is_none());
    assert_eq!(external_before, snapshot(&root));
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

    assert!(BoundDirectory::open(&public.join("support").join("report-authority")).is_err());
    assert!(
        fs::read_dir(external.join("support").join("report-authority"))
            .unwrap()
            .next()
            .is_none()
    );
    fs::remove_file(public).unwrap();
    fs::remove_dir_all(base).unwrap();
}

#[cfg(windows)]
#[test]
fn preexisting_reparse_ancestor_is_refused() {
    use std::process::Command;

    let base = temp_root("reparse");
    let public = base.join("public");
    let external = base.join("external");
    fs::create_dir_all(external.join("support").join("report-authority")).unwrap();
    let status = Command::new("cmd")
        .arg("/C")
        .arg("mklink")
        .arg("/J")
        .arg(&public)
        .arg(&external)
        .status()
        .unwrap();
    assert!(status.success());

    assert!(BoundDirectory::open(&public.join("support").join("report-authority")).is_err());
    assert!(
        fs::read_dir(external.join("support").join("report-authority"))
            .unwrap()
            .next()
            .is_none()
    );
    fs::remove_dir(public).unwrap();
    fs::remove_dir_all(base).unwrap();
}

fn snapshot(root: &Path) -> BTreeMap<String, Vec<u8>> {
    fs::read_dir(root)
        .unwrap()
        .map(|entry| {
            let entry = entry.unwrap();
            (
                entry.file_name().to_string_lossy().into_owned(),
                fs::read(entry.path()).unwrap(),
            )
        })
        .collect()
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
