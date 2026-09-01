use std::ffi::{OsStr, OsString};
use std::fs::File;
use std::io::{self, Read};
use std::path::{Component, Path};

const MAX_DIRECTORY_ENTRIES: usize = 256;
const MAX_INITIAL_READ_CAPACITY: u64 = 64 * 1024;

#[cfg(test)]
use std::sync::Mutex;

#[cfg(test)]
struct TestHook {
    name: OsString,
    callback: Box<dyn FnOnce() + Send>,
}

#[cfg(test)]
static TEST_AFTER_COMPONENT_OPEN: Mutex<Option<TestHook>> = Mutex::new(None);
#[cfg(all(test, unix))]
static TEST_BEFORE_CREATED_COMPONENT_PUBLISH: Mutex<Option<TestHook>> = Mutex::new(None);
#[cfg(test)]
static TEST_BEFORE_BOUNDED_READ: Mutex<Option<TestHook>> = Mutex::new(None);
#[cfg(test)]
static TEST_AFTER_CHILD_REVIEW: Mutex<Option<TestHook>> = Mutex::new(None);
#[cfg(all(test, windows))]
static TEST_AFTER_FIRST_ENUMERATION_ENTRY: Mutex<Option<Box<dyn FnOnce() + Send>>> =
    Mutex::new(None);

/// One exact opened directory generation.
///
/// Acquisition starts at an explicit existing anchor. Every protected descendant component is
/// opened relative to the retained parent handle/descriptor with alias/reparse refusal, then owner
/// and privacy policy is validated on that opened generation. Missing protected components can be
/// created relative to the same retained parent during the walk. Unix creation stages the new
/// component under a private unpredictable sibling name, opens and validates the staged entry,
/// then publishes it to the intended name with no-replace semantics while retaining that opened
/// descriptor. `mkdirat` does not return a descriptor, so Layer 1 does not claim resistance to
/// arbitrary same-UID mutation of the private staging name between `mkdirat` and its first
/// no-follow open. Once that descriptor is acquired, publication and consequential child
/// operations remain bound to the opened generation.
///
/// Consequential child operations stay relative to `file`; this type deliberately retains no
/// pathname that could be re-resolved later.
#[derive(Debug)]
pub(crate) struct BoundDirectory {
    file: File,
}

impl BoundDirectory {
    /// Open an existing protected descendant chain below `anchor`.
    pub(crate) fn open_existing(anchor: &Path, relative: &Path) -> io::Result<Self> {
        let anchor = imp::open_anchor(anchor)?;
        let file = walk_protected(&anchor, relative, false)?;
        Ok(Self { file })
    }

    /// Open or create a protected descendant chain below `anchor`.
    pub(crate) fn open_or_create(anchor: &Path, relative: &Path) -> io::Result<Self> {
        let anchor = imp::open_anchor(anchor)?;
        let file = walk_protected(&anchor, relative, true)?;
        Ok(Self { file })
    }

    /// Derive an existing protected descendant from this exact directory generation.
    pub(crate) fn open_existing_child(&self, relative: &Path) -> io::Result<Self> {
        let file = walk_protected(&self.file, relative, false)?;
        Ok(Self { file })
    }

    /// Derive or create a protected descendant from this exact directory generation.
    pub(crate) fn open_or_create_child(&self, relative: &Path) -> io::Result<Self> {
        let file = walk_protected(&self.file, relative, true)?;
        Ok(Self { file })
    }

    /// Create one new private regular child relative to this exact directory generation.
    pub(crate) fn create_new(&self, name: &OsStr, mode: u32) -> io::Result<File> {
        validate_child_name(name)?;
        imp::create_new(&self.file, name, mode)
    }

    /// Open one existing private regular child without following an alias/reparse point.
    pub(crate) fn open_regular(&self, name: &OsStr) -> io::Result<File> {
        validate_child_name(name)?;
        imp::open_regular(&self.file, name)
    }

    pub(crate) fn exists_regular(&self, name: &OsStr) -> io::Result<bool> {
        match self.open_regular(name) {
            Ok(file) => {
                drop(file);
                Ok(true)
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(error) => Err(error),
        }
    }

    /// Read through the already-open exact child with an authoritative byte ceiling.
    pub(crate) fn read_bytes(&self, name: &OsStr, max_bytes: u64) -> io::Result<Vec<u8>> {
        let limit = max_bytes.checked_add(1).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "read limit is too large")
        })?;
        let file = self.open_regular(name)?;
        let metadata = file.metadata()?;
        if metadata.len() > max_bytes {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "private child exceeds the bounded read limit",
            ));
        }

        run_before_bounded_read_test_hook(name);

        let initial_capacity = metadata.len().min(max_bytes).min(MAX_INITIAL_READ_CAPACITY);
        let initial_capacity = usize::try_from(initial_capacity).unwrap_or(usize::MAX);
        let mut bytes = Vec::with_capacity(initial_capacity);
        let mut reader = file.take(limit);
        reader.read_to_end(&mut bytes)?;
        if bytes.len() as u64 > max_bytes {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "private child grew beyond the bounded read limit",
            ));
        }
        Ok(bytes)
    }

    /// Enumerate the exact directory generation with a fixed capability-level entry ceiling.
    pub(crate) fn list_names(&self) -> io::Result<Vec<OsString>> {
        imp::list_names(&self.file, MAX_DIRECTORY_ENTRIES)
    }

    #[cfg(test)]
    pub(crate) fn list_names_with_limit_for_test(
        &self,
        max_entries: usize,
    ) -> io::Result<Vec<OsString>> {
        imp::list_names(&self.file, max_entries)
    }

    /// Delete only the child generation reviewed by this operation.
    ///
    /// Unix captures the contested public entry under a unique no-replace quarantine name, proves
    /// identity against the reviewed open child, then removes the quarantine entry. Identity
    /// mismatch rolls back with no-replace semantics; a rollback collision preserves both entries
    /// and fails closed. Windows marks the exact RootDirectory-relative opened handle for deletion.
    pub(crate) fn delete_file(&self, name: &OsStr) -> io::Result<()> {
        validate_child_name(name)?;
        let reviewed = match imp::open_regular_for_delete(&self.file, name) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(error),
        };
        run_after_child_review_test_hook(name);
        imp::delete_reviewed(&self.file, name, reviewed)
    }

    /// Clear a flat private namespace using only exact-child deletion operations.
    ///
    /// Every listed entry is first opened and validated as a private regular file. A directory,
    /// alias/reparse point, special file, foreign owner, broad Unix mode, or over-limit namespace
    /// therefore aborts before any deletion starts.
    pub(crate) fn clear_regular_files(&self) -> io::Result<()> {
        let names = self.list_names()?;
        let mut reviewed = Vec::with_capacity(names.len());
        for name in names {
            let file = imp::open_regular_for_delete(&self.file, &name)?;
            reviewed.push((name, file));
        }
        for (name, file) in reviewed {
            run_after_child_review_test_hook(&name);
            imp::delete_reviewed(&self.file, &name, file)?;
        }
        self.sync()
    }

    pub(crate) fn sync(&self) -> io::Result<()> {
        imp::sync_directory(&self.file)
    }

    #[cfg(test)]
    pub(crate) fn is_inheritable_for_test(&self) -> io::Result<bool> {
        imp::is_inheritable(&self.file)
    }
}

#[cfg(test)]
pub(crate) fn file_is_inheritable_for_test(file: &File) -> io::Result<bool> {
    imp::is_inheritable(file)
}

fn walk_protected(parent: &File, relative: &Path, create_missing: bool) -> io::Result<File> {
    let components = protected_components(relative)?;
    let mut current: Option<File> = None;
    for name in components {
        let parent = current.as_ref().unwrap_or(parent);
        let next = if create_missing {
            imp::open_or_create_private_directory(parent, &name)?
        } else {
            imp::open_private_directory(parent, &name)?
        };
        run_after_component_open_test_hook(&name);
        current = Some(next);
    }
    current.ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "protected directory chain must contain at least one component",
        )
    })
}

fn protected_components(relative: &Path) -> io::Result<Vec<OsString>> {
    if relative.is_absolute() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "protected directory chain must be relative to its retained anchor",
        ));
    }
    let mut names = Vec::new();
    for component in relative.components() {
        match component {
            Component::Normal(name) => names.push(name.to_os_string()),
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "protected directory chain contains an unsafe component",
                ));
            }
        }
    }
    if names.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "protected directory chain must contain at least one component",
        ));
    }
    Ok(names)
}

fn validate_child_name(name: &OsStr) -> io::Result<()> {
    let mut components = Path::new(name).components();
    if matches!(components.next(), Some(Component::Normal(_))) && components.next().is_none() {
        Ok(())
    } else {
        Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "child name must be one ordinary relative path component",
        ))
    }
}

#[cfg(test)]
pub(crate) fn install_after_component_open_test_hook(
    name: &OsStr,
    callback: impl FnOnce() + Send + 'static,
) {
    *TEST_AFTER_COMPONENT_OPEN
        .lock()
        .expect("component-open test hook lock") = Some(TestHook {
        name: name.to_os_string(),
        callback: Box::new(callback),
    });
}

#[cfg(all(test, unix))]
pub(crate) fn install_before_created_component_publish_test_hook(
    name: &OsStr,
    callback: impl FnOnce() + Send + 'static,
) {
    *TEST_BEFORE_CREATED_COMPONENT_PUBLISH
        .lock()
        .expect("component-publish test hook lock") = Some(TestHook {
        name: name.to_os_string(),
        callback: Box::new(callback),
    });
}

#[cfg(test)]
pub(crate) fn install_before_bounded_read_test_hook(
    name: &OsStr,
    callback: impl FnOnce() + Send + 'static,
) {
    *TEST_BEFORE_BOUNDED_READ
        .lock()
        .expect("bounded-read test hook lock") = Some(TestHook {
        name: name.to_os_string(),
        callback: Box::new(callback),
    });
}

#[cfg(test)]
pub(crate) fn install_after_child_review_test_hook(
    name: &OsStr,
    callback: impl FnOnce() + Send + 'static,
) {
    *TEST_AFTER_CHILD_REVIEW
        .lock()
        .expect("child-review test hook lock") = Some(TestHook {
        name: name.to_os_string(),
        callback: Box::new(callback),
    });
}

#[cfg(all(test, windows))]
pub(crate) fn install_after_first_enumeration_entry_test_hook(
    callback: impl FnOnce() + Send + 'static,
) {
    *TEST_AFTER_FIRST_ENUMERATION_ENTRY
        .lock()
        .expect("enumeration test hook lock") = Some(Box::new(callback));
}

#[cfg(test)]
fn run_test_hook(slot: &Mutex<Option<TestHook>>, name: &OsStr) {
    let hook = {
        let mut slot = slot.lock().expect("filesystem capability test hook lock");
        if slot
            .as_ref()
            .is_some_and(|hook| hook.name.as_os_str() == name)
        {
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
fn run_after_component_open_test_hook(name: &OsStr) {
    run_test_hook(&TEST_AFTER_COMPONENT_OPEN, name);
}

#[cfg(not(test))]
fn run_after_component_open_test_hook(_name: &OsStr) {}

#[cfg(all(test, unix))]
fn run_before_created_component_publish_test_hook(name: &OsStr) {
    run_test_hook(&TEST_BEFORE_CREATED_COMPONENT_PUBLISH, name);
}

#[cfg(all(not(test), unix))]
fn run_before_created_component_publish_test_hook(_name: &OsStr) {}

#[cfg(test)]
fn run_before_bounded_read_test_hook(name: &OsStr) {
    run_test_hook(&TEST_BEFORE_BOUNDED_READ, name);
}

#[cfg(not(test))]
fn run_before_bounded_read_test_hook(_name: &OsStr) {}

#[cfg(test)]
fn run_after_child_review_test_hook(name: &OsStr) {
    run_test_hook(&TEST_AFTER_CHILD_REVIEW, name);
}

#[cfg(not(test))]
fn run_after_child_review_test_hook(_name: &OsStr) {}

#[cfg(all(test, windows))]
fn run_after_first_enumeration_entry_test_hook() {
    let hook = TEST_AFTER_FIRST_ENUMERATION_ENTRY
        .lock()
        .expect("enumeration test hook lock")
        .take();
    if let Some(hook) = hook {
        hook();
    }
}

#[cfg(all(not(test), windows))]
fn run_after_first_enumeration_entry_test_hook() {}

#[cfg(unix)]
mod imp {
    use super::*;
    use std::ffi::{CStr, CString};
    use std::os::fd::{AsRawFd, FromRawFd};
    use std::os::unix::ffi::{OsStrExt, OsStringExt};
    use std::os::unix::fs::MetadataExt;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_DELETE_QUARANTINE: AtomicU64 = AtomicU64::new(1);

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

    pub(super) fn open_anchor(path: &Path) -> io::Result<File> {
        if !path.is_absolute() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "filesystem capability anchor must be absolute",
            ));
        }
        let root = CString::new("/").expect("filesystem root contains no NUL");
        let fd = unsafe {
            libc::open(
                root.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_DIRECTORY | libc::O_CLOEXEC,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let mut current = unsafe { File::from_raw_fd(fd) };
        for component in path.components() {
            match component {
                Component::RootDir | Component::CurDir => {}
                Component::Normal(name) => {
                    current = open_directory_component(&current, name)?;
                }
                Component::ParentDir | Component::Prefix(_) => {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "filesystem capability anchor contains an unsafe component",
                    ));
                }
            }
        }
        Ok(current)
    }

    pub(super) fn open_private_directory(parent: &File, name: &OsStr) -> io::Result<File> {
        let directory = open_directory_component(parent, name)?;
        require_private_directory(&directory)?;
        Ok(directory)
    }

    pub(super) fn open_or_create_private_directory(
        parent: &File,
        name: &OsStr,
    ) -> io::Result<File> {
        match open_private_directory(parent, name) {
            Ok(directory) => return Ok(directory),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }

        let (staged_name, directory) = create_private_directory_stage(parent)?;
        run_before_created_component_publish_test_hook(name);
        match rename_no_replace(parent, &staged_name, name) {
            Ok(()) => Ok(directory),
            Err(error) => Err(io::Error::new(
                error.kind(),
                format!(
                    "could not publish the staged protected component without replacing a concurrent winner; private staging entry {:?} was preserved: {error}",
                    staged_name
                ),
            )),
        }
    }

    pub(super) fn create_new(parent: &File, name: &OsStr, mode: u32) -> io::Result<File> {
        validate_private_file_mode(mode)?;
        let name = c_name(name)?;
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                mode as libc::c_uint,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let file = unsafe { File::from_raw_fd(fd) };
        if unsafe { libc::fchmod(file.as_raw_fd(), mode as libc::mode_t) } != 0 {
            return Err(io::Error::last_os_error());
        }
        require_private_regular(&file)?;
        Ok(file)
    }

    pub(super) fn open_regular(parent: &File, name: &OsStr) -> io::Result<File> {
        open_private_regular(parent, name)
    }

    pub(super) fn open_regular_for_delete(parent: &File, name: &OsStr) -> io::Result<File> {
        open_private_regular(parent, name)
    }

    pub(super) fn list_names(parent: &File, max_entries: usize) -> io::Result<Vec<OsString>> {
        let dot = CString::new(".").expect("dot contains no NUL");
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                dot.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_DIRECTORY | libc::O_CLOEXEC,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let directory = unsafe { libc::fdopendir(fd) };
        if directory.is_null() {
            let error = io::Error::last_os_error();
            unsafe {
                libc::close(fd);
            }
            return Err(error);
        }

        let mut names = Vec::new();
        let mut failure = None;
        loop {
            unsafe {
                *errno_location() = 0;
            }
            let entry = unsafe { libc::readdir(directory) };
            if entry.is_null() {
                let errno = unsafe { *errno_location() };
                if errno != 0 {
                    failure = Some(io::Error::from_raw_os_error(errno));
                }
                break;
            }
            let name = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) }.to_bytes();
            if name == b"." || name == b".." {
                continue;
            }
            if names.len() >= max_entries {
                failure = Some(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "directory exceeds the capability entry limit",
                ));
                break;
            }
            names.push(OsString::from_vec(name.to_vec()));
        }

        if unsafe { libc::closedir(directory) } != 0 && failure.is_none() {
            failure = Some(io::Error::last_os_error());
        }
        if let Some(error) = failure {
            return Err(error);
        }
        names.sort();
        Ok(names)
    }

    pub(super) fn delete_reviewed(parent: &File, name: &OsStr, reviewed: File) -> io::Result<()> {
        let quarantine = capture_public_entry(parent, name)?;
        let anchored = match open_private_regular(parent, &quarantine) {
            Ok(file) => file,
            Err(error) => {
                return fail_with_rollback(parent, &quarantine, name, error);
            }
        };

        match same_identity(&reviewed, &anchored) {
            Ok(true) => {}
            Ok(false) => {
                drop(anchored);
                return fail_with_rollback(
                    parent,
                    &quarantine,
                    name,
                    io::Error::other("child generation changed before deletion commit"),
                );
            }
            Err(error) => {
                drop(anchored);
                return fail_with_rollback(parent, &quarantine, name, error);
            }
        }

        // Keep both exact handles live through the name removal. The no-replace capture above is
        // the linearization point for the public name: any later public same-name winner remains
        // outside the quarantine and cannot be deletion collateral.
        let quarantine_name = c_name(&quarantine)?;
        if unsafe { libc::unlinkat(parent.as_raw_fd(), quarantine_name.as_ptr(), 0) } != 0 {
            let error = io::Error::last_os_error();
            drop(anchored);
            drop(reviewed);
            return fail_with_rollback(parent, &quarantine, name, error);
        }
        drop(anchored);
        drop(reviewed);
        Ok(())
    }

    pub(super) fn sync_directory(directory: &File) -> io::Result<()> {
        directory.sync_all()
    }

    pub(super) fn is_inheritable(file: &File) -> io::Result<bool> {
        let flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFD) };
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(flags & libc::FD_CLOEXEC == 0)
    }

    fn open_directory_component(parent: &File, name: &OsStr) -> io::Result<File> {
        let name = c_name(name)?;
        open_directory_component_c(parent, &name)
    }

    fn open_directory_component_c(parent: &File, name: &CString) -> io::Result<File> {
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_DIRECTORY | libc::O_CLOEXEC,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    fn open_private_regular(parent: &File, name: &OsStr) -> io::Result<File> {
        let name = c_name(name)?;
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY
                    | libc::O_NOFOLLOW
                    | libc::O_NONBLOCK
                    | libc::O_NOCTTY
                    | libc::O_CLOEXEC,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let file = unsafe { File::from_raw_fd(fd) };
        require_private_regular(&file)?;
        Ok(file)
    }

    fn require_private_directory(directory: &File) -> io::Result<()> {
        let metadata = directory.metadata()?;
        if !metadata.is_dir() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "protected component is not a directory",
            ));
        }
        if metadata.uid() != unsafe { libc::geteuid() } {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "protected component is owned by another user",
            ));
        }
        if metadata.mode() & 0o7777 != 0o700 {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "protected component does not have private mode 0700",
            ));
        }
        Ok(())
    }

    fn require_private_regular(file: &File) -> io::Result<()> {
        let metadata = file.metadata()?;
        if !metadata.is_file() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "child is not a regular file",
            ));
        }
        if metadata.uid() != unsafe { libc::geteuid() } {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "child is owned by another user",
            ));
        }
        if metadata.mode() & 0o077 != 0 {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "child permissions are broader than owner-only",
            ));
        }
        Ok(())
    }

    fn validate_private_file_mode(mode: u32) -> io::Result<()> {
        if mode == 0 || mode & !0o777 != 0 || mode & 0o077 != 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "created child mode must grant only owner permissions",
            ));
        }
        Ok(())
    }

    fn same_identity(left: &File, right: &File) -> io::Result<bool> {
        let left = left.metadata()?;
        let right = right.metadata()?;
        Ok(left.dev() == right.dev() && left.ino() == right.ino())
    }

    fn create_private_directory_stage(parent: &File) -> io::Result<(OsString, File)> {
        for _ in 0..32 {
            let stage = random_private_stage_name()?;
            let stage_name = c_name(&stage)?;
            if unsafe { libc::mkdirat(parent.as_raw_fd(), stage_name.as_ptr(), 0o700) } != 0 {
                let error = io::Error::last_os_error();
                if error.raw_os_error() == Some(libc::EEXIST) {
                    continue;
                }
                return Err(error);
            }

            let directory = open_directory_component_c(parent, &stage_name).map_err(|error| {
                io::Error::new(
                    error.kind(),
                    format!(
                        "could not open the newly staged private directory {:?}; the staging entry was preserved: {error}",
                        stage
                    ),
                )
            })?;
            if unsafe { libc::fchmod(directory.as_raw_fd(), 0o700) } != 0 {
                let error = io::Error::last_os_error();
                return Err(io::Error::new(
                    error.kind(),
                    format!(
                        "could not enforce private permissions on staged directory {:?}; the staging entry was preserved: {error}",
                        stage
                    ),
                ));
            }
            require_private_directory(&directory).map_err(|error| {
                io::Error::new(
                    error.kind(),
                    format!(
                        "staged directory {:?} failed exact-generation validation and was preserved: {error}",
                        stage
                    ),
                )
            })?;
            return Ok((stage, directory));
        }
        Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            "could not reserve a private directory staging name",
        ))
    }

    fn random_private_stage_name() -> io::Result<OsString> {
        let mut nonce = [0u8; 16];
        getrandom::fill(&mut nonce).map_err(|error| {
            io::Error::other(format!(
                "could not generate a private directory staging name: {error}"
            ))
        })?;
        let suffix = nonce
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        Ok(OsString::from(format!(".bound-create-{suffix}")))
    }

    fn capture_public_entry(parent: &File, name: &OsStr) -> io::Result<OsString> {
        for _ in 0..32 {
            let candidate = OsString::from(format!(
                ".bound-delete-{}-{}",
                std::process::id(),
                NEXT_DELETE_QUARANTINE.fetch_add(1, Ordering::Relaxed)
            ));
            match rename_no_replace(parent, name, &candidate) {
                Ok(()) => return Ok(candidate),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error),
            }
        }
        Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            "could not reserve a private deletion quarantine name",
        ))
    }

    fn fail_with_rollback(
        parent: &File,
        quarantine: &OsStr,
        public: &OsStr,
        cause: io::Error,
    ) -> io::Result<()> {
        match rename_no_replace(parent, quarantine, public) {
            Ok(()) => Err(cause),
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
                Err(io::Error::other(format!(
                    "{cause}; a later public winner exists, so the captured entry was preserved in quarantine"
                )))
            }
            Err(rollback) => Err(io::Error::other(format!(
                "{cause}; rollback failed and the captured entry was preserved in quarantine: {rollback}"
            ))),
        }
    }

    fn rename_no_replace(parent: &File, from: &OsStr, to: &OsStr) -> io::Result<()> {
        let from = c_name(from)?;
        let to = c_name(to)?;

        #[cfg(target_os = "linux")]
        let result = unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                parent.as_raw_fd(),
                from.as_ptr(),
                parent.as_raw_fd(),
                to.as_ptr(),
                libc::RENAME_NOREPLACE,
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
            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(libc::EEXIST) {
                Err(io::Error::new(io::ErrorKind::AlreadyExists, error))
            } else {
                Err(error)
            }
        }
    }

    fn c_name(name: &OsStr) -> io::Result<CString> {
        CString::new(name.as_bytes())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "name contains NUL"))
    }

    #[cfg(target_os = "linux")]
    fn errno_location() -> *mut libc::c_int {
        unsafe { libc::__errno_location() }
    }

    #[cfg(target_os = "macos")]
    fn errno_location() -> *mut libc::c_int {
        unsafe { libc::__error() }
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use std::ffi::c_void;
    use std::mem::size_of;
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use std::os::windows::io::{AsRawHandle, FromRawHandle, RawHandle};
    use std::path::PathBuf;
    use std::ptr::null_mut;

    type Handle = *mut c_void;

    const FILE_LIST_DIRECTORY: u32 = 0x0001;
    const FILE_READ_DATA: u32 = 0x0001;
    const FILE_WRITE_DATA: u32 = 0x0002;
    const FILE_TRAVERSE: u32 = 0x0020;
    const FILE_READ_ATTRIBUTES: u32 = 0x0080;
    const DELETE: u32 = 0x0001_0000;
    const READ_CONTROL: u32 = 0x0002_0000;
    const SYNCHRONIZE: u32 = 0x0010_0000;
    const FILE_SHARE_READ: u32 = 0x0000_0001;
    const FILE_SHARE_WRITE: u32 = 0x0000_0002;
    const FILE_SHARE_DELETE: u32 = 0x0000_0004;
    const OPEN_EXISTING: u32 = 3;
    const FILE_ATTRIBUTE_DIRECTORY: u32 = 0x0000_0010;
    const FILE_ATTRIBUTE_NORMAL: u32 = 0x0000_0080;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;
    const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
    const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
    const FILE_OPEN: u32 = 1;
    const FILE_CREATE: u32 = 2;
    const FILE_OPEN_IF: u32 = 3;
    const FILE_SYNCHRONOUS_IO_NONALERT: u32 = 0x0000_0020;
    const FILE_NON_DIRECTORY_FILE: u32 = 0x0000_0040;
    const FILE_DIRECTORY_FILE: u32 = 0x0000_0001;
    const FILE_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
    const OBJ_CASE_INSENSITIVE: u32 = 0x0000_0040;
    const FILE_DISPOSITION_INFORMATION: u32 = 13;
    const FILE_NAMES_INFORMATION: u32 = 12;
    const STATUS_OBJECT_NAME_NOT_FOUND: i32 = 0xC000_0034u32 as i32;
    const STATUS_OBJECT_NAME_COLLISION: i32 = 0xC000_0035u32 as i32;
    const STATUS_OBJECT_PATH_NOT_FOUND: i32 = 0xC000_003Au32 as i32;
    const STATUS_NO_MORE_FILES: i32 = 0x8000_0006u32 as i32;
    const OWNER_SECURITY_INFORMATION: u32 = 0x0000_0001;
    const SE_FILE_OBJECT: u32 = 1;
    const TOKEN_QUERY: u32 = 0x0008;
    const TOKEN_OWNER_CLASS: u32 = 4;
    const HANDLE_FLAG_INHERIT: u32 = 0x0000_0001;

    #[repr(C)]
    struct UnicodeString {
        length: u16,
        maximum_length: u16,
        buffer: *mut u16,
    }

    #[repr(C)]
    struct ObjectAttributes {
        length: u32,
        root_directory: Handle,
        object_name: *mut UnicodeString,
        attributes: u32,
        security_descriptor: *mut c_void,
        security_quality_of_service: *mut c_void,
    }

    #[repr(C)]
    struct ByHandleFileInformation {
        file_attributes: u32,
        creation_time_low: u32,
        creation_time_high: u32,
        last_access_time_low: u32,
        last_access_time_high: u32,
        last_write_time_low: u32,
        last_write_time_high: u32,
        volume_serial_number: u32,
        file_size_high: u32,
        file_size_low: u32,
        number_of_links: u32,
        file_index_high: u32,
        file_index_low: u32,
    }

    #[repr(C)]
    struct TokenOwner {
        owner: *mut c_void,
    }

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn CreateFileW(
            file_name: *const u16,
            desired_access: u32,
            share_mode: u32,
            security_attributes: *mut c_void,
            creation_disposition: u32,
            flags_and_attributes: u32,
            template_file: Handle,
        ) -> Handle;
        fn GetFileInformationByHandle(
            file: Handle,
            information: *mut ByHandleFileInformation,
        ) -> i32;
        fn GetCurrentProcess() -> Handle;
        fn CloseHandle(handle: Handle) -> i32;
        fn LocalFree(memory: *mut c_void) -> *mut c_void;
        fn GetHandleInformation(handle: Handle, flags: *mut u32) -> i32;
    }

    #[link(name = "advapi32")]
    unsafe extern "system" {
        fn GetSecurityInfo(
            handle: Handle,
            object_type: u32,
            security_information: u32,
            owner: *mut *mut c_void,
            group: *mut *mut c_void,
            dacl: *mut *mut c_void,
            sacl: *mut *mut c_void,
            security_descriptor: *mut *mut c_void,
        ) -> u32;
        fn OpenProcessToken(process: Handle, desired_access: u32, token: *mut Handle) -> i32;
        fn GetTokenInformation(
            token: Handle,
            information_class: u32,
            information: *mut c_void,
            information_length: u32,
            return_length: *mut u32,
        ) -> i32;
        fn EqualSid(first: *const c_void, second: *const c_void) -> i32;
    }

    #[link(name = "ntdll")]
    unsafe extern "system" {
        fn NtCreateFile(
            file_handle: *mut Handle,
            desired_access: u32,
            object_attributes: *mut ObjectAttributes,
            io_status_block: *mut usize,
            allocation_size: *mut i64,
            file_attributes: u32,
            share_access: u32,
            create_disposition: u32,
            create_options: u32,
            ea_buffer: *mut c_void,
            ea_length: u32,
        ) -> i32;
        fn NtSetInformationFile(
            file_handle: Handle,
            io_status_block: *mut usize,
            file_information: *mut c_void,
            length: u32,
            file_information_class: u32,
        ) -> i32;
        fn NtQueryDirectoryFile(
            file_handle: Handle,
            event: Handle,
            apc_routine: *mut c_void,
            apc_context: *mut c_void,
            io_status_block: *mut usize,
            file_information: *mut c_void,
            length: u32,
            file_information_class: u32,
            return_single_entry: u8,
            file_name: *mut UnicodeString,
            restart_scan: u8,
        ) -> i32;
    }

    pub(super) fn open_anchor(path: &Path) -> io::Result<File> {
        if !path.is_absolute() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "filesystem capability anchor must be absolute",
            ));
        }
        let mut root = PathBuf::new();
        let mut names = Vec::<OsString>::new();
        let mut rooted = false;
        for component in path.components() {
            match component {
                Component::Prefix(prefix) => root.push(prefix.as_os_str()),
                Component::RootDir => {
                    root.push(Path::new("\\"));
                    rooted = true;
                }
                Component::Normal(name) => names.push(name.to_os_string()),
                Component::CurDir => {}
                Component::ParentDir => {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "filesystem capability anchor contains an unsafe component",
                    ));
                }
            }
        }
        if !rooted {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "filesystem capability anchor has no filesystem root",
            ));
        }

        let mut current = open_directory_by_path(&root)?;
        for name in names {
            current = open_anchor_relative_directory(&current, &name)?;
        }
        Ok(current)
    }

    pub(super) fn open_private_directory(parent: &File, name: &OsStr) -> io::Result<File> {
        let directory = open_relative_directory(parent, name, FILE_OPEN)?;
        require_private_directory(&directory)?;
        Ok(directory)
    }

    pub(super) fn open_or_create_private_directory(
        parent: &File,
        name: &OsStr,
    ) -> io::Result<File> {
        let directory = open_relative_directory(parent, name, FILE_OPEN_IF)?;
        require_private_directory(&directory)?;
        Ok(directory)
    }

    pub(super) fn create_new(parent: &File, name: &OsStr, _mode: u32) -> io::Result<File> {
        let file = open_relative(
            parent,
            name,
            FILE_WRITE_DATA | FILE_READ_ATTRIBUTES | READ_CONTROL | SYNCHRONIZE,
            FILE_CREATE,
            FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
        )?;
        require_private_regular(&file)?;
        Ok(file)
    }

    pub(super) fn open_regular(parent: &File, name: &OsStr) -> io::Result<File> {
        let file = open_relative(
            parent,
            name,
            FILE_READ_DATA | FILE_READ_ATTRIBUTES | READ_CONTROL | SYNCHRONIZE,
            FILE_OPEN,
            FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
        )?;
        require_private_regular(&file)?;
        Ok(file)
    }

    pub(super) fn open_regular_for_delete(parent: &File, name: &OsStr) -> io::Result<File> {
        let file = open_relative(
            parent,
            name,
            DELETE | FILE_READ_ATTRIBUTES | READ_CONTROL | SYNCHRONIZE,
            FILE_OPEN,
            FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
        )?;
        require_private_regular(&file)?;
        Ok(file)
    }

    pub(super) fn list_names(directory: &File, max_entries: usize) -> io::Result<Vec<OsString>> {
        let enumeration = open_relative_directory(directory, OsStr::new("."), FILE_OPEN)?;
        require_private_directory(&enumeration)?;

        let mut names = Vec::new();
        let mut restart_scan = 1u8;
        loop {
            let mut buffer = [0u8; 4096];
            let mut io_status = [0usize; 2];
            let status = unsafe {
                NtQueryDirectoryFile(
                    enumeration.as_raw_handle() as Handle,
                    null_mut(),
                    null_mut(),
                    null_mut(),
                    io_status.as_mut_ptr(),
                    buffer.as_mut_ptr().cast(),
                    buffer.len() as u32,
                    FILE_NAMES_INFORMATION,
                    1,
                    null_mut(),
                    restart_scan,
                )
            };
            restart_scan = 0;
            if status == STATUS_NO_MORE_FILES {
                break;
            }
            if status < 0 {
                return Err(io::Error::other(format!(
                    "NtQueryDirectoryFile failed (NTSTATUS 0x{:08x})",
                    status as u32
                )));
            }
            let information = io_status[1];
            if information < 12 || information > buffer.len() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "NtQueryDirectoryFile returned an invalid record length",
                ));
            }
            let name_bytes =
                u32::from_ne_bytes(buffer[8..12].try_into().expect("fixed slice")) as usize;
            if !name_bytes.is_multiple_of(2) || 12usize.saturating_add(name_bytes) > information {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "NtQueryDirectoryFile returned an invalid filename length",
                ));
            }
            let wide = buffer[12..12 + name_bytes]
                .as_chunks::<2>()
                .0
                .iter()
                .map(|pair| u16::from_ne_bytes(*pair))
                .collect::<Vec<_>>();
            let name = OsString::from_wide(&wide);
            if name == OsStr::new(".") || name == OsStr::new("..") {
                continue;
            }
            if names.len() >= max_entries {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "directory exceeds the capability entry limit",
                ));
            }
            names.push(name);
            if names.len() == 1 {
                run_after_first_enumeration_entry_test_hook();
            }
        }
        names.sort();
        Ok(names)
    }

    pub(super) fn delete_reviewed(_parent: &File, _name: &OsStr, reviewed: File) -> io::Result<()> {
        let mut disposition = 1u8;
        let mut io_status = [0usize; 2];
        let status = unsafe {
            NtSetInformationFile(
                reviewed.as_raw_handle() as Handle,
                io_status.as_mut_ptr(),
                (&mut disposition as *mut u8).cast(),
                1,
                FILE_DISPOSITION_INFORMATION,
            )
        };
        if status < 0 {
            return Err(io::Error::other(format!(
                "NtSetInformationFile(disposition) failed (NTSTATUS 0x{:08x})",
                status as u32
            )));
        }
        drop(reviewed);
        Ok(())
    }

    pub(super) fn sync_directory(_directory: &File) -> io::Result<()> {
        Ok(())
    }

    pub(super) fn is_inheritable(file: &File) -> io::Result<bool> {
        let mut flags = 0u32;
        if unsafe { GetHandleInformation(file.as_raw_handle() as Handle, &mut flags) } == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(flags & HANDLE_FLAG_INHERIT != 0)
    }

    fn open_directory_by_path(path: &Path) -> io::Result<File> {
        let mut wide = path.as_os_str().encode_wide().collect::<Vec<_>>();
        wide.push(0);
        let handle = unsafe {
            CreateFileW(
                wide.as_ptr(),
                FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                null_mut(),
                OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                null_mut(),
            )
        };
        if handle.is_null() || handle as isize == -1 {
            return Err(io::Error::last_os_error());
        }
        // security_attributes is null, so CreateFileW cannot return an inheritable handle.
        let file = unsafe { File::from_raw_handle(handle as RawHandle) };
        require_not_reparse(&file)?;
        require_directory(&file)?;
        Ok(file)
    }

    fn open_anchor_relative_directory(parent: &File, name: &OsStr) -> io::Result<File> {
        open_relative(
            parent,
            name,
            FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
            FILE_OPEN,
            FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
        )
    }

    fn open_relative_directory(parent: &File, name: &OsStr, disposition: u32) -> io::Result<File> {
        open_relative(
            parent,
            name,
            FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | READ_CONTROL | SYNCHRONIZE,
            disposition,
            FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
        )
    }

    fn open_relative(
        parent: &File,
        name: &OsStr,
        desired_access: u32,
        disposition: u32,
        options: u32,
    ) -> io::Result<File> {
        let mut wide = name.encode_wide().collect::<Vec<_>>();
        let buffer_byte_len = wide
            .len()
            .checked_mul(2)
            .and_then(|value| u16::try_from(value).ok())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "name is too long"))?;
        if buffer_byte_len == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "relative name is empty",
            ));
        }
        // NT relative opens do not interpret `.` as the current directory. A zero-length
        // UNICODE_STRING with RootDirectory set reopens that exact directory as a new FileObject;
        // keep the dot buffer only as the internal sentinel so each enumeration gets fresh cursor
        // state without re-resolving a public pathname.
        let byte_len = if name == OsStr::new(".") {
            0
        } else {
            buffer_byte_len
        };
        let mut unicode = UnicodeString {
            length: byte_len,
            maximum_length: buffer_byte_len,
            buffer: wide.as_mut_ptr(),
        };
        let mut attributes = ObjectAttributes {
            length: size_of::<ObjectAttributes>() as u32,
            root_directory: parent.as_raw_handle() as Handle,
            object_name: &mut unicode,
            // OBJ_INHERIT is deliberately absent, so NtCreateFile returns a non-inheritable handle.
            attributes: OBJ_CASE_INSENSITIVE,
            security_descriptor: null_mut(),
            security_quality_of_service: null_mut(),
        };
        let mut opened: Handle = null_mut();
        let mut io_status = [0usize; 2];
        let status = unsafe {
            NtCreateFile(
                &mut opened,
                desired_access,
                &mut attributes,
                io_status.as_mut_ptr(),
                null_mut(),
                FILE_ATTRIBUTE_NORMAL,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                disposition,
                options,
                null_mut(),
                0,
            )
        };
        if status == STATUS_OBJECT_NAME_COLLISION {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "entry already exists",
            ));
        }
        if status == STATUS_OBJECT_NAME_NOT_FOUND || status == STATUS_OBJECT_PATH_NOT_FOUND {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                "entry does not exist",
            ));
        }
        if status < 0 {
            return Err(io::Error::other(format!(
                "NtCreateFile failed (NTSTATUS 0x{:08x})",
                status as u32
            )));
        }
        if opened.is_null() || opened as isize == -1 {
            return Err(io::Error::other("NtCreateFile returned an invalid handle"));
        }
        let file = unsafe { File::from_raw_handle(opened as RawHandle) };
        require_not_reparse(&file)?;
        Ok(file)
    }

    fn require_private_directory(directory: &File) -> io::Result<()> {
        require_not_reparse(directory)?;
        require_directory(directory)?;
        require_current_token_owner(directory)
    }

    fn require_private_regular(file: &File) -> io::Result<()> {
        require_not_reparse(file)?;
        if information(file)?.file_attributes & FILE_ATTRIBUTE_DIRECTORY != 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "child is not a regular file",
            ));
        }
        // Windows has ACLs rather than POSIX mode bits. This layer binds the exact current-token
        // default-owner generation; ACL provisioning remains an operating-system policy of the
        // app-data root while reparse refusal prevents namespace redirection.
        require_current_token_owner(file)
    }

    fn require_directory(file: &File) -> io::Result<()> {
        if information(file)?.file_attributes & FILE_ATTRIBUTE_DIRECTORY == 0 {
            Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "protected component is not a directory",
            ))
        } else {
            Ok(())
        }
    }

    fn require_not_reparse(file: &File) -> io::Result<()> {
        if information(file)?.file_attributes & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
            Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "opened generation is a reparse point",
            ))
        } else {
            Ok(())
        }
    }

    fn require_current_token_owner(file: &File) -> io::Result<()> {
        let mut owner: *mut c_void = null_mut();
        let mut descriptor: *mut c_void = null_mut();
        let status = unsafe {
            GetSecurityInfo(
                file.as_raw_handle() as Handle,
                SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION,
                &mut owner,
                null_mut(),
                null_mut(),
                null_mut(),
                &mut descriptor,
            )
        };
        if status != 0 {
            return Err(io::Error::from_raw_os_error(status as i32));
        }
        if owner.is_null() || descriptor.is_null() {
            if !descriptor.is_null() {
                unsafe {
                    LocalFree(descriptor);
                }
            }
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "opened generation has no filesystem owner",
            ));
        }

        let result = current_default_owner_sid_matches(owner);
        unsafe {
            LocalFree(descriptor);
        }
        match result? {
            true => Ok(()),
            false => Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "opened generation is owned by another principal",
            )),
        }
    }

    fn current_default_owner_sid_matches(owner: *const c_void) -> io::Result<bool> {
        let mut token: Handle = null_mut();
        if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
            return Err(io::Error::last_os_error());
        }

        let mut required = 0u32;
        unsafe {
            GetTokenInformation(token, TOKEN_OWNER_CLASS, null_mut(), 0, &mut required);
        }
        if required == 0 {
            let error = io::Error::last_os_error();
            unsafe {
                CloseHandle(token);
            }
            return Err(error);
        }

        let word = size_of::<usize>();
        let words = (required as usize).div_ceil(word);
        let mut buffer = vec![0usize; words];
        if unsafe {
            GetTokenInformation(
                token,
                TOKEN_OWNER_CLASS,
                buffer.as_mut_ptr().cast(),
                required,
                &mut required,
            )
        } == 0
        {
            let error = io::Error::last_os_error();
            unsafe {
                CloseHandle(token);
            }
            return Err(error);
        }
        let token_owner = unsafe { &*(buffer.as_ptr().cast::<TokenOwner>()) };
        if token_owner.owner.is_null() {
            unsafe {
                CloseHandle(token);
            }
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "current access token has no default owner",
            ));
        }
        let matches = unsafe { EqualSid(owner, token_owner.owner) } != 0;
        unsafe {
            CloseHandle(token);
        }
        Ok(matches)
    }

    fn information(file: &File) -> io::Result<ByHandleFileInformation> {
        let mut information = ByHandleFileInformation {
            file_attributes: 0,
            creation_time_low: 0,
            creation_time_high: 0,
            last_access_time_low: 0,
            last_access_time_high: 0,
            last_write_time_low: 0,
            last_write_time_high: 0,
            volume_serial_number: 0,
            file_size_high: 0,
            file_size_low: 0,
            number_of_links: 0,
            file_index_high: 0,
            file_index_low: 0,
        };
        if unsafe { GetFileInformationByHandle(file.as_raw_handle() as Handle, &mut information) }
            == 0
        {
            Err(io::Error::last_os_error())
        } else {
            Ok(information)
        }
    }
}
