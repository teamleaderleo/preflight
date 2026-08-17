#!/usr/bin/env python3

import desktop_ci_scope as scope


def test_package_sensitive_paths():
    for path in (
        ".github/workflows/desktop-ci.yml",
        ".github/actions/setup-build-jdk/action.yml",
        "pom.xml",
        "preflight-desktop/src-tauri/src/lib.rs",
        "preflight-desktop/scripts/verify-native-package.mjs",
        "preflight-desktop/package-lock.json",
        "preflight-desktop/package.json",
        "scripts/verify_linux_glibc_floor.py",
        "scripts/write_linux_builder_provenance.py",
    ):
        assert scope.needs_package_matrix([path]), path


def test_package_neutral_paths_skip_native_packages():
    for path in (
        "preflight-agent/src/main/java/example.java",
        "preflight-cli/src/main/java/example.java",
        "preflight-core/src/main/java/example.java",
        "preflight-desktop/index.html",
        "preflight-desktop/public/logo.svg",
        "preflight-desktop/src/App.tsx",
        "preflight-desktop/src/styles.css",
        "preflight-desktop/vite.config.ts",
        "scripts/starsector_profile_guard.py",
        "README.md",
    ):
        assert not scope.needs_package_matrix([path]), path


def test_any_sensitive_path_wins():
    assert scope.needs_package_matrix(
        ["preflight-desktop/src/App.tsx", "preflight-desktop/src-tauri/src/lib.rs"]
    )


def test_native_validation_only_tracks_native_host_changes():
    for path in (
        ".github/workflows/desktop-ci.yml",
        "preflight-desktop/src-tauri/Cargo.toml",
        "preflight-desktop/src-tauri/src/lib.rs",
    ):
        assert scope.needs_native_validation([path]), path

    for path in (
        "preflight-desktop/src/App.tsx",
        "preflight-desktop/scripts/verify-native-package.mjs",
        "preflight-desktop/package-lock.json",
        "preflight-cli/src/main/java/example.java",
    ):
        assert not scope.needs_native_validation([path]), path


if __name__ == "__main__":
    test_package_sensitive_paths()
    test_package_neutral_paths_skip_native_packages()
    test_any_sensitive_path_wins()
    test_native_validation_only_tracks_native_host_changes()
