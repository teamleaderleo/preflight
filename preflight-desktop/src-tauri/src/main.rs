#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

#[cfg(unix)]
mod single_instance {
    use std::env;
    use std::fs::{self, DirBuilder, File, OpenOptions};
    use std::io;
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt};
    use std::os::unix::net::{UnixListener, UnixStream};
    use std::path::{Path, PathBuf};
    use std::sync::mpsc::Sender;
    use std::thread;
    use std::time::{Duration, Instant};

    const LOCK_EX: i32 = 2;
    const LOCK_NB: i32 = 4;
    const RESTART_GRACE: Duration = Duration::from_secs(2);
    const RETRY_INTERVAL: Duration = Duration::from_millis(25);
    // macOS limits a socket path to 104 bytes, so the focus socket keeps a short name beside the
    // lock inside the owner-private directory.
    const FOCUS_SOCKET_NAME: &str = "focus.sock";

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
        focus_socket: PathBuf,
        serving_focus: bool,
    }

    impl Guard {
        /// Accept focus handoffs from later launches for the lifetime of this guard. Each
        /// connection to the owner-private socket becomes one request on `requests`; the
        /// listener thread ends when the receiver is dropped or the process exits.
        pub fn serve_focus_requests(&mut self, requests: Sender<()>) -> io::Result<()> {
            // The exclusive lock proves no live primary owns the socket, so a leftover path from
            // a crashed process is stale and safe to replace.
            match fs::remove_file(&self.focus_socket) {
                Ok(()) => {}
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(error),
            }
            let listener = UnixListener::bind(&self.focus_socket)?;
            self.serving_focus = true;
            fs::set_permissions(&self.focus_socket, fs::Permissions::from_mode(0o600))?;
            thread::Builder::new()
                .name("single-instance-focus".to_string())
                .spawn(move || {
                    for connection in listener.incoming() {
                        if connection.is_err() {
                            continue;
                        }
                        if requests.send(()).is_err() {
                            break;
                        }
                    }
                })?;
            Ok(())
        }
    }

    impl Drop for Guard {
        fn drop(&mut self) {
            if self.serving_focus {
                let _ = fs::remove_file(&self.focus_socket);
            }
        }
    }

    pub fn acquire() -> io::Result<Acquisition> {
        acquire_path(lock_path(None)?, focus_socket_path(None)?, RESTART_GRACE)
    }

    /// Ask the primary process to bring its window forward. Connecting is the whole request.
    pub fn request_primary_focus() -> io::Result<()> {
        request_focus_at(focus_socket_path(None)?)
    }

    fn request_focus_at(socket: PathBuf) -> io::Result<()> {
        UnixStream::connect(socket).map(drop)
    }

    #[cfg(test)]
    pub fn acquire_for_test(suffix: &str) -> io::Result<Acquisition> {
        acquire_path(
            lock_path(Some(suffix))?,
            focus_socket_path(Some(suffix))?,
            Duration::ZERO,
        )
    }

    #[cfg(test)]
    pub fn acquire_for_test_with_grace(suffix: &str, grace: Duration) -> io::Result<Acquisition> {
        acquire_path(
            lock_path(Some(suffix))?,
            focus_socket_path(Some(suffix))?,
            grace,
        )
    }

    #[cfg(test)]
    pub fn request_primary_focus_for_test(suffix: &str) -> io::Result<()> {
        request_focus_at(focus_socket_path(Some(suffix))?)
    }

    #[cfg(test)]
    pub fn focus_socket_path_for_test(suffix: &str) -> io::Result<PathBuf> {
        focus_socket_path(Some(suffix))
    }

    fn lock_path(suffix: Option<&str>) -> io::Result<PathBuf> {
        let directory = private_runtime_directory(current_uid())?;
        let suffix = suffix.map(|value| format!("-{value}")).unwrap_or_default();
        Ok(directory.join(format!("starsector-preflight-desktop{suffix}.lock")))
    }

    fn focus_socket_path(suffix: Option<&str>) -> io::Result<PathBuf> {
        let directory = private_runtime_directory(current_uid())?;
        Ok(match suffix {
            Some(suffix) => directory.join(format!("f-{suffix}.sock")),
            None => directory.join(FOCUS_SOCKET_NAME),
        })
    }

    fn current_uid() -> u32 {
        // SAFETY: geteuid takes no arguments and has no memory-safety preconditions.
        unsafe { geteuid() }
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

    fn acquire_path(
        path: PathBuf,
        focus_socket: PathBuf,
        grace: Duration,
    ) -> io::Result<Acquisition> {
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
                return Ok(Acquisition::Primary(Guard {
                    _file: file,
                    focus_socket,
                    serving_focus: false,
                }));
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
    use std::sync::mpsc::Sender;
    use std::thread;
    use std::time::{Duration, Instant};

    type Handle = *mut c_void;
    const ERROR_ALREADY_EXISTS: u32 = 183;
    const RESTART_GRACE: Duration = Duration::from_secs(2);
    const RETRY_INTERVAL: Duration = Duration::from_millis(25);
    const SW_RESTORE: i32 = 9;
    // The main window's class name from tauri.conf.json. Matching the class instead of the title
    // keeps an Explorer window for a folder named "Preflight" from receiving the handoff.
    const MAIN_WINDOW_CLASS: &str = "PreflightDesktopMainWindow";

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn CreateMutexW(attributes: *const c_void, initial_owner: i32, name: *const u16) -> Handle;
        fn CloseHandle(handle: Handle) -> i32;
        fn GetLastError() -> u32;
    }

    #[link(name = "user32")]
    unsafe extern "system" {
        fn FindWindowW(class_name: *const u16, window_name: *const u16) -> Handle;
        fn IsIconic(window: Handle) -> i32;
        fn ShowWindow(window: Handle, command: i32) -> i32;
        fn SetForegroundWindow(window: Handle) -> i32;
    }

    pub enum Acquisition {
        Primary(Guard),
        AlreadyRunning,
    }

    pub struct Guard {
        handle: Handle,
    }

    impl Guard {
        /// Windows needs no listener: a later launch restores and foregrounds the primary window
        /// itself, because the freshly launched process is the one holding foreground rights.
        /// Dropping the sender ends the receiving thread immediately.
        pub fn serve_focus_requests(&mut self, requests: Sender<()>) -> io::Result<()> {
            drop(requests);
            Ok(())
        }
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

    /// Bring the primary process's main window forward from this process.
    pub fn request_primary_focus() -> io::Result<()> {
        let class_name: Vec<u16> = format!("{MAIN_WINDOW_CLASS}\0").encode_utf16().collect();
        // SAFETY: the class name is NUL-terminated and the window name pointer may be null.
        let window = unsafe { FindWindowW(class_name.as_ptr(), ptr::null()) };
        if window.is_null() {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                "the running Preflight window was not found",
            ));
        }
        // SAFETY: `window` is a live top-level window handle; these calls only read or change
        // its show state and take no memory ownership.
        unsafe {
            if IsIconic(window) != 0 {
                ShowWindow(window, SW_RESTORE);
            }
            if SetForegroundWindow(window) == 0 {
                return Err(io::Error::other(
                    "Windows declined to move the running Preflight window forward",
                ));
            }
        }
        Ok(())
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
    let mut single_instance = match single_instance::acquire() {
        Ok(single_instance::Acquisition::Primary(guard)) => guard,
        Ok(single_instance::Acquisition::AlreadyRunning) => {
            // A second double-click should show the existing window, not silently do nothing.
            match single_instance::request_primary_focus() {
                Ok(()) => {
                    eprintln!("Preflight is already running; its window was brought forward.")
                }
                Err(error) => {
                    eprintln!("Preflight is already running; this launch will exit ({error}).")
                }
            }
            return;
        }
        Err(error) => {
            eprintln!("Preflight could not establish single-instance ownership: {error}");
            std::process::exit(1);
        }
    };

    // Focus handoff is polish on top of the instance guard, so a socket problem only costs the
    // handoff rather than the launch.
    let (focus_requests, focus_receiver) = std::sync::mpsc::channel();
    if let Err(error) = single_instance.serve_focus_requests(focus_requests) {
        eprintln!("Preflight cannot accept focus requests from later launches: {error}");
    }

    starsector_preflight_desktop_lib::run(focus_receiver);
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

    #[cfg(unix)]
    #[test]
    fn a_later_launch_hands_focus_to_the_primary_and_stale_sockets_are_replaced() {
        // Short on purpose: the socket path must stay under the macOS 104-byte limit.
        let suffix = format!("ft{}", std::process::id());
        let mut first = match single_instance::acquire_for_test(&suffix).unwrap() {
            Acquisition::Primary(guard) => guard,
            Acquisition::AlreadyRunning => panic!("focus fixture unexpectedly already owned"),
        };
        assert!(
            single_instance::request_primary_focus_for_test(&suffix).is_err(),
            "no primary is listening before the guard serves focus requests"
        );

        let (requests, received) = mpsc::channel();
        first.serve_focus_requests(requests).unwrap();
        single_instance::request_primary_focus_for_test(&suffix).unwrap();
        single_instance::request_primary_focus_for_test(&suffix).unwrap();
        received.recv_timeout(Duration::from_secs(5)).unwrap();
        received.recv_timeout(Duration::from_secs(5)).unwrap();

        drop(received);
        drop(first);
        // A crashed primary never removes its socket path. The next primary owns the lock, so it
        // replaces the stale path instead of failing to bind.
        std::fs::write(
            single_instance::focus_socket_path_for_test(&suffix).unwrap(),
            b"",
        )
        .unwrap();
        let mut second = match single_instance::acquire_for_test(&suffix).unwrap() {
            Acquisition::Primary(guard) => guard,
            Acquisition::AlreadyRunning => panic!("released fixture unexpectedly still owned"),
        };
        let (requests, received) = mpsc::channel();
        second.serve_focus_requests(requests).unwrap();
        single_instance::request_primary_focus_for_test(&suffix).unwrap();
        received.recv_timeout(Duration::from_secs(5)).unwrap();
        drop(second);
        assert!(
            single_instance::request_primary_focus_for_test(&suffix).is_err(),
            "a released guard removes its focus socket"
        );
    }
}
