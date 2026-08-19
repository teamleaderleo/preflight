from pathlib import Path

path = Path('preflight-desktop/src-tauri/src/report_authority_dir.rs')
text = path.read_text()
old = '''        if !same_identity(&reviewed, &anchored)? {
            drop(anchored);
            let _ = rename_no_replace(parent, &anchor, name);
            return Err(io::Error::other(
                "report-authority child changed before deletion commit",
            ));
        }
'''
new = '''        match same_identity(&reviewed, &anchored) {
            Ok(true) => {}
            Ok(false) => {
                drop(anchored);
                let _ = rename_no_replace(parent, &anchor, name);
                return Err(io::Error::other(
                    "report-authority child changed before deletion commit",
                ));
            }
            Err(identity_error) => {
                drop(anchored);
                match rename_no_replace(parent, &anchor, name) {
                    Ok(()) | Err(ref error) if error.kind() == io::ErrorKind::AlreadyExists => {}
                    Err(restore_error) => {
                        return Err(io::Error::other(format!(
                            "could not verify report-authority child identity ({identity_error}); preserved private deletion anchor after restore failed: {restore_error}"
                        )));
                    }
                }
                return Err(identity_error);
            }
        }
'''
assert old in text
path.write_text(text.replace(old, new, 1))
