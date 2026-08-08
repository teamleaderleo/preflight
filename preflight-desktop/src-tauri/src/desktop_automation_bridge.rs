#[cfg(not(target_os = "macos"))]
use std::path::Path;
#[cfg(not(target_os = "macos"))]
use std::process::Command;

pub(crate) const ENDPOINT_ENV: &str = "PREFLIGHT_MAC_AUTOMATION_ENDPOINT";
pub(crate) const TOKEN_ENV: &str = "PREFLIGHT_MAC_AUTOMATION_TOKEN";

#[cfg(target_os = "macos")]
mod platform {
    use super::{ENDPOINT_ENV, TOKEN_ENV};
    use serde::{Deserialize, Serialize};
    use std::fs;
    use std::io::{Read, Write};
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::path::{Path, PathBuf};
    use std::process::{Command, Stdio};
    use std::sync::mpsc;
    use std::thread;
    use std::time::{Duration, Instant};

    const PROTOCOL: u32 = 1;
    const MAX_REQUEST_BYTES: u64 = 8 * 1024;
    const MAX_OUTPUT_BYTES: usize = 8 * 1024;
    const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
    const CONTINUE_X: f64 = 0.775;
    const CONTINUE_Y: f64 = 0.300;

    pub(crate) struct DesktopAutomationBridge {
        endpoint: String,
        token: String,
        stop: Option<mpsc::Sender<()>>,
        worker: Option<thread::JoinHandle<()>>,
    }

    impl DesktopAutomationBridge {
        pub(crate) fn start(run_directory: Option<&Path>) -> Result<Self, String> {
            let listener = TcpListener::bind("127.0.0.1:0")
                .map_err(|error| format!("Could not bind the macOS automation bridge: {error}"))?;
            listener.set_nonblocking(true).map_err(|error| {
                format!("Could not configure the macOS automation bridge: {error}")
            })?;
            let endpoint = listener
                .local_addr()
                .map_err(|error| format!("Could not inspect the macOS automation bridge: {error}"))?
                .to_string();
            let mut secret = [0_u8; 32];
            getrandom::fill(&mut secret).map_err(|error| {
                format!("Could not authorize the macOS automation bridge: {error}")
            })?;
            let token = secret
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>();
            let expected_token = token.clone();
            let run_directory = run_directory.map(Path::to_path_buf);
            let (stop, stopped) = mpsc::channel();
            let worker = thread::Builder::new()
                .name("preflight-macos-automation".to_string())
                .spawn(move || serve(listener, stopped, expected_token, run_directory))
                .map_err(|error| format!("Could not start the macOS automation bridge: {error}"))?;
            Ok(Self {
                endpoint,
                token,
                stop: Some(stop),
                worker: Some(worker),
            })
        }

        pub(crate) fn configure(&self, command: &mut Command) {
            command.env(ENDPOINT_ENV, &self.endpoint);
            command.env(TOKEN_ENV, &self.token);
        }
    }

    impl Drop for DesktopAutomationBridge {
        fn drop(&mut self) {
            if let Some(stop) = self.stop.take() {
                let _ = stop.send(());
            }
            if let Some(worker) = self.worker.take() {
                let _ = worker.join();
            }
        }
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct Request {
        protocol: u32,
        token: String,
        operation: String,
        pid: Option<u32>,
        argument: Option<String>,
    }

    #[derive(Serialize)]
    #[serde(rename_all = "camelCase")]
    struct Response {
        protocol: u32,
        ok: bool,
        output: Option<String>,
        error: Option<String>,
    }

    fn serve(
        listener: TcpListener,
        stop: mpsc::Receiver<()>,
        token: String,
        run_directory: Option<PathBuf>,
    ) {
        loop {
            if stop.try_recv().is_ok() {
                return;
            }
            match listener.accept() {
                Ok((stream, _)) => handle_connection(stream, &token, run_directory.as_deref()),
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(10));
                }
                Err(_) => return,
            }
        }
    }

    fn handle_connection(mut stream: TcpStream, token: &str, run_directory: Option<&Path>) {
        let _ = stream.set_read_timeout(Some(COMMAND_TIMEOUT));
        let _ = stream.set_write_timeout(Some(COMMAND_TIMEOUT));
        let response = read_request(&mut stream)
            .and_then(|request| execute_request(request, token, run_directory));
        let response = match response {
            Ok(output) => Response {
                protocol: PROTOCOL,
                ok: true,
                output: Some(output),
                error: None,
            },
            Err(error) => Response {
                protocol: PROTOCOL,
                ok: false,
                output: None,
                error: Some(bounded(&error)),
            },
        };
        if let Ok(encoded) = serde_json::to_vec(&response) {
            let _ = stream.write_all(&encoded);
            let _ = stream.write_all(b"\n");
            let _ = stream.flush();
            let _ = stream.shutdown(Shutdown::Write);
        }
    }

    fn read_request(stream: &mut TcpStream) -> Result<Request, String> {
        let mut bytes = Vec::new();
        stream
            .take(MAX_REQUEST_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|error| format!("Could not read the macOS automation request: {error}"))?;
        if bytes.len() as u64 > MAX_REQUEST_BYTES {
            return Err("The macOS automation request is too large.".to_string());
        }
        serde_json::from_slice(&bytes)
            .map_err(|error| format!("The macOS automation request is invalid: {error}"))
    }

    fn execute_request(
        request: Request,
        expected_token: &str,
        run_directory: Option<&Path>,
    ) -> Result<String, String> {
        if request.protocol != PROTOCOL || !constant_time_eq(&request.token, expected_token) {
            return Err("The macOS automation request isn't authorized.".to_string());
        }
        let script = reviewed_script(&request)?;
        if request.operation == "capture" {
            let run_directory = run_directory
                .ok_or_else(|| "Window capture isn't authorized for this probe.".to_string())?;
            return capture(run_directory, script);
        }
        run_bounded("/usr/bin/osascript", &["-e", &script])
    }

    fn reviewed_script(request: &Request) -> Result<String, String> {
        if request.operation == "probe" {
            if request.pid.is_some() || request.argument.is_some() {
                return Err("The macOS automation probe has unexpected parameters.".to_string());
            }
            return Ok(
                "tell application \"System Events\" to return UI elements enabled".to_string(),
            );
        }
        let pid = request
            .pid
            .filter(|pid| *pid > 0)
            .ok_or_else(|| "The macOS automation request needs a positive PID.".to_string())?;
        let header = process_header(pid);
        match request.operation.as_str() {
            "window-bounds" | "capture" if request.argument.is_none() => Ok(format!(
                "{header}\nset win to window 1 of targetProcess\nset winPosition to position of win\nset winSize to size of win\nreturn (item 1 of winPosition as text) & \",\" & (item 2 of winPosition as text) & \",\" & (item 1 of winSize as text) & \",\" & (item 2 of winSize as text)\nend tell"
            )),
            "activate" if request.argument.is_none() => Ok(format!(
                "{header}\nset frontmost of targetProcess to true\nreturn \"activated PID {pid}\"\nend tell"
            )),
            "observe" if request.argument.is_none() => Ok(format!(
                "{header}\nset win to window 1 of targetProcess\nset winPosition to position of win\nset winSize to size of win\nset focused to frontmost of targetProcess\nreturn \"PID {pid} window \" & (item 1 of winPosition as text) & \",\" & (item 2 of winPosition as text) & \",\" & (item 1 of winSize as text) & \",\" & (item 2 of winSize as text) & \" frontmost=\" & (focused as text)\nend tell"
            )),
            "click" if request.argument.as_deref() == Some("main-menu.continue") => Ok(format!(
                "{header}\nset frontmost of targetProcess to true\nset win to window 1 of targetProcess\nset winPosition to position of win\nset winSize to size of win\nset clickX to (item 1 of winPosition) + (round ((item 1 of winSize) * {CONTINUE_X}))\nset clickY to (item 2 of winPosition) + (round ((item 2 of winSize) * {CONTINUE_Y}))\nclick at {{clickX, clickY}}\nreturn \"clicked main-menu.continue at \" & clickX & \",\" & clickY\nend tell"
            )),
            "press-key" => {
                let key = reviewed_key(request.argument.as_deref())?;
                Ok(format!(
                    "{header}\nset frontmost of targetProcess to true\nkey code {}\nreturn \"pressed key code {}\"\nend tell",
                    key.1, key.1
                ))
            }
            "key-down" | "key-up" => {
                let key = reviewed_key(request.argument.as_deref())?;
                let action = request.operation.strip_prefix("key-").unwrap_or_default();
                Ok(format!(
                    "{header}\nset frontmost of targetProcess to true\nkey {action} \"{}\"\nreturn \"key {action}\"\nend tell",
                    key.0
                ))
            }
            "release-key" => {
                let key = reviewed_key(request.argument.as_deref())?;
                Ok(format!(
                    "tell application \"System Events\"\nset matches to (every application process whose unix id is {pid})\nkey up \"{}\"\nreturn \"key up\"\nend tell",
                    key.0
                ))
            }
            "quit" if request.argument.is_none() => Ok(format!(
                "{header}\nset frontmost of targetProcess to true\nkeystroke \"q\" using {{command down}}\nreturn \"requested quit for PID {pid}\"\nend tell"
            )),
            _ => Err("The macOS automation operation isn't reviewed.".to_string()),
        }
    }

    fn process_header(pid: u32) -> String {
        format!(
            "tell application \"System Events\"\nset matches to (every application process whose unix id is {pid})\nif (count of matches) is not 1 then error \"exact PID unavailable\" number 1728\nset targetProcess to item 1 of matches"
        )
    }

    fn reviewed_key(value: Option<&str>) -> Result<(&'static str, u8), String> {
        match value {
            Some("a") => Ok(("a", 0)),
            Some("s") => Ok(("s", 1)),
            Some("d") => Ok(("d", 2)),
            Some("w") => Ok(("w", 13)),
            Some("return") => Ok(("return", 36)),
            Some("space") => Ok(("space", 49)),
            Some("escape") => Ok(("escape", 53)),
            _ => Err("The macOS automation key isn't reviewed.".to_string()),
        }
    }

    fn capture(run_directory: &Path, bounds_script: String) -> Result<String, String> {
        let real_run = run_directory
            .canonicalize()
            .map_err(|error| format!("Could not resolve the desktop-test directory: {error}"))?;
        let bounds = run_bounded("/usr/bin/osascript", &["-e", &bounds_script])?;
        let values = bounds
            .split(',')
            .map(str::trim)
            .map(str::parse::<i32>)
            .collect::<Result<Vec<_>, _>>()
            .map_err(|_| format!("macOS returned invalid game-window bounds: {bounds}"))?;
        if values.len() != 4 || values[2] <= 0 || values[3] <= 0 {
            return Err(format!("macOS returned empty game-window bounds: {bounds}"));
        }
        let destination = real_run.join("desktop-smoke.png");
        if destination.exists() {
            fs::remove_file(&destination).map_err(|error| {
                format!("Could not replace the prior game-window capture: {error}")
            })?;
        }
        let region = format!("{},{},{},{}", values[0], values[1], values[2], values[3]);
        run_bounded(
            "/usr/sbin/screencapture",
            &[
                "-x",
                &format!("-R{region}"),
                destination.to_string_lossy().as_ref(),
            ],
        )?;
        if !destination.is_file()
            || destination
                .metadata()
                .map(|metadata| metadata.len())
                .unwrap_or(0)
                == 0
        {
            return Err(
                "macOS Screen Recording didn't produce a bounded game-window capture.".to_string(),
            );
        }
        Ok(destination.to_string_lossy().into_owned())
    }

    fn run_bounded(program: &str, arguments: &[&str]) -> Result<String, String> {
        let mut child = Command::new(program)
            .args(arguments)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|error| format!("Could not start the macOS automation command: {error}"))?;
        let deadline = Instant::now() + COMMAND_TIMEOUT;
        let status = loop {
            match child.try_wait() {
                Ok(Some(status)) => break status,
                Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(10)),
                Ok(None) => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err("The macOS automation command timed out.".to_string());
                }
                Err(error) => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(format!(
                        "Could not wait for the macOS automation command: {error}"
                    ));
                }
            }
        };
        let mut output = Vec::new();
        if let Some(mut stdout) = child.stdout.take() {
            let _ = stdout
                .by_ref()
                .take(MAX_OUTPUT_BYTES as u64 + 1)
                .read_to_end(&mut output);
        }
        if let Some(mut stderr) = child.stderr.take() {
            let mut errors = Vec::new();
            let _ = stderr
                .by_ref()
                .take(MAX_OUTPUT_BYTES as u64 + 1)
                .read_to_end(&mut errors);
            output.extend_from_slice(&errors);
        }
        let output = String::from_utf8_lossy(&output);
        if output.len() > MAX_OUTPUT_BYTES {
            return Err("The macOS automation command returned too much output.".to_string());
        }
        if !status.success() {
            return Err(format!(
                "The macOS automation command failed (exit {}): {}",
                status.code().unwrap_or(-1),
                bounded(&output)
            ));
        }
        Ok(output.trim().to_string())
    }

    fn constant_time_eq(left: &str, right: &str) -> bool {
        if left.len() != right.len() {
            return false;
        }
        left.bytes()
            .zip(right.bytes())
            .fold(0_u8, |difference, (left, right)| {
                difference | (left ^ right)
            })
            == 0
    }

    fn bounded(value: &str) -> String {
        let value = value.trim();
        value.chars().take(2_000).collect()
    }

    #[cfg(test)]
    mod tests {
        use super::{DesktopAutomationBridge, Request, reviewed_script};
        use serde_json::Value;
        use std::io::{Read, Write};
        use std::net::TcpStream;

        fn request(operation: &str, pid: Option<u32>, argument: Option<&str>) -> Request {
            Request {
                protocol: 1,
                token: "unused".to_string(),
                operation: operation.to_string(),
                pid,
                argument: argument.map(str::to_string),
            }
        }

        #[test]
        fn reviewed_native_scripts_remain_exact_pid_addressed() {
            for operation in ["window-bounds", "activate", "observe", "capture", "quit"] {
                let script = reviewed_script(&request(operation, Some(4242), None)).unwrap();
                assert!(script.contains("application process whose unix id is 4242"));
                assert!(!script.to_ascii_lowercase().contains("starsector"));
            }
        }

        #[test]
        fn native_bridge_rejects_unreviewed_targets_keys_and_parameters() {
            assert!(reviewed_script(&request("click", Some(4242), Some("other"))).is_err());
            assert!(reviewed_script(&request("press-key", Some(4242), Some("command-q"))).is_err());
            assert!(reviewed_script(&request("probe", Some(4242), None)).is_err());
            assert!(reviewed_script(&request("arbitrary-script", Some(4242), None)).is_err());
        }

        #[test]
        fn native_bridge_is_loopback_capability_authorized() {
            let bridge = DesktopAutomationBridge::start(None).unwrap();
            assert!(bridge.endpoint.starts_with("127.0.0.1:"));
            assert_eq!(bridge.token.len(), 64);
            let mut stream = TcpStream::connect(&bridge.endpoint).unwrap();
            stream
                .write_all(
                    br#"{"protocol":1,"token":"wrong","operation":"probe","pid":null,"argument":null}
"#,
                )
                .unwrap();
            stream.shutdown(std::net::Shutdown::Write).unwrap();
            let mut response = String::new();
            stream.read_to_string(&mut response).unwrap();
            let value: Value = serde_json::from_str(&response).unwrap();
            assert_eq!(value["protocol"], 1);
            assert_eq!(value["ok"], false);
            assert!(
                value["error"]
                    .as_str()
                    .unwrap()
                    .contains("isn't authorized"),
                "{value}"
            );
        }
    }
}

#[cfg(target_os = "macos")]
pub(crate) use platform::DesktopAutomationBridge;

#[cfg(not(target_os = "macos"))]
pub(crate) struct DesktopAutomationBridge;

#[cfg(not(target_os = "macos"))]
impl DesktopAutomationBridge {
    pub(crate) fn start(_run_directory: Option<&Path>) -> Result<Self, String> {
        Ok(Self)
    }

    pub(crate) fn configure(&self, _command: &mut Command) {}
}
