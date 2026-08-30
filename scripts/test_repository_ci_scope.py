#!/usr/bin/env python3

import repository_ci_scope as scope


def test_java_inputs_run_maven():
    for path in (
        ".github/workflows/ci.yml",
        ".github/actions/setup-build-jdk/action.yml",
        ".mvn/wrapper/maven-wrapper.properties",
        "mvnw",
        "mvnw.cmd",
        "pom.xml",
        "preflight-agent/src/main/java/example.java",
        "preflight-cli/src/test/java/example.java",
        "preflight-core/src/main/java/example.java",
        "preflight-synthetic-startup/pom.xml",
    ):
        assert scope.needs_java_verify([path]), path


def test_operator_inputs_run_operator_checks_without_maven():
    for path in (
        "build/ci/Containerfile",
        "scripts/repository_ci_scope.py",
        "scripts/run-startup-benchmark.sh",
    ):
        assert scope.needs_operator_checks([path]), path
        assert not scope.needs_java_verify([path]), path


def test_unrelated_workflow_only_needs_scope_job():
    path = ".github/workflows/distribution.yml"
    assert not scope.needs_java_verify([path])
    assert not scope.needs_operator_checks([path])


def test_ci_workflow_exercises_both_job_families():
    path = ".github/workflows/ci.yml"
    assert scope.needs_java_verify([path])
    assert scope.needs_operator_checks([path])


def test_any_matching_path_wins():
    paths = ["README.md", "preflight-cli/src/main/java/example.java"]
    assert scope.needs_java_verify(paths)


if __name__ == "__main__":
    test_java_inputs_run_maven()
    test_operator_inputs_run_operator_checks_without_maven()
    test_unrelated_workflow_only_needs_scope_job()
    test_ci_workflow_exercises_both_job_families()
    test_any_matching_path_wins()
