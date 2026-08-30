# CI maintenance map

Preflight keeps many focused GitHub Actions workflows because their path filters and test selectors
carry useful regression intent. Shared mechanics should have a small number of owners, while each
focused workflow should continue to show what it tests.

## Shared mechanics already centralized

| Mechanic | Owner | Representative callers |
| --- | --- | --- |
| JDK 17/21 selection and Maven dependency cache | `.github/actions/setup-build-jdk/action.yml` | `ci.yml`, `java-analysis.yml`, `desktop-ci.yml`, `distribution.yml`, adapter/cache/index focused jobs |
| Contributor Maven distribution | `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | ordinary Java CI and local contributor commands |
| Verified desktop engine JAR | `desktop-ci.yml` `engine` job | desktop frontend, native-host validation, platform package jobs |
| Verified desktop frontend distribution | `desktop-ci.yml` `frontend` job | native platform package jobs |
| Release package boundary verification | `scripts/verify_release_boundary.py` plus package verification scripts | ordinary Java CI, distribution/release jobs |

A repository search for `uses: ./.github/actions/setup-build-jdk` currently finds the ordinary CI,
desktop/release jobs, and more than twenty focused Java/probe workflows. That is the highest-value
shared setup owner: Java version or Maven-cache policy should normally change there once.

## Repetition that is intentional for now

Keep these items visible in focused workflow files unless several callers become truly identical:

- trigger paths and event types;
- Maven `-Dtest` / `-Dit.test` selectors;
- operating-system matrices;
- specialized fixture preparation;
- artifact names and retention periods;
- release permissions and candidate-publication conditions.

These values explain why a workflow exists. Hiding them behind a large reusable-workflow parameter
list would make regression gates harder to review.

## Good extraction candidates

When changing several workflows at once, look first for these repeated mechanics:

1. focused Maven invocation plus failure-log collection (`surefire-reports` / `failsafe-reports`);
2. the exact Node 24 patch in `.node-version` + `npm ci` setup for desktop jobs;
3. Rust toolchain + Cargo download/build caches for native-host jobs;
4. repeated artifact upload/download conventions used by candidate validation.

Extract one only when the callers need the same lifecycle and failure behavior. Preserve selectors,
matrices, and artifact identity at the caller whenever possible.

## Updating a shared action or pin

For a shared setup or action-version change:

1. Update the shared owner or all occurrences of the exact third-party action pin.
2. Search `.github/workflows` for the previous commit SHA to catch focused lanes that are easy to
   overlook.
3. Prove at least one ordinary Java caller and, when relevant, one desktop/native caller before
   merging. Release-only action changes should also exercise the closest non-publishing candidate
   path available.
4. Do not wait on unrelated long-running installers once the changed mechanic has executed
   successfully on every affected operating system.
5. If a workflow intentionally stays on an older pin, add an inline comment explaining why.

The `actions/cache` v6 update is an example: the cache paths and keys stayed unchanged, and the new
runtime was exercised by Linux validation plus Linux/macOS/Windows package callers before merge.

## Current direction

The desktop CI split and operator/Maven parallelization already removed substantial duplicated work.
Further consolidation should target repeated setup/error-handling mechanics, one family at a time,
without combining the focused regression workflows into one generic workflow.
