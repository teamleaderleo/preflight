use std::path::Path;

fn main() {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    if arguments.len() != 5 {
        eprintln!(
            "usage: campaign_smoke <java> <preflight.jar> <scenario.json> <run-directory> <game>"
        );
        std::process::exit(2);
    }
    match starsector_preflight_desktop_lib::run_campaign_smoke_harness(
        Path::new(&arguments[0]),
        Path::new(&arguments[1]),
        Path::new(&arguments[2]),
        Path::new(&arguments[3]),
        Path::new(&arguments[4]),
    ) {
        Ok(code) => std::process::exit(code),
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(1);
        }
    }
}
