use std::fs::File;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

/// One exact opened report-authority directory generation.
///
/// Consequential file opens and cleanup are relative to this handle/descriptor, so replacing the
/// public pathname after review cannot redirect credential publication, reads, or cleanup.
pub(crate) struct BoundDirectory {
    path: PathBuf,
    file: File,
}

impl BoundDirectory {
    pub(crate) fn open(path: &Path) -> Result<Self, String> {
        let path = path.to_absolute_path();
        let file = imp::open_directory(&path)
            .map_err(|error| format!("Could not open protected report storage {}: {error}", path.display()))?;
        Ok(Self { path, file })
    }

    pub(crate) fn require_current(&self) -> Result<(), String> {
        let current = imp::open_directory(&self.path).map_err(|error| {
            format!(
                "Protected report storage changed while report authority was active: {error}"
            )
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

    pub(crate) fn read_bytes(&self, name: &str, max_bytes: u64) -> Result<Vec<u8>, String> {
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

    pub(crate) fn delete_file(&self, name: &str) -> io::Result<()> {
        validate_name(name)?;
        imp::delete_file(&self.file, name)
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
    use std::ffi::{CString, c_char, c_int};
    use std::fs::OpenOptions;
    use std::os::fd::{AsRawFd, FromRawFd};
    use std::os::unix::fs::{MetadataExt, OpenOptionsExt};

    const O_RDONLY: c_int = 0;
    const O_WRONLY: c_int = 1;
    #[cfg(target_os = "linux")]
    const O_CREAT: c_int = 0x40;
    #[cfg(target_os = "linux")]
    const O_EXCL: c_int = 0x80;
    #[cfg(target_os = "linux")]
    const O_NOFOLLOW: c_int = 0x20000;
    #[cfg(target_os = "linux")]
    const O_DIRECTORY: c_int = 0x10000;
    #[cfg(target_os = "macos")]
    const O_CREAT: c_int = 0x0200;
    #[cfg(target_os = "macos")]
    const O_EXCL: c_int = 0x0800;
    #[cfg(target_os = "macos")]
    const O_NOFOLLOW: c_int = 0x0100;
    #[cfg(target_os = "macos")]
    const O_DIRECTORY: c_int = 0x100000;

    unsafe extern "C" {
        fn openat(dirfd: c_int, path: *const c_char, flags: c_int, ...) -> c_int;
        fn unlinkat(dirfd: c_int, path: *const c_char, flags: c_int) -> c_int;
    }

    pub(super) fn open_directory(path: &Path) -> io::Result<File> {
        let mut options = OpenOptions::new();
        options
            .read(true)
            .custom_flags(O_NOFOLLOW | O_DIRECTORY);
        options.open(path)
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
            openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW,
                mode as c_int,
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
        let fd = unsafe { openat(parent.as_raw_fd(), name.as_ptr(), O_RDONLY | O_NOFOLLOW, 0) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    pub(super) fn delete_file(parent: &File, name: &str) -> io::Result<()> {
        let name = CString::new(name)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "filename contains NUL"))?;
        if unsafe { unlinkat(parent.as_raw_fd(), name.as_ptr(), 0) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    pub(super) fn sync_directory(directory: &File) -> io::Result<()> {
        directory.sync_all()
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use std::ffi::{OsStr, c_void};
    use std::mem::size_of;
    use std::os::windows::ffi::OsStrExt;
    use std::os::windows::io::{AsRawHandle, FromRawHandle, RawHandle};
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
    const FILE_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
    const OBJ_CASE_INSENSITIVE: u32 = 0x0000_0040;
    const FILE_DISPOSITION_INFORMATION: u32 = 13;
    const STATUS_OBJECT_NAME_NOT_FOUND: i32 = 0xC000_0034u32 as i32;
    const STATUS_OBJECT_NAME_COLLISION: i32 = 0xC000_0035u32 as i32;
    const STATUS_OBJECT_PATH_NOT_FOUND: i32 = 0xC000_003Au32 as i32;

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
    }

    pub(super) fn open_directory(path: &Path) -> io::Result<File> {
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
            name,
            FILE_WRITE_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
            FILE_CREATE,
        )
    }

    pub(super) fn open_regular(parent: &File, name: &str) -> io::Result<File> {
        open_relative(
            parent,
            name,
            FILE_READ_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
            FILE_OPEN,
        )
    }

    pub(super) fn delete_file(parent: &File, name: &str) -> io::Result<()> {
        let file = match open_relative(
            parent,
            name,
            DELETE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
            FILE_OPEN,
        ) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(error),
        };
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

    fn open_relative(
        parent: &File,
        name: &str,
        desired_access: u32,
        disposition: u32,
    ) -> io::Result<File> {
        let mut wide = OsStr::new(name).encode_wide().collect::<Vec<_>>();
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
                FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
                null_mut(),
                0,
            )
        };
        if status == STATUS_OBJECT_NAME_COLLISION {
            return Err(io::Error::new(io::ErrorKind::AlreadyExists, "entry already exists"));
        }
        if status == STATUS_OBJECT_NAME_NOT_FOUND || status == STATUS_OBJECT_PATH_NOT_FOUND {
            return Err(io::Error::new(io::ErrorKind::NotFound, "entry does not exist"));
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
        let ok = unsafe {
            GetFileInformationByHandle(
                file.as_raw_handle() as Handle,
                &mut information,
            )
        };
        if ok == 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(information)
        }
    }
}
