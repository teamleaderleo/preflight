use std::fs::File;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
#[cfg(test)]
use std::sync::Mutex;
use std::time::SystemTime;

/// One exact opened report-authority directory generation.
///
/// The directory is reached by a component-by-component no-follow walk. Consequential file opens,
/// enumeration, publication, and cleanup stay relative to the retained handle/descriptor, so a
/// replacement of any public pathname component cannot redirect report authority into another
/// directory generation.
pub(crate) struct BoundDirectory {
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
pub(crate) fn install_before_read_test_hook(name: &str, callback: impl FnOnce() + Send + 'static) {
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
    pub(crate) fn open(path: &Path) -> Result<Self, String> {
        let path = path.to_absolute_path();
        let file = imp::open_directory(&path).map_err(|error| {
            format!(
                "Could not open protected report storage {}: {error}",
                path.display()
            )
        })?;
        Ok(Self { path, file })
    }

    pub(crate) fn path(&self) -> &Path {
        &self.path
    }

    pub(crate) fn require_current(&self) -> Result<(), String> {
        let current = imp::open_directory(&self.path).map_err(|error| {
            format!("Protected report storage changed while report authority was active: {error}")
        })?;
        if imp::same_identity(&self.file, &current).map_err(|error| {
            format!("Could not compare protected report storage generations: {error}")
        })? {
            Ok(())
        } else {
            Err("Protected report storage changed while report authority was active.".to_string())
        }
    }

    pub(crate) fn create_new(&self, name: &str, mode: u32) -> io::Result<File> {
        validate_name(name)?;
        imp::create_new(&self.file, name, mode)
    }

    pub(crate) fn open_regular(&self, name: &str) -> io::Result<File> {
        validate_name(name)?;
        let file = imp::open_regular(&self.file, name)?;
        let metadata = file.metadata()?;
        if !metadata.is_file() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "parent-bound report-authority entry is not a regular file",
            ));
        }
        Ok(file)
    }

    pub(crate) fn exists_regular(&self, name: &str) -> io::Result<bool> {
        match self.open_regular(name) {
            Ok(file) => {
                drop(file);
                Ok(true)
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(error) => Err(error),
        }
    }

    pub(crate) fn modified(&self, name: &str) -> io::Result<SystemTime> {
        self.open_regular(name)?.metadata()?.modified()
    }

    pub(crate) fn read_bytes(&self, name: &str, max_bytes: u64) -> Result<Vec<u8>, String> {
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

    pub(crate) fn list_names(&self) -> Result<Vec<String>, String> {
        imp::list_names(&self.file)
            .map_err(|error| format!("Could not enumerate saved report authority: {error}"))
    }

    pub(crate) fn delete_file(&self, name: &str) -> io::Result<()> {
        validate_name(name)?;
        match imp::delete_file(&self.file, name) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            result => result,
        }
    }

    /// Clear the flat report-authority namespace through this exact opened directory generation.
    /// Unexpected directories, aliases, or special files fail closed instead of being traversed.
    pub(crate) fn clear_regular_files(&self) -> Result<(), String> {
        for name in self.list_names()? {
            self.delete_file(&name).map_err(|error| {
                format!("Could not clear parent-bound report-authority entry {name}: {error}")
            })?;
        }
        self.sync()
    }

    pub(crate) fn sync(&self) -> Result<(), String> {
        imp::sync_directory(&self.file)
            .map_err(|error| format!("Could not durably save report authority: {error}"))
    }
}

trait AbsolutePath {
    fn to_absolute_path(&self) -> PathBuf;
}

impl AbsolutePath for Path {
    fn to_absolute_path(&self) -> PathBuf {
        if self.is_absolute() {
            self.to_path_buf()
        } else {
            std::env::current_dir()
                .unwrap_or_else(|_| PathBuf::from("."))
                .join(self)
        }
    }
}

fn validate_name(name: &str) -> io::Result<()> {
    if name.is_empty()
        || name == "."
        || name == ".."
        || name.contains('/')
        || name.contains('\\')
        || name.contains('\0')
    {
        Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "unsafe parent-bound report-authority filename",
        ))
    } else {
        Ok(())
    }
}

#[cfg(unix)]
mod imp {
    use super::*;
    use std::ffi::{CStr, CString};
    use std::os::fd::{AsRawFd, FromRawFd};
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::fs::MetadataExt;
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

    pub(super) fn open_directory(path: &Path) -> io::Result<File> {
        if !path.is_absolute() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "protected report storage path is not absolute",
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
                Component::RootDir => {}
                Component::Normal(name) => {
                    let name = CString::new(name.as_bytes()).map_err(|_| {
                        io::Error::new(io::ErrorKind::InvalidInput, "directory name contains NUL")
                    })?;
                    let fd = unsafe {
                        libc::openat(
                            current.as_raw_fd(),
                            name.as_ptr(),
                            libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_DIRECTORY | libc::O_CLOEXEC,
                        )
                    };
                    if fd < 0 {
                        return Err(io::Error::last_os_error());
                    }
                    current = unsafe { File::from_raw_fd(fd) };
                }
                Component::CurDir => {}
                Component::ParentDir | Component::Prefix(_) => {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "protected report storage contains an unsafe path component",
                    ));
                }
            }
        }
        Ok(current)
    }

    pub(super) fn same_identity(left: &File, right: &File) -> io::Result<bool> {
        let left = left.metadata()?;
        let right = right.metadata()?;
        Ok(left.dev() == right.dev() && left.ino() == right.ino())
    }

    pub(super) fn create_new(parent: &File, name: &str, mode: u32) -> io::Result<File> {
        let name = CString::new(name)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "filename contains NUL"))?;
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                mode as libc::mode_t,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    pub(super) fn open_regular(parent: &File, name: &str) -> io::Result<File> {
        let name = CString::new(name)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "filename contains NUL"))?;
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    pub(super) fn list_names(parent: &File) -> io::Result<Vec<String>> {
        let duplicate = unsafe { libc::fcntl(parent.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
        if duplicate < 0 {
            return Err(io::Error::last_os_error());
        }
        let directory = unsafe { libc::fdopendir(duplicate) };
        if directory.is_null() {
            let error = io::Error::last_os_error();
            unsafe {
                libc::close(duplicate);
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
            let name = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) };
            let bytes = name.to_bytes();
            if bytes == b"." || bytes == b".." {
                continue;
            }
            let name = match std::str::from_utf8(bytes) {
                Ok(name) => name.to_string(),
                Err(_) => {
                    failure = Some(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "report-authority filename is not UTF-8",
                    ));
                    break;
                }
            };
            names.push(name);
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

    pub(super) fn delete_file(parent: &File, name: &str) -> io::Result<()> {
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
        match same_identity(&reviewed, &anchored) {
            Ok(true) => {}
            Ok(false) => {
                drop(anchored);
                let _ = rename_no_replace(parent, &anchor, name);
                return Err(io::Error::other(
                    "report-authority child changed before deletion commit",
                ));
            }
            Err(identity_error) => {
                drop(anchored);
                match rename_no_replace(parent, &anchor, name) {
                    Ok(()) => {}
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
                    Err(restore_error) => {
                        return Err(io::Error::other(format!(
                            "could not verify report-authority child identity ({identity_error}); preserved private deletion anchor after restore failed: {restore_error}"
                        )));
                    }
                }
                return Err(identity_error);
            }
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
        directory.sync_all()
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
    use std::ffi::{OsStr, OsString, c_void};
    use std::mem::size_of;
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use std::os::windows::io::{AsRawHandle, FromRawHandle, RawHandle};
    use std::path::Component;
    use std::ptr::null_mut;

    type Handle = *mut c_void;

    const FILE_LIST_DIRECTORY: u32 = 0x0001;
    const FILE_READ_DATA: u32 = 0x0001;
    const FILE_WRITE_DATA: u32 = 0x0002;
    const FILE_TRAVERSE: u32 = 0x0020;
    const FILE_READ_ATTRIBUTES: u32 = 0x0080;
    const DELETE: u32 = 0x0001_0000;
    const SYNCHRONIZE: u32 = 0x0010_0000;
    const FILE_SHARE_READ: u32 = 0x0000_0001;
    const FILE_SHARE_WRITE: u32 = 0x0000_0002;
    const FILE_SHARE_DELETE: u32 = 0x0000_0004;
    const OPEN_EXISTING: u32 = 3;
    const FILE_ATTRIBUTE_NORMAL: u32 = 0x0000_0080;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;
    const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
    const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
    const FILE_OPEN: u32 = 1;
    const FILE_CREATE: u32 = 2;
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

    pub(super) fn open_directory(path: &Path) -> io::Result<File> {
        if !path.is_absolute() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "protected report storage path is not absolute",
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
                        "protected report storage contains an unsafe path component",
                    ));
                }
            }
        }
        if !rooted {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "protected report storage has no filesystem root",
            ));
        }
        let mut current = open_directory_by_path(&root)?;
        for name in names {
            current = open_relative_directory(&current, &name)?;
        }
        Ok(current)
    }

    pub(super) fn same_identity(left: &File, right: &File) -> io::Result<bool> {
        let left = information(left)?;
        let right = information(right)?;
        Ok(left.volume_serial_number == right.volume_serial_number
            && left.file_index_high == right.file_index_high
            && left.file_index_low == right.file_index_low)
    }

    pub(super) fn create_new(parent: &File, name: &str, _mode: u32) -> io::Result<File> {
        open_relative(
            parent,
            OsStr::new(name),
            FILE_WRITE_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
            FILE_CREATE,
            FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
        )
    }

    pub(super) fn open_regular(parent: &File, name: &str) -> io::Result<File> {
        open_relative(
            parent,
            OsStr::new(name),
            FILE_READ_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
            FILE_OPEN,
            FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
        )
    }

    pub(super) fn list_names(directory: &File) -> io::Result<Vec<String>> {
        let mut names = Vec::new();
        let mut restart_scan = 1u8;
        loop {
            let mut buffer = [0u8; 4096];
            let mut io_status = [0usize; 2];
            let status = unsafe {
                NtQueryDirectoryFile(
                    directory.as_raw_handle() as Handle,
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
            if name_bytes % 2 != 0 || 12usize.saturating_add(name_bytes) > information {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "NtQueryDirectoryFile returned an invalid filename length",
                ));
            }
            let wide = buffer[12..12 + name_bytes]
                .chunks_exact(2)
                .map(|pair| u16::from_ne_bytes([pair[0], pair[1]]))
                .collect::<Vec<_>>();
            let name = OsString::from_wide(&wide).into_string().map_err(|_| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    "report-authority filename is not Unicode",
                )
            })?;
            if name != "." && name != ".." {
                names.push(name);
            }
        }
        names.sort();
        Ok(names)
    }

    pub(super) fn delete_file(parent: &File, name: &str) -> io::Result<()> {
        let file = match open_relative(
            parent,
            OsStr::new(name),
            DELETE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
            FILE_OPEN,
            FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
        ) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(error),
        };
        super::run_before_delete_test_hook(name);
        let mut disposition = 1u8;
        let mut io_status = [0usize; 2];
        let status = unsafe {
            NtSetInformationFile(
                file.as_raw_handle() as Handle,
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
        drop(file);
        Ok(())
    }

    pub(super) fn sync_directory(_directory: &File) -> io::Result<()> {
        Ok(())
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
        let file = unsafe { File::from_raw_handle(handle as RawHandle) };
        require_not_reparse(&file)?;
        Ok(file)
    }

    fn open_relative_directory(parent: &File, name: &OsStr) -> io::Result<File> {
        open_relative(
            parent,
            name,
            FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
            FILE_OPEN,
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
        wide.push(0);
        let byte_len = (wide.len() - 1)
            .checked_mul(2)
            .and_then(|value| u16::try_from(value).ok())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "filename is too long"))?;
        let mut unicode = UnicodeString {
            length: byte_len,
            maximum_length: byte_len.saturating_add(2),
            buffer: wide.as_mut_ptr(),
        };
        let mut attributes = ObjectAttributes {
            length: size_of::<ObjectAttributes>() as u32,
            root_directory: parent.as_raw_handle() as Handle,
            object_name: &mut unicode,
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

    fn require_not_reparse(file: &File) -> io::Result<()> {
        if information(file)?.file_attributes & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
            Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "parent-bound report-authority entry is a reparse point",
            ))
        } else {
            Ok(())
        }
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
        let ok =
            unsafe { GetFileInformationByHandle(file.as_raw_handle() as Handle, &mut information) };
        if ok == 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(information)
        }
    }
}
