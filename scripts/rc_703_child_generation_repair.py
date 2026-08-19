from pathlib import Path

source = Path("preflight-desktop/src-tauri/src/report_authority_dir.rs")
text = source.read_text()

old_import = """use std::path::{Path, PathBuf};
use std::time::SystemTime;
"""
new_import = """use std::path::{Path, PathBuf};
#[cfg(test)]
use std::sync::Mutex;
use std::time::SystemTime;
"""
assert old_import in text
text = text.replace(old_import, new_import, 1)

marker = """pub(crate) struct BoundDirectory {
    path: PathBuf,
    file: File,
}

impl BoundDirectory {
"""
hook_block = """pub(crate) struct BoundDirectory {
    path: PathBuf,
    file: File,
}

#[cfg(test)]
struct TestHook {
    name: String,
    callback: Box<dyn FnOnce() + Send>,
}

#[cfg(test)]
static TEST_BEFORE_DELETE: Mutex<Option<TestHook>> = Mutex::new(None);
#[cfg(test)]
static TEST_BEFORE_READ: Mutex<Option<TestHook>> = Mutex::new(None);

#[cfg(test)]
pub(crate) fn install_before_delete_test_hook(
    name: &str,
    callback: impl FnOnce() + Send + 'static,
) {
    *TEST_BEFORE_DELETE.lock().expect("delete test hook lock") = Some(TestHook {
        name: name.to_string(),
        callback: Box::new(callback),
    });
}

#[cfg(test)]
pub(crate) fn install_before_read_test_hook(
    name: &str,
    callback: impl FnOnce() + Send + 'static,
) {
    *TEST_BEFORE_READ.lock().expect("read test hook lock") = Some(TestHook {
        name: name.to_string(),
        callback: Box::new(callback),
    });
}

#[cfg(test)]
fn run_test_hook(slot: &Mutex<Option<TestHook>>, name: &str) {
    let hook = {
        let mut slot = slot.lock().expect("report-authority test hook lock");
        if slot.as_ref().is_some_and(|hook| hook.name == name) {
            slot.take()
        } else {
            None
        }
    };
    if let Some(hook) = hook {
        (hook.callback)();
    }
}

#[cfg(test)]
fn run_before_delete_test_hook(name: &str) {
    run_test_hook(&TEST_BEFORE_DELETE, name);
}

#[cfg(not(test))]
fn run_before_delete_test_hook(_name: &str) {}

#[cfg(test)]
fn run_before_read_test_hook(name: &str) {
    run_test_hook(&TEST_BEFORE_READ, name);
}

#[cfg(not(test))]
fn run_before_read_test_hook(_name: &str) {}

impl BoundDirectory {
"""
assert marker in text
text = text.replace(marker, hook_block, 1)

old_read = """    pub(crate) fn read_bytes(&self, name: &str, max_bytes: u64) -> Result<Vec<u8>, String> {
        let mut file = self.open_regular(name).map_err(|error| {
            format!("Could not open parent-bound report-authority entry {name}: {error}")
        })?;
        let metadata = file.metadata().map_err(|error| {
            format!("Could not inspect parent-bound report-authority entry {name}: {error}")
        })?;
        if metadata.len() > max_bytes {
            return Err("Saved report authority is unexpectedly large.".to_string());
        }
        let mut bytes = Vec::with_capacity(metadata.len() as usize);
        file.read_to_end(&mut bytes).map_err(|error| {
            format!("Could not read parent-bound report-authority entry {name}: {error}")
        })?;
        Ok(bytes)
    }
"""
new_read = """    pub(crate) fn read_bytes(&self, name: &str, max_bytes: u64) -> Result<Vec<u8>, String> {
        let file = self.open_regular(name).map_err(|error| {
            format!("Could not open parent-bound report-authority entry {name}: {error}")
        })?;
        let metadata = file.metadata().map_err(|error| {
            format!("Could not inspect parent-bound report-authority entry {name}: {error}")
        })?;
        if metadata.len() > max_bytes {
            return Err("Saved report authority is unexpectedly large.".to_string());
        }
        run_before_read_test_hook(name);
        let mut bytes = Vec::with_capacity(metadata.len() as usize);
        let mut limited = file.take(max_bytes.saturating_add(1));
        limited.read_to_end(&mut bytes).map_err(|error| {
            format!("Could not read parent-bound report-authority entry {name}: {error}")
        })?;
        if bytes.len() as u64 > max_bytes {
            return Err("Saved report authority is unexpectedly large.".to_string());
        }
        Ok(bytes)
    }
"""
assert old_read in text
text = text.replace(old_read, new_read, 1)

old_unix_import = """    use std::os::unix::fs::MetadataExt;
    use std::path::Component;
"""
new_unix_import = """    use std::os::unix::fs::MetadataExt;
    use std::path::Component;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_DELETE_ANCHOR: AtomicU64 = AtomicU64::new(1);

    #[cfg(target_os = "macos")]
    unsafe extern "C" {
        fn renameatx_np(
            from_fd: libc::c_int,
            from: *const libc::c_char,
            to_fd: libc::c_int,
            to: *const libc::c_char,
            flags: libc::c_uint,
        ) -> libc::c_int;
    }
"""
assert old_unix_import in text
text = text.replace(old_unix_import, new_unix_import, 1)

old_delete = """    pub(super) fn delete_file(parent: &File, name: &str) -> io::Result<()> {
        let file = open_regular(parent, name)?;
        if !file.metadata()?.is_file() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "parent-bound report-authority entry is not a regular file",
            ));
        }
        drop(file);
        let name = CString::new(name)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "filename contains NUL"))?;
        if unsafe { libc::unlinkat(parent.as_raw_fd(), name.as_ptr(), 0) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    pub(super) fn sync_directory(directory: &File) -> io::Result<()> {
"""
new_delete = """    pub(super) fn delete_file(parent: &File, name: &str) -> io::Result<()> {
        let reviewed = open_regular(parent, name)?;
        if !reviewed.metadata()?.is_file() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "parent-bound report-authority entry is not a regular file",
            ));
        }

        // First move the contested public name to a private no-replace sibling. The identity
        // check below proves that the moved object is the exact child generation we reviewed.
        // A newcomer can then occupy the public name without becoming deletion collateral.
        let anchor = (0..32)
            .find_map(|_| {
                let candidate = format!(
                    ".preflight-delete-{}-{}",
                    std::process::id(),
                    NEXT_DELETE_ANCHOR.fetch_add(1, Ordering::Relaxed)
                );
                match rename_no_replace(parent, name, &candidate) {
                    Ok(()) => Some(Ok(candidate)),
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => None,
                    Err(error) => Some(Err(error)),
                }
            })
            .transpose()?
            .ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "could not reserve a private report-authority deletion anchor",
                )
            })?;

        let anchored = match open_regular(parent, &anchor) {
            Ok(file) => file,
            Err(error) => {
                let _ = rename_no_replace(parent, &anchor, name);
                return Err(error);
            }
        };
        if !same_identity(&reviewed, &anchored)? {
            drop(anchored);
            let _ = rename_no_replace(parent, &anchor, name);
            return Err(io::Error::other(
                "report-authority child changed before deletion commit",
            ));
        }

        super::run_before_delete_test_hook(name);
        drop(anchored);
        drop(reviewed);
        let anchor = CString::new(anchor)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "filename contains NUL"))?;
        if unsafe { libc::unlinkat(parent.as_raw_fd(), anchor.as_ptr(), 0) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    fn rename_no_replace(parent: &File, from: &str, to: &str) -> io::Result<()> {
        let from = CString::new(from)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "filename contains NUL"))?;
        let to = CString::new(to)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "filename contains NUL"))?;

        #[cfg(target_os = "linux")]
        let result = unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                parent.as_raw_fd(),
                from.as_ptr(),
                parent.as_raw_fd(),
                to.as_ptr(),
                1u32,
            )
        };

        #[cfg(target_os = "macos")]
        let result = unsafe {
            renameatx_np(
                parent.as_raw_fd(),
                from.as_ptr(),
                parent.as_raw_fd(),
                to.as_ptr(),
                0x0000_0004,
            ) as libc::c_long
        };

        if result == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub(super) fn sync_directory(directory: &File) -> io::Result<()> {
"""
assert old_delete in text
text = text.replace(old_delete, new_delete, 1)

windows_seam = """        let mut disposition = 1u8;
        let mut io_status = [0usize; 2];
"""
windows_replacement = """        super::run_before_delete_test_hook(name);
        let mut disposition = 1u8;
        let mut io_status = [0usize; 2];
"""
assert windows_seam in text
text = text.replace(windows_seam, windows_replacement, 1)
source.write_text(text)

tests = Path("preflight-desktop/src-tauri/tests/report_authority_dir.rs")
text = tests.read_text()
old_use = "use report_authority_dir::BoundDirectory;\n"
new_use = """use report_authority_dir::{
    BoundDirectory, install_before_delete_test_hook, install_before_read_test_hook,
};
"""
assert old_use in text
text = text.replace(old_use, new_use, 1)

insert_before = "fn snapshot(root: &Path) -> BTreeMap<String, Vec<u8>> {\n"
regressions = r'''#[test]
fn same_parent_replacement_after_child_proof_preserves_the_newcomer() {
    let base = temp_root("same-parent-replacement");
    let root = base.join("authority");
    fs::create_dir_all(&root).unwrap();
    let public = root.join("replace-target.json");
    fs::write(&public, b"owned").unwrap();
    let directory = BoundDirectory::open(&root).unwrap();

    let public_for_hook = public.clone();
    #[cfg(windows)]
    let moved_for_hook = root.join("reviewed-old.json");
    install_before_delete_test_hook("replace-target.json", move || {
        #[cfg(windows)]
        fs::rename(&public_for_hook, &moved_for_hook).unwrap();
        #[cfg(unix)]
        assert!(!public_for_hook.exists());
        fs::write(&public_for_hook, b"external").unwrap();
    });

    directory.delete_file("replace-target.json").unwrap();

    assert_eq!(b"external", fs::read(&public).unwrap().as_slice());
    #[cfg(windows)]
    assert!(!root.join("reviewed-old.json").exists());
    fs::remove_dir_all(base).unwrap();
}

#[test]
fn read_bytes_rejects_growth_after_the_initial_metadata_check() {
    let base = temp_root("growing-read");
    let root = base.join("authority");
    fs::create_dir_all(&root).unwrap();
    let path = root.join("grow.json");
    fs::write(&path, b"tiny").unwrap();
    let directory = BoundDirectory::open(&root).unwrap();

    let path_for_hook = path.clone();
    install_before_read_test_hook("grow.json", move || {
        let mut file = fs::OpenOptions::new()
            .append(true)
            .open(&path_for_hook)
            .unwrap();
        file.write_all(&[b'x'; 128]).unwrap();
        file.sync_all().unwrap();
    });

    let error = directory.read_bytes("grow.json", 8).unwrap_err();
    assert!(error.contains("unexpectedly large"));
    fs::remove_dir_all(base).unwrap();
}

fn snapshot(root: &Path) -> BTreeMap<String, Vec<u8>> {
'''
assert insert_before in text
text = text.replace(insert_before, regressions, 1)
tests.write_text(text)
