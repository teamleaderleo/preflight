fn main() {
    println!("cargo:rerun-if-env-changed=PREFLIGHT_UPDATER_PUBLIC_KEY");
    println!("cargo:rerun-if-env-changed=PREFLIGHT_REPORT_INTAKE_ORIGIN");

    if std::env::var_os("PREFLIGHT_REPORT_INTAKE_ORIGIN").is_some() {
        panic!(
            "protocol #798 is local-only: PREFLIGHT_REPORT_INTAKE_ORIGIN must be absent from beta builds"
        );
    }

    tauri_build::build()
}
