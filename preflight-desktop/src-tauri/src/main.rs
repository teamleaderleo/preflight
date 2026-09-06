#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

#[cfg(unix)]
mod single_instance {
    use std::env;
    use std::fs::{self, DirBuilder, File, OpenOptions};
    use std::io;
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt};
    use std::path::{Path, PathBuf};
    use std::thread;
    use std::time::{Duration, Instant};

    const LOCK_EX: i32 = 2;
    const LOCK_NB: i32 = 4;
    const RESTART_GRACE: Duration = Duration::from_secs(2);
    const RETRY_INTERVAL: Duration = Duration::from_millis(25);

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
        acquire_path(lock_path(None)?, RESTART_GRACE)
    }

    #[cfg(test)]
    pub fn acquire_for_test(suffix: &str) -> io::Result<Acquisition> {
        acquire_path(lock_path(Some(suffix))?, Duration::ZERO)
    }

    #[cfg(test)]
    pub fn acquire_for_test_with_grace(suffix: &str, grace: Duration) -> io::Result<Acquisition> {
        acquire_path(lock_path(Some(suffix))?, grace)
    }

    fn lock_path(suffix: Option<&str>) -> io::Result<PathBuf> {
        // SAFETY: geteuid takes no arguments and has no memory-safety preconditions.
        let uid = unsafe { geteuid() };
        let directory = private_runtime_directory(uid)?;
        let suffix = suffix.map(|value| format!("-{value}")).unwrap_or_default();
        Ok(directory.join(format!("starsector-preflight-desktop{suffix}.lock")))
    }

    fn private_runtime_directory(uid: u32) -> io::Result<PathBuf> {
        #[cfg(target_os = "linux")]
        {
            if let Some(configured) = env::var_os("XDG_RUNTIME_DIR") {
                let directory = PathBuf::from(configured);
                validate_private_directory(&directory, uid)?;
                return Ok(directory);
            }

            let standard = PathBuf::from(format!("/run/user/{uid}"));
            match fs::symlink_metadata(&standard) {
                Ok(_) => {
                    validate_private_directory(&standard, uid)?;
                    return Ok(standard);
                }
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(error),
            }
        }

        // macOS normally supplies a per-user TMPDIR. Linux containers may not have a runtime
        // directory, so create and validate an owner-private child before putting the stable lock
        // name into a shared temporary namespace.
        let directory = env::temp_dir().join(format!("starsector-preflight-desktop-{uid}"));
        match DirBuilder::new().mode(0o700).create(&directory) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(error),
        }
        validate_private_directory(&directory, uid)?;
        Ok(directory)
    }

    fn validate_private_directory(path: &Path, uid: u32) -> io::Result<()> {
        let metadata = fs::symlink_metadata(path)?;
        if !metadata.file_type().is_dir() || metadata.uid() != uid || metadata.mode() & 0o077 != 0 {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                format!(
                    "single-instance runtime directory is not a real owner-private directory: {}",
                    path.display()
                ),
            ));
        }
        Ok(())
    }

    fn acquire_path(path: PathBuf, grace: Duration) -> io::Result<Acquisition> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .mode(0o600)
            .custom_flags(O_NOFOLLOW)
            .open(path)?;
        let started = Instant::now();

        loop {
            // SAFETY: the descriptor belongs to `file` and remains open for the call and Guard lifetime.
            if unsafe { flock(file.as_raw_fd(), LOCK_EX | LOCK_NB) } == 0 {
                return Ok(Acquisition::Primary(Guard { _file: file }));
            }

            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::WouldBlock {
                return Err(error);
            }
            let elapsed = started.elapsed();
            if elapsed >= grace {
                return Ok(Acquisition::AlreadyRunning);
            }
            thread::sleep(RETRY_INTERVAL.min(grace.saturating_sub(elapsed)));
        }
    }
}

#[cfg(windows)]
mod single_instance {
    use std::ffi::c_void;
    use std::io;
    use std::ptr;
    use std::thread;
    use std::time::{Duration, Instant};

    type Handle = *mut c_void;
    const ERROR_ALREADY_EXISTS: u32 = 183;
    const RESTART_GRACE: Duration = Duration::from_secs(2);
    const RETRY_INTERVAL: Duration = Duration::from_millis(25);

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
        acquire_name("Local\\StarsectorPreflightDesktop", RESTART_GRACE)
    }

    #[cfg(test)]
    pub fn acquire_for_test(suffix: &str) -> io::Result<Acquisition> {
        acquire_name(
            &format!("Local\\StarsectorPreflightDesktop-{suffix}"),
            Duration::ZERO,
        )
    }

    #[cfg(test)]
    pub fn acquire_for_test_with_grace(suffix: &str, grace: Duration) -> io::Result<Acquisition> {
        acquire_name(
            &format!("Local\\StarsectorPreflightDesktop-{suffix}"),
            grace,
        )
    }

    fn acquire_name(name: &str, grace: Duration) -> io::Result<Acquisition> {
        let name: Vec<u16> = format!("{name}\0").encode_utf16().collect();
        let started = Instant::now();

        loop {
            // SAFETY: attributes is null, the name is NUL-terminated, and the returned handle is owned here.
            let handle = unsafe { CreateMutexW(ptr::null(), 0, name.as_ptr()) };
            if handle.is_null() {
                return Err(io::Error::last_os_error());
            }

            // SAFETY: GetLastError has no memory-safety preconditions and reports CreateMutexW status.
            let status = unsafe { GetLastError() };
            if status != ERROR_ALREADY_EXISTS {
                return Ok(Acquisition::Primary(Guard { handle }));
            }

            // The existing process still owns the object. Close this peer handle before retrying so
            // the restart child does not keep the old mutex alive after its parent exits.
            // SAFETY: this collision handle is closed exactly once and is not retained in a Guard.
            unsafe {
                let _ = CloseHandle(handle);
            }
            let elapsed = started.elapsed();
            if elapsed >= grace {
                return Ok(Acquisition::AlreadyRunning);
            }
            thread::sleep(RETRY_INTERVAL.min(grace.saturating_sub(elapsed)));
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

#[cfg(test)]
mod tests {
    use super::single_instance::{self, Acquisition};
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;

    #[test]
    fn single_instance_guard_refuses_a_peer_and_releases_on_drop() {
        let suffix = format!("test-{}", std::process::id());
        let first = match single_instance::acquire_for_test(&suffix).unwrap() {
            Acquisition::Primary(guard) => guard,
            Acquisition::AlreadyRunning => panic!("test guard unexpectedly already owned"),
        };

        assert!(matches!(
            single_instance::acquire_for_test(&suffix).unwrap(),
            Acquisition::AlreadyRunning
        ));

        drop(first);
        assert!(matches!(
            single_instance::acquire_for_test(&suffix).unwrap(),
            Acquisition::Primary(_)
        ));
    }

    #[test]
    fn restart_grace_acquires_after_the_previous_process_releases() {
        let suffix = format!("restart-test-{}", std::process::id());
        let (ready_tx, ready_rx) = mpsc::channel();
        let holder_suffix = suffix.clone();
        let holder = thread::spawn(move || {
            let guard = match single_instance::acquire_for_test(&holder_suffix).unwrap() {
                Acquisition::Primary(guard) => guard,
                Acquisition::AlreadyRunning => panic!("restart fixture unexpectedly already owned"),
            };
            ready_tx.send(()).unwrap();
            thread::sleep(Duration::from_millis(100));
            drop(guard);
        });

        ready_rx.recv().unwrap();
        assert!(matches!(
            single_instance::acquire_for_test_with_grace(&suffix, Duration::from_secs(1)).unwrap(),
            Acquisition::Primary(_)
        ));
        holder.join().unwrap();
    }
}
