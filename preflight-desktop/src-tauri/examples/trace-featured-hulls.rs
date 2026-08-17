//! Prints the featured hull traces the desktop build derives from a chosen installation.
//!
//! This is the same code path the app runs, not a second implementation of it, so what it prints
//! is what the product draws. It exists so the traces can be looked at -- in a browser preview, a
//! diff, or a review -- without building and launching the desktop shell, and so that
//! `src/previewTracedHulls.json` has a command that regenerates it rather than a provenance note.
//!
//!   cargo run --example trace-featured-hulls -- /Applications/Starsector.app/Contents/Resources/Java \
//!     > ../src/previewTracedHulls.json

use starsector_preflight_desktop_lib::hulls::read_wireframe_hulls;
use std::path::PathBuf;

fn main() {
    let Some(game) = std::env::args_os().nth(1).map(PathBuf::from) else {
        eprintln!("usage: trace-featured-hulls <starsector-directory>");
        std::process::exit(2);
    };
    match read_wireframe_hulls(&game) {
        Ok(catalog) => println!(
            "{}",
            serde_json::to_string(&catalog.featured_traces()).expect("serialize catalog")
        ),
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(1);
        }
    }
}
