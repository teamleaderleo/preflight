#[path = "../src/bound_directory.rs"]
mod bound_directory;

#[cfg(windows)]
use bound_directory::install_after_first_enumeration_entry_test_hook;
#[cfg(unix)]
use bound_directory::install_before_created_component_publish_test_hook;
use bound_directory::{
    BoundDirectory, file_is_inheritable_for_test, install_after_child_review_test_hook,
    install_after_component_open_test_hook, install_before_bounded_read_test_hook,
};
use std::ffi::OsStr;
#[cfg(windows)]
use std::ffi::OsString;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn retained_walk_creates_private_chain_and_relative_operations_work() {
    let base = temp_root("ordinary");
    let root = base.join("owned").join("support").join("authority");

    let directory =
        BoundDirectory::open_or_create(&base, Path::new("owned/support/authority")).unwrap();
    assert_private_directory(&base.join("owned"));
    assert_private_directory(&base.join("owned").join("support"));
    assert_private_directory(&root);

    let mut file = directory
        .create_new(OsStr::new("secret.bin"), 0o600)
        .unwrap();
    assert!(!file_is_inheritable_for_test(&file).unwrap());
    file.write_all(b"secret").unwrap();
    file.sync_all().unwrap();
    drop(file);

    assert!(!directory.is_inheritable_for_test().unwrap());
    assert!(directory.exists_regular(OsStr::new("secret.bin")).unwrap());
    assert_eq!(
        b"secret",
        directory
            .read_bytes(OsStr::new("secret.bin"), 64)
            .unwrap()
            .as_slice()
    );

    let first = directory.list_names().unwrap();
    let second = directory.list_names().unwrap();
    assert_eq!(first, second);
    assert_eq!(vec![OsStr::new("secret.bin").to_os_string()], first);

    directory.delete_file(OsStr::new("secret.bin")).unwrap();
    assert!(!root.join("secret.bin").exists());
    directory.sync().unwrap();
    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn retained_root_derives_children_without_reopening_a_public_path() {
    let base = temp_root("child-derive");
    let root = BoundDirectory::open_or_create(&base, Path::new("owned")).unwrap();
    let child = root
        .open_or_create_child(Path::new("support/authority"))
        .unwrap();

    let mut file = child.create_new(OsStr::new("marker"), 0o600).unwrap();
    file.write_all(b"owned").unwrap();
    drop(file);
    drop(child);

    let reopened = root
        .open_existing_child(Path::new("support/authority"))
        .unwrap();
    assert_eq!(
        b"owned",
        reopened
            .read_bytes(OsStr::new("marker"), 16)
            .unwrap()
            .as_slice()
    );

    drop(reopened);
    drop(root);
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn hard_read_ceiling_applies_after_the_opened_child_grows() {
    let base = temp_root("bounded-read");
    let root = base.join("owned").join("authority");
    let directory = BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap();

    let mut file = directory
        .create_new(OsStr::new("growing.bin"), 0o600)
        .unwrap();
    file.write_all(&[b'a'; 32]).unwrap();
    file.sync_all().unwrap();
    drop(file);

    let growing = root.join("growing.bin");
    install_before_bounded_read_test_hook(OsStr::new("growing.bin"), move || {
        let mut writer = OpenOptions::new().append(true).open(&growing).unwrap();
        writer.write_all(&[b'b'; 64]).unwrap();
        writer.sync_all().unwrap();
    });

    let error = directory
        .read_bytes(OsStr::new("growing.bin"), 64)
        .unwrap_err();
    assert_eq!(std::io::ErrorKind::InvalidData, error.kind());

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn enumeration_has_a_hard_entry_ceiling() {
    let base = temp_root("entry-limit");
    let directory = BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap();
    for name in ["a", "b", "c"] {
        let mut file = directory.create_new(OsStr::new(name), 0o600).unwrap();
        file.write_all(name.as_bytes()).unwrap();
    }

    let error = directory.list_names_with_limit_for_test(2).unwrap_err();
    assert_eq!(std::io::ErrorKind::InvalidData, error.kind());

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[cfg(windows)]
#[test]
fn overlapping_enumeration_uses_independent_cursor_state() {
    use std::sync::{Arc, mpsc};
    use std::thread;
    use std::time::Duration;

    const ENUM_TIMEOUT: Duration = Duration::from_secs(10);

    let base = temp_root("enumeration-concurrency");
    let directory =
        Arc::new(BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap());
    for name in ["alpha", "beta", "gamma"] {
        let mut file = directory.create_new(OsStr::new(name), 0o600).unwrap();
        file.write_all(name.as_bytes()).unwrap();
    }
    let expected = ["alpha", "beta", "gamma"]
        .into_iter()
        .map(OsString::from)
        .collect::<Vec<_>>();

    let (paused_tx, paused_rx) = mpsc::channel();
    let (resume_tx, resume_rx) = mpsc::channel();
    install_after_first_enumeration_entry_test_hook(move || {
        paused_tx.send(()).unwrap();
        resume_rx
            .recv_timeout(ENUM_TIMEOUT)
            .expect("first enumeration was not resumed before the test deadline");
    });

    let (first_result_tx, first_result_rx) = mpsc::channel();
    let first_directory = Arc::clone(&directory);
    let first_thread = thread::spawn(move || {
        first_result_tx
            .send(first_directory.list_names())
            .expect("first enumeration result receiver dropped");
    });

    match paused_rx.recv_timeout(ENUM_TIMEOUT) {
        Ok(()) => {}
        Err(mpsc::RecvTimeoutError::Timeout) => {
            if let Ok(result) = first_result_rx.try_recv() {
                panic!("first enumeration finished before reaching the pause hook: {result:?}");
            }
            panic!("first enumeration did not reach the pause hook before the test deadline");
        }
        Err(mpsc::RecvTimeoutError::Disconnected) => {
            let result = first_result_rx.try_recv().ok();
            panic!("first enumeration lost its pause hook before signaling: {result:?}");
        }
    }

    let (second_result_tx, second_result_rx) = mpsc::channel();
    let second_directory = Arc::clone(&directory);
    let second_thread = thread::spawn(move || {
        second_result_tx
            .send(second_directory.list_names())
            .expect("second enumeration result receiver dropped");
    });

    let second_result = second_result_rx.recv_timeout(ENUM_TIMEOUT);
    let _ = resume_tx.send(());
    let second = second_result
        .expect("second enumeration did not finish before the test deadline")
        .unwrap();
    let first = first_result_rx
        .recv_timeout(ENUM_TIMEOUT)
        .expect("first enumeration did not finish after resume before the test deadline")
        .unwrap();

    first_thread.join().unwrap();
    second_thread.join().unwrap();

    assert_eq!(expected, first);
    assert_eq!(expected, second);

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn enumerate_then_clear_sees_the_full_directory_again() {
    let base = temp_root("enumerate-clear");
    let root = base.join("owned").join("authority");
    let directory = BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap();
    for name in ["first", "second"] {
        let mut file = directory.create_new(OsStr::new(name), 0o600).unwrap();
        file.write_all(name.as_bytes()).unwrap();
    }

    assert_eq!(2, directory.list_names().unwrap().len());
    directory.clear_regular_files().unwrap();
    assert!(fs::read_dir(&root).unwrap().next().is_none());

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn clear_validates_the_whole_flat_namespace_before_deleting() {
    let base = temp_root("clear-preflight");
    let root = base.join("owned").join("authority");
    let directory = BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap();

    let mut first = directory.create_new(OsStr::new("aaa"), 0o600).unwrap();
    first.write_all(b"keep-a").unwrap();
    drop(first);
    let mut second = directory.create_new(OsStr::new("bbb"), 0o600).unwrap();
    second.write_all(b"keep-b").unwrap();
    drop(second);

    fs::create_dir(root.join("zzz")).unwrap();
    make_private_directory(&root.join("zzz"));

    assert!(directory.clear_regular_files().is_err());
    assert_eq!(b"keep-a", fs::read(root.join("aaa")).unwrap().as_slice());
    assert_eq!(b"keep-b", fs::read(root.join("bbb")).unwrap().as_slice());

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[cfg(unix)]
#[test]
fn fifo_child_is_rejected_without_waiting_for_a_writer() {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;

    let base = temp_root("fifo");
    let root = base.join("owned").join("authority");
    let directory = BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap();

    let fifo = root.join("fifo");
    let fifo_name = CString::new(fifo.as_os_str().as_bytes()).unwrap();
    assert_eq!(0, unsafe { libc::mkfifo(fifo_name.as_ptr(), 0o600) });

    let error = directory.open_regular(OsStr::new("fifo")).unwrap_err();
    assert_eq!(std::io::ErrorKind::InvalidData, error.kind());
    assert!(directory.clear_regular_files().is_err());

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[cfg(unix)]
#[test]
fn existing_broad_mode_protected_component_is_refused() {
    use std::os::unix::fs::PermissionsExt;

    let base = temp_root("broad-mode");
    let directory = BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap();
    drop(directory);

    fs::set_permissions(base.join("owned"), fs::Permissions::from_mode(0o755)).unwrap();

    let error = BoundDirectory::open_existing(&base, Path::new("owned/authority")).unwrap_err();
    assert_eq!(std::io::ErrorKind::PermissionDenied, error.kind());

    fs::remove_dir_all(base).unwrap();
}

#[cfg(target_os = "macos")]
#[test]
fn extended_acl_is_rejected_despite_private_mode_bits() {
    use std::os::unix::fs::PermissionsExt;
    use std::process::Command;

    let base = temp_root("extended-acl");
    let root = base.join("owned").join("authority");
    let directory = BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap();
    let file_path = root.join("secret.bin");
    let file = directory
        .create_new(OsStr::new("secret.bin"), 0o600)
        .unwrap();
    drop(file);

    let file_acl = Command::new("/bin/chmod")
        .arg("+a")
        .arg("everyone allow read")
        .arg(&file_path)
        .status()
        .unwrap();
    assert!(file_acl.success());
    assert_eq!(
        0,
        fs::metadata(&file_path).unwrap().permissions().mode() & 0o077
    );
    let error = directory.open_regular(OsStr::new("secret.bin")).unwrap_err();
    assert_eq!(std::io::ErrorKind::PermissionDenied, error.kind());

    let directory_acl = Command::new("/bin/chmod")
        .arg("+a")
        .arg("everyone allow list,search")
        .arg(&root)
        .status()
        .unwrap();
    assert!(directory_acl.success());
    assert_eq!(
        0o700,
        fs::metadata(&root).unwrap().permissions().mode() & 0o7777
    );
    let error = BoundDirectory::open_existing(&base, Path::new("owned/authority")).unwrap_err();
    assert_eq!(std::io::ErrorKind::PermissionDenied, error.kind());

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[cfg(unix)]
#[test]
fn public_winner_before_missing_component_publish_is_never_adopted() {
    let base = temp_root("create-publish-winner");
    let owned = BoundDirectory::open_or_create(&base, Path::new("owned")).unwrap();
    drop(owned);

    let owned_path = base.join("owned");
    let public_support = owned_path.join("publish-hook-support");
    let public_for_hook = public_support.clone();
    install_before_created_component_publish_test_hook(
        OsStr::new("publish-hook-support"),
        move || {
            fs::create_dir(&public_for_hook).unwrap();
            make_private_directory(&public_for_hook);
        },
    );

    let error =
        BoundDirectory::open_or_create(&base, Path::new("owned/publish-hook-support/authority"))
            .unwrap_err();
    assert_eq!(std::io::ErrorKind::AlreadyExists, error.kind());
    assert!(public_support.is_dir());
    assert!(!public_support.join("authority").exists());

    let staged = fs::read_dir(&owned_path)
        .unwrap()
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with(".bound-create-"))
        })
        .collect::<Vec<_>>();
    assert_eq!(1, staged.len());
    assert_private_directory(&staged[0]);
    assert!(!staged[0].join("authority").exists());

    fs::remove_dir_all(base).unwrap();
}

#[test]
fn ancestor_replacement_during_walk_cannot_redirect_later_creation() {
    let base = temp_root("ancestor-replacement");
    let owned = BoundDirectory::open_or_create(&base, Path::new("hook-owned")).unwrap();
    drop(owned);

    let public_owned = base.join("hook-owned");
    let reviewed_owned = base.join("reviewed-hook-owned");
    let replacement_owned = base.join("hook-owned");
    let public_for_hook = public_owned.clone();
    let reviewed_for_hook = reviewed_owned.clone();
    install_after_component_open_test_hook(OsStr::new("hook-owned"), move || {
        fs::rename(&public_for_hook, &reviewed_for_hook).unwrap();
        fs::create_dir(&replacement_owned).unwrap();
        make_private_directory(&replacement_owned);
    });

    let directory =
        BoundDirectory::open_or_create(&base, Path::new("hook-owned/support/authority")).unwrap();
    let mut file = directory.create_new(OsStr::new("marker"), 0o600).unwrap();
    file.write_all(b"reviewed-generation").unwrap();
    drop(file);

    assert_eq!(
        b"reviewed-generation",
        fs::read(
            reviewed_owned
                .join("support")
                .join("authority")
                .join("marker")
        )
        .unwrap()
        .as_slice()
    );
    assert!(!public_owned.join("support").exists());

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn final_root_replacement_does_not_redirect_relative_operations() {
    let base = temp_root("root-replacement");
    let public = base.join("owned").join("authority");
    let reviewed = base.join("owned").join("reviewed-authority");
    let directory = BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap();

    let mut original = directory.create_new(OsStr::new("old"), 0o600).unwrap();
    original.write_all(b"old-generation").unwrap();
    drop(original);

    fs::rename(&public, &reviewed).unwrap();
    fs::create_dir(&public).unwrap();
    make_private_directory(&public);
    write_private_file(&public.join("sentinel"), b"replacement");

    let replacement_before = fs::read(public.join("sentinel")).unwrap();
    let mut created = directory.create_new(OsStr::new("new"), 0o600).unwrap();
    created.write_all(b"reviewed").unwrap();
    drop(created);

    assert_eq!(
        b"reviewed",
        directory
            .read_bytes(OsStr::new("new"), 32)
            .unwrap()
            .as_slice()
    );
    directory.delete_file(OsStr::new("old")).unwrap();
    assert!(!reviewed.join("old").exists());
    assert_eq!(
        replacement_before,
        fs::read(public.join("sentinel")).unwrap()
    );
    assert!(!public.join("new").exists());

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn child_replacement_after_review_never_deletes_the_later_winner() {
    let base = temp_root("child-replacement");
    let root = base.join("owned").join("authority");
    let public = root.join("victim");
    let reviewed_side = root.join("reviewed-side");
    let directory = BoundDirectory::open_or_create(&base, Path::new("owned/authority")).unwrap();

    let mut file = directory.create_new(OsStr::new("victim"), 0o600).unwrap();
    file.write_all(b"reviewed").unwrap();
    drop(file);

    let public_for_hook = public.clone();
    let side_for_hook = reviewed_side.clone();
    install_after_child_review_test_hook(OsStr::new("victim"), move || {
        fs::rename(&public_for_hook, &side_for_hook).unwrap();
        write_private_file(&public_for_hook, b"later-winner");
    });

    let result = directory.delete_file(OsStr::new("victim"));
    #[cfg(unix)]
    assert!(result.is_err());
    #[cfg(windows)]
    result.unwrap();

    assert_eq!(b"later-winner", fs::read(&public).unwrap().as_slice());
    #[cfg(unix)]
    assert_eq!(b"reviewed", fs::read(&reviewed_side).unwrap().as_slice());
    #[cfg(windows)]
    assert!(!reviewed_side.exists());

    drop(directory);
    fs::remove_dir_all(base).unwrap();
}

#[cfg(unix)]
#[test]
fn preexisting_alias_ancestor_is_refused() {
    use std::os::unix::fs::symlink;

    let base = temp_root("alias");
    let external = base.join("external");
    fs::create_dir(&external).unwrap();
    make_private_directory(&external);
    symlink(&external, base.join("owned")).unwrap();

    assert!(BoundDirectory::open_or_create(&base, Path::new("owned/authority")).is_err());
    assert!(!external.join("authority").exists());

    fs::remove_file(base.join("owned")).unwrap();
    fs::remove_dir_all(base).unwrap();
}

#[cfg(windows)]
#[test]
fn preexisting_reparse_ancestor_is_refused() {
    use std::process::Command;

    let base = temp_root("reparse");
    let external = base.join("external");
    fs::create_dir(&external).unwrap();
    let public = base.join("owned");
    let status = Command::new("cmd")
        .arg("/C")
        .arg("mklink")
        .arg("/J")
        .arg(&public)
        .arg(&external)
        .status()
        .unwrap();
    assert!(status.success());

    assert!(BoundDirectory::open_or_create(&base, Path::new("owned/authority")).is_err());
    assert!(!external.join("authority").exists());

    fs::remove_dir(public).unwrap();
    fs::remove_dir_all(base).unwrap();
}

fn write_private_file(path: &Path, bytes: &[u8]) {
    fs::write(path, bytes).unwrap();
    make_private_file(path);
}

#[cfg(unix)]
fn make_private_file(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600)).unwrap();
}

#[cfg(windows)]
fn make_private_file(_path: &Path) {}

#[cfg(unix)]
fn make_private_directory(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700)).unwrap();
}

#[cfg(windows)]
fn make_private_directory(_path: &Path) {}

#[cfg(unix)]
fn assert_private_directory(path: &Path) {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};

    let metadata = fs::metadata(path).unwrap();
    assert_eq!(unsafe { libc::geteuid() }, metadata.uid());
    assert_eq!(0o700, metadata.permissions().mode() & 0o7777);
}

#[cfg(windows)]
fn assert_private_directory(path: &Path) {
    assert!(fs::metadata(path).unwrap().is_dir());
}

fn temp_root(label: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let temp = fs::canonicalize(std::env::temp_dir()).unwrap();
    let root = temp.join(format!(
        "preflight-bound-directory-{label}-{}-{nanos}",
        std::process::id()
    ));
    if root.exists() {
        fs::remove_dir_all(&root).unwrap();
    }
    fs::create_dir(&root).unwrap();
    root
}
