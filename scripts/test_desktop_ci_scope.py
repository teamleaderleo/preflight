#!/usr/bin/env python3

import desktop_ci_scope as scope


def test_package_sensitive_paths():
    for path in (
        ".github/workflows/desktop-ci.yml",
        ".github/actions/setup-build-jdk/action.yml",
        "pom.xml",
        "preflight-desktop/src/App.tsx",
        "preflight-desktop/src-tauri/src/lib.rs",
        "preflight-desktop/scripts/verify-native-package.mjs",
        "preflight-desktop/package-lock.json",
        "scripts/verify_linux_glibc_floor.py",
        "scripts/write_linux_builder_provenance.py",
    ):
        assert scope.needs_package_matrix([path]), path


def test_engine_only_paths_leave_packages_for_main():
    for path in (
        "preflight-agent/src/main/java/example.java",
        "preflight-cli/src/main/java/example.java",
        "preflight-core/src/main/java/example.java",
        "scripts/starsector_profile_guard.py",
        "README.md",
    ):
        assert not scope.needs_package_matrix([path]), path


def test_any_sensitive_path_wins():
    assert scope.needs_package_matrix(
        ["preflight-core/src/main/java/example.java", "preflight-desktop/src/bridge.ts"]
    )


if __name__ == "__main__":
    test_package_sensitive_paths()
    test_engine_only_paths_leave_packages_for_main()
    test_any_sensitive_path_wins()
