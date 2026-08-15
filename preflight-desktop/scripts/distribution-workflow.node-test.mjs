import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflow = normalizedWorkflowText(
  readFileSync(resolve(repository, ".github/workflows/distribution.yml"), "utf8"),
);
const publication = normalizedWorkflowText(
  readFileSync(resolve(repository, ".github/workflows/publish-release.yml"), "utf8"),
);
const desktopCi = normalizedWorkflowText(
  readFileSync(resolve(repository, ".github/workflows/desktop-ci.yml"), "utf8"),
);

export function normalizedWorkflowText(value) {
  return value.replaceAll("\r\n", "\n");
}

function job(name, nextName) {
  const start = workflow.indexOf(`\n  ${name}:\n`);
  assert.notEqual(start, -1, `missing ${name} job`);
  const end = nextName ? workflow.indexOf(`\n  ${nextName}:\n`, start + 1) : workflow.length;
  assert.notEqual(end, -1, `missing ${nextName} job after ${name}`);
  return workflow.slice(start, end);
}

function desktopJob(name, nextName) {
  const start = desktopCi.indexOf(`\n  ${name}:\n`);
  assert.notEqual(start, -1, `missing desktop CI ${name} job`);
  const end = nextName ? desktopCi.indexOf(`\n  ${nextName}:\n`, start + 1) : desktopCi.length;
  assert.notEqual(end, -1, `missing desktop CI ${nextName} job after ${name}`);
  return desktopCi.slice(start, end);
}

test("workflow structure checks use stable line endings on Windows", () => {
  assert.equal(normalizedWorkflowText("jobs:\r\n  candidate:\r\n"), "jobs:\n  candidate:\n");
});

test("private candidates and pushed tags cannot authorize public publication", () => {
  const header = workflow.slice(0, workflow.indexOf("\njobs:\n"));
  const candidate = job("candidate", "publish");
  const publish = job("publish");

  assert.match(header, /signed_candidate:/);
  assert.match(header, /permissions:\n  contents: read/);
  assert.match(candidate, /github\.event_name == 'workflow_dispatch' && inputs\.signed_candidate/);
  assert.match(candidate, /permissions:\n      contents: read/);
  assert.match(candidate, /private-candidate\.invalid/);
  assert.match(candidate, /candidate-crypt\.mjs[\s\\]+decrypt/);
  assert.match(candidate, /candidate-crypt\.mjs[\s\\]+encrypt/);
  assert.match(candidate, /docs\/releases\/\$\{version\}\.md/);
  assert.match(candidate, /path: candidate-output\/\*\.pfcandidate/);
  assert.doesNotMatch(candidate, /path: candidate-input\/\*/);
  assert.doesNotMatch(candidate, /gh release|contents: write/);

  assert.match(publish, /if: startsWith\(github\.ref, 'refs\/tags\/v'\)/);
  assert.match(publish, /permissions:\n      contents: write/);
  assert.match(publish, /gh release create/);
  assert.match(publish, /--draft/);
  assert.match(publish, /--notes-file "docs\/releases\/\$\{version\}\.md"/);
  assert.doesNotMatch(publish, /--generate-notes|gh release edit|--draft=false/);
});

test("manual publication reuses and revalidates the exact tagged draft", () => {
  assert.match(publication, /workflow_dispatch:/);
  assert.match(publication, /distribution_run_id:/);
  assert.match(publication, /permissions:\n  contents: write\n  actions: read/);
  assert.match(publication, /actions\/runs\/\$DISTRIBUTION_RUN_ID/);
  assert.match(publication, /run\.get\("name"\) != "Distribution"/);
  assert.match(publication, /run\.get\("head_sha"\) != tag_sha/);
  assert.match(publication, /release\.get\("draft"\) is not True/);
  assert.match(publication, /preflight-complete-release-\$DISTRIBUTION_RUN_ID/);
  assert.match(publication, /gh release download "\$RELEASE_TAG"/);
  assert.match(publication, /Draft assets differ from the verified Distribution artifact/);
  assert.match(publication, /verify_complete_release\.py --release draft-release/);
  assert.match(publication, /Draft release changed during publication verification/);
  assert.match(publication, /Release tag moved during publication verification/);
  assert.match(publication, /gh release edit "\$RELEASE_TAG".*--draft=false/);
  assert.doesNotMatch(publication, /mvn .*verify|tauri build|cargo build|npm run build/);
});

test("signed candidates require updater credentials, release validation and the reviewed intake origin", () => {
  const distribution = job("distribution", "desktop");
  const desktop = job("desktop", "candidate");

  assert.match(distribution, /if: startsWith\(github\.ref, 'refs\/tags\/v'\) \|\| inputs\.signed_candidate/);
  assert.match(distribution, /validate-release-version\.mjs "\$tag"/);
  assert.match(distribution, /TAURI_SIGNING_PRIVATE_KEY_PASSWORD/);
  assert.match(distribution, /PREFLIGHT_REPORT_INTAKE_ORIGIN is required for a private signed candidate/);
  assert.match(distribution, /PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD must contain at least 32 characters/);
  assert.match(distribution, /path: candidate-core\/\*\.pfcandidate/);
  assert.match(desktop, /Decrypt and stage private-candidate engine JAR/);
  assert.match(desktop, /path: preflight-desktop\/candidate-desktop\/\*\.pfcandidate/);
  assert.match(desktop, /update_bundles: dmg,app/);
  assert.match(desktop, /--bundles \$\{\{ matrix\.update_bundles \}\}/);
  assert.match(desktop, /PREFLIGHT_UPDATE_RELEASE:.*inputs\.signed_candidate/);
  assert.match(desktop, /PREFLIGHT_REPORT_INTAKE_ORIGIN:.*inputs\.signed_candidate/);
  assert.doesNotMatch(workflow, /PREFLIGHT_UPDATER_ENDPOINT/);
});

test("desktop CI builds one engine and keeps validation out of the platform package matrix", () => {
  const engine = desktopJob("engine", "validate");
  const validate = desktopJob("validate", "package");
  const packageJob = desktopJob("package");

  assert.match(engine, /Build bounded engine JAR once/);
  assert.match(engine, /actions\/upload-artifact@/);
  assert.match(validate, /needs: engine/);
  assert.match(validate, /npm audit --omit=dev/);
  assert.match(validate, /npm test/);
  assert.match(validate, /cargo fmt --check/);
  assert.match(validate, /cargo test --locked/);
  assert.match(validate, /cargo clippy --locked/);

  assert.match(packageJob, /needs: \[engine, scope\]/);
  assert.match(packageJob, /if: needs\.scope\.outputs\.package == 'true'/);
  assert.match(packageJob, /actions\/download-artifact@/);
  assert.match(packageJob, /engine:prepare:verified/);
  assert.doesNotMatch(packageJob, /npm audit --omit=dev|npm test|cargo fmt --check|cargo test --locked|cargo clippy --locked/);
});

test("every native package job exercises install, automation contracts and both removal scopes", () => {
  const unconditionalExercise = /- name: Exercise native installation and removal\n        working-directory: preflight-desktop\n        run: npm run desktop:exercise-install/;
  assert.match(workflow, unconditionalExercise);
  assert.match(desktopCi, unconditionalExercise);
  const exercise = readFileSync(
    resolve(repository, "preflight-desktop/scripts/exercise-native-install.mjs"),
    "utf8",
  );
  assert.match(exercise, /exerciseSyntheticPackageContract/);
  assert.match(exercise, /exercisePackagedDesktopSmokeContract/);
  assert.match(exercise, /exercisePackagedAllDataRemoval/);
  assert.match(exercise, /gameModAndSaveDataRetained: true/);
});
