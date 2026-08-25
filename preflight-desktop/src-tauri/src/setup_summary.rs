use serde::Serialize;
use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

const SETUP_SUMMARY_FORMAT: &str = "starsector-preflight-setup-summary-export-v1";
const SETUP_SUMMARY_PREFIX: &str = "Preflight setup (public)\nSummary version: 1\n";
const SETUP_SUMMARY_MAX_BYTES: usize = 131_072;

#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SetupSummaryExport {
    format: &'static str,
    output: String,
    bytes: usize,
}

#[tauri::command]
pub(crate) fn save_setup_summary(
    output: String,
    text: String,
) -> Result<SetupSummaryExport, String> {
    validate_setup_summary(&text)?;
    let destination = setup_summary_output_path(&output)?;
    write_new_summary(&destination, text.as_bytes())?;
    Ok(SetupSummaryExport {
        format: SETUP_SUMMARY_FORMAT,
        output: destination.to_string_lossy().into_owned(),
        bytes: text.len(),
    })
}

fn validate_setup_summary(text: &str) -> Result<(), String> {
    if text.len() > SETUP_SUMMARY_MAX_BYTES {
        return Err("The setup summary is larger than Preflight's public-text limit.".to_string());
    }
    if !text.starts_with(SETUP_SUMMARY_PREFIX) || !text.ends_with('\n') {
        return Err("The setup summary does not match Preflight's public-text format.".to_string());
    }
    if text
        .chars()
        .any(|character| character != '\n' && character.is_control())
    {
        return Err("The setup summary contains unsupported control characters.".to_string());
    }
    Ok(())
}

fn setup_summary_output_path(output: &str) -> Result<PathBuf, String> {
    let requested = Path::new(output);
    if !requested.is_absolute() {
        return Err("Choose an absolute location for the setup summary.".to_string());
    }
    if requested
        .extension()
        .and_then(|extension| extension.to_str())
        .is_none_or(|extension| !extension.eq_ignore_ascii_case("txt"))
    {
        return Err("The setup summary filename must end in .txt.".to_string());
    }
    let parent = requested
        .parent()
        .ok_or_else(|| "The setup summary location has no parent folder.".to_string())?
        .canonicalize()
        .map_err(|error| format!("Could not open the setup summary folder: {error}"))?;
    if !parent.is_dir() {
        return Err("The setup summary location is not inside a folder.".to_string());
    }
    let name = requested
        .file_name()
        .ok_or_else(|| "The setup summary filename is missing.".to_string())?;
    Ok(parent.join(name))
}

fn write_new_summary(destination: &Path, bytes: &[u8]) -> Result<(), String> {
    let mut file = create_new_private(destination).map_err(|error| match error.kind() {
        io::ErrorKind::AlreadyExists => {
            "That file already exists. Choose a new filename; Preflight will not replace it."
                .to_string()
        }
        _ => format!("Could not create the setup summary: {error}"),
    })?;
    if let Err(error) = file.write_all(bytes).and_then(|()| file.sync_all()) {
        return Err(format!(
            "Could not finish the setup summary: {error}. The new file may be incomplete; Preflight did not replace an existing file."
        ));
    }
    Ok(())
}

fn create_new_private(destination: &Path) -> io::Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(destination)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn temp_directory(name: &str) -> PathBuf {
        let directory = std::env::temp_dir().join(format!(
            "preflight-setup-summary-{name}-{}",
            getrandom::u64().expect("random test suffix")
        ));
        fs::create_dir(&directory).expect("create test directory");
        directory
    }

    #[test]
    fn writes_the_exact_public_summary_once() {
        let directory = temp_directory("write");
        let destination = directory.join("setup.txt");
        let text = format!("{SETUP_SUMMARY_PREFIX}Enabled mods: 0\n");

        let receipt = save_setup_summary(destination.to_string_lossy().into_owned(), text.clone())
            .expect("save summary");

        assert_eq!(receipt.format, SETUP_SUMMARY_FORMAT);
        assert_eq!(
            PathBuf::from(&receipt.output),
            directory
                .canonicalize()
                .expect("canonical test directory")
                .join("setup.txt")
        );
        assert_eq!(receipt.bytes, text.len());
        assert_eq!(
            fs::read_to_string(&destination).expect("read summary"),
            text
        );
        fs::remove_dir_all(directory).expect("remove test directory");
    }

    #[test]
    fn refuses_to_replace_an_existing_file() {
        let directory = temp_directory("existing");
        let destination = directory.join("setup.txt");
        fs::write(&destination, "keep me").expect("seed destination");

        let error = save_setup_summary(
            destination.to_string_lossy().into_owned(),
            format!("{SETUP_SUMMARY_PREFIX}Enabled mods: 0\n"),
        )
        .expect_err("existing file must be refused");

        assert!(error.contains("already exists"), "{error}");
        assert_eq!(
            fs::read_to_string(&destination).expect("read existing"),
            "keep me"
        );
        fs::remove_dir_all(directory).expect("remove test directory");
    }

    #[test]
    fn rejects_non_text_destinations_and_unbounded_or_untyped_text() {
        let directory = temp_directory("validation");
        let wrong_extension = directory.join("setup.log");
        let valid = format!("{SETUP_SUMMARY_PREFIX}Enabled mods: 0\n");

        assert!(
            save_setup_summary(wrong_extension.to_string_lossy().into_owned(), valid)
                .expect_err("wrong extension must be refused")
                .contains("must end in .txt")
        );
        assert!(
            validate_setup_summary("arbitrary renderer text\n")
                .expect_err("untyped text must be refused")
                .contains("public-text format")
        );
        assert!(
            validate_setup_summary(&format!(
                "{SETUP_SUMMARY_PREFIX}{}\n",
                "x".repeat(SETUP_SUMMARY_MAX_BYTES)
            ))
            .expect_err("oversized text must be refused")
            .contains("public-text limit")
        );
        fs::remove_dir_all(directory).expect("remove test directory");
    }
}
