fn main() {
    println!("cargo:rerun-if-env-changed=PREFLIGHT_UPDATER_PUBLIC_KEY");
    tauri_build::build()
}
