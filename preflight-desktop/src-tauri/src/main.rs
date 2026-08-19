#[cfg(unix)]
mod single_instance {
    use std::fs::{File, OpenOptions};
    use std::io;
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::OpenOptionsExt;
    use std::path::PathBuf;

    const LOCK_EX: i32 = 2;
    const LOCK_NB: i32 = 4;

    #[cfg(target_os = "linux")]
    const O_NOFOLLOW: i32 = 0x20000;
    #[cfg(target_os = "macos")]
    const O_NOFOLLOW: i32 = 0x0100;
    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    const O_NOFOLLOW: i32 = 0;

    unsafe extern "C" {
        fn flock(fd: i32, operation: i32) -> i32;
        fn geteuid() -> u32;
    }

    pub enum Acquisition {
        Primary(Guard),
        AlreadyRunning,
    }

    pub struct Guard {
        _file: File,
    }

    pub fn acquire() -> io::Result<Acquisition> {
        // SAFETY: geteuid takes no arguments and has no memory-safety preconditions.
        let uid = unsafe { geteuid() };
        let path = PathBuf::from(format!("/tmp/starsector-preflight-desktop-{uid}.lock"));
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .mode(0o600)
            .custom_flags(O_NOFOLLOW)
            .open(path)?;

        // SAFETY: the descriptor belongs to `file` and remains open for the call and Guard lifetime.
        if unsafe { flock(file.as_raw_fd(), LOCK_EX | LOCK_NB) } == 0 {
            return Ok(Acquisition::Primary(Guard { _file: file }));
        }

        let error = io::Error::last_os_error();
        if error.kind() == io::ErrorKind::WouldBlock {
            Ok(Acquisition::AlreadyRunning)
        } else {
            Err(error)
        }
    }
}

#[cfg(windows)]
mod single_instance {
    use std::ffi::c_void;
    use std::io;
    use std::ptr;

    type Handle = *mut c_void;
    const ERROR_ALREADY_EXISTS: u32 = 183;

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn CreateMutexW(attributes: *const c_void, initial_owner: i32, name: *const u16) -> Handle;
        fn CloseHandle(handle: Handle) -> i32;
        fn GetLastError() -> u32;
    }

    pub enum Acquisition {
        Primary(Guard),
        AlreadyRunning,
    }

    pub struct Guard {
        handle: Handle,
    }

    impl Drop for Guard {
        fn drop(&mut self) {
            // SAFETY: `handle` is a live kernel handle returned by CreateMutexW and is closed once.
            unsafe {
                let _ = CloseHandle(self.handle);
            }
        }
    }

    pub fn acquire() -> io::Result<Acquisition> {
        let name: Vec<u16> = "Local\\StarsectorPreflightDesktop\0".encode_utf16().collect();
        // SAFETY: attributes is null, the name is NUL-terminated, and the returned handle is owned here.
        let handle = unsafe { CreateMutexW(ptr::null(), 0, name.as_ptr()) };
        if handle.is_null() {
            return Err(io::Error::last_os_error());
        }

        // SAFETY: GetLastError has no memory-safety preconditions and reports CreateMutexW status.
        let status = unsafe { GetLastError() };
        if status == ERROR_ALREADY_EXISTS {
            // SAFETY: this branch declines ownership and closes its newly returned handle exactly once.
            unsafe {
                let _ = CloseHandle(handle);
            }
            Ok(Acquisition::AlreadyRunning)
        } else {
            Ok(Acquisition::Primary(Guard { handle }))
        }
    }
}

fn main() {
    let _single_instance = match single_instance::acquire() {
        Ok(single_instance::Acquisition::Primary(guard)) => guard,
        Ok(single_instance::Acquisition::AlreadyRunning) => {
            eprintln!("Preflight is already running; this launch will exit.");
            return;
        }
        Err(error) => {
            eprintln!("Preflight could not establish single-instance ownership: {error}");
            std::process::exit(1);
        }
    };

    starsector_preflight_desktop_lib::run();
}
