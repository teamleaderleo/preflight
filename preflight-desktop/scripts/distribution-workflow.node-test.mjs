import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const distribution = normalized(
  readFileSync(resolve(repository, ".github/workflows/distribution.yml"), "utf8"),
);
const publication = normalized(
  readFileSync(resolve(repository, ".github/workflows/publish-release.yml"), "utf8"),
);
const desktopCi = normalized(
  readFileSync(resolve(repository, ".github/workflows/desktop-ci.yml"), "utf8"),
);
const normalTauriConfig = readFileSync(
  resolve(repository, "preflight-desktop/src-tauri/tauri.conf.json"),
  "utf8",
);
const ciTauriConfig = readFileSync(
  resolve(repository, "preflight-desktop/src-tauri/tauri.ci.conf.json"),
  "utf8",
);
const linuxBuilderImage =
  "ubuntu:jammy-20260627@sha256:0d779ea97881505f5ef0039336ee85edba27519bdba968c284c86ee066a973c8";

function normalized(value) {
  return value.replaceAll("\r\n", "\n");
}

function job(source, name) {
  const marker = `\n  ${name}:\n`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `missing ${name} job`);
  const bodyStart = start + 1;
  const remainder = source.slice(start + marker.length);
  const next = remainder.search(/\n  [A-Za-z0-9_-]+:\n/);
  return next < 0
    ? source.slice(bodyStart)
    : source.slice(bodyStart, start + marker.length + next);
}

test("workflow text normalization is stable on Windows", () => {
  assert.equal(normalized("jobs:\r\n  candidate:\r\n"), "jobs:\n  candidate:\n");
});

test("private candidates cannot publish and public publication stays explicit", () => {
  const candidate = job(distribution, "candidate");
  const publish = job(distribution, "publish");

  assert.match(distribution, /signed_candidate:/);
  assert.match(distribution, /permissions:\n  contents: read/);

  assert.match(candidate, /github\.event_name == 'workflow_dispatch' && inputs\.signed_candidate/);
  assert.match(candidate, /permissions:\n      contents: read/);
  assert.match(candidate, /candidate-crypt\.mjs[\s\\]+decrypt/);
  assert.match(candidate, /candidate-crypt\.mjs[\s\\]+encrypt/);
  assert.match(candidate, /path: candidate-output\/\*\.pfcandidate/);
  assert.doesNotMatch(candidate, /gh release|contents: write/);

  assert.match(publish, /if: startsWith\(github\.ref, 'refs\/tags\/v'\)/);
  assert.match(publish, /permissions:\n      contents: write/);
  assert.match(publish, /gh release create/);
  assert.match(publish, /--draft/);
  assert.match(publish, /--notes-file "docs\/releases\/\$\{version\}\.md"/);
  assert.doesNotMatch(publish, /--generate-notes|gh release edit|--draft=false/);
});

test("manual publication reuses the exact verified tagged draft", () => {
  for (const input of [
    "tag:",
    "distribution_run_id:",
    "lifecycle_run_id:",
    "report_canary_run_id:",
  ]) {
    assert.match(publication, new RegExp(input));
  }
  assert.match(publication, /permissions:\n  contents: write\n  actions: read/);
  assert.match(publication, /actions\/runs\/\$DISTRIBUTION_RUN_ID/);
  assert.match(publication, /run\.get\("name"\) != "Distribution"/);
  assert.match(publication, /run\.get\("head_sha"\) != tag_sha/);
  assert.match(publication, /release\.get\("draft"\) is not True/);
  assert.match(publication, /gh release download "\$RELEASE_TAG"/);
  assert.match(publication, /verify_complete_release\.py --release draft-release/);
  assert.match(publication, /Draft assets differ from the verified Distribution artifact/);
  assert.match(publication, /Draft release changed during publication verification/);
  assert.match(publication, /Release tag moved during publication verification/);
  assert.match(publication, /gh release edit "\$RELEASE_TAG".*--draft=false/);
  assert.doesNotMatch(publication, /mvn .*verify|tauri build|cargo build/);
});

test("signed candidates require credentials, release validation and every platform", () => {
  const core = job(distribution, "distribution");
  const macWindows = job(distribution, "desktop");
  const linux = job(distribution, "desktop-linux");
  const candidate = job(distribution, "candidate");
  const publish = job(distribution, "publish");

  assert.match(core, /validate-release-version\.mjs "\$tag"/);
  assert.match(core, /TAURI_SIGNING_PRIVATE_KEY_PASSWORD/);
  assert.match(core, /PREFLIGHT_REPORT_INTAKE_ORIGIN is required for an update-signed build/);
  assert.match(core, /PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD must contain at least 32 characters/);
  assert.match(core, /Verify source and history boundary/);
  assert.match(core, /path: candidate-core\/\*\.pfcandidate/);

  for (const platform of [macWindows, linux]) {
    assert.match(platform, /Decrypt and stage private-candidate engine JAR/);
    assert.match(platform, /path: preflight-desktop\/candidate-desktop\/\*\.pfcandidate/);
    assert.match(platform, /PREFLIGHT_UPDATE_RELEASE:/);
    assert.match(platform, /PREFLIGHT_REPORT_INTAKE_ORIGIN/);
    assert.match(platform, /PREFLIGHT_UPDATER_PUBLIC_KEY/);
    assert.match(platform, /Exercise native installation and removal/);
  }

  assert.match(macWindows, /update_bundles: dmg,app/);
  assert.match(candidate, /needs: \[distribution, desktop, desktop-linux\]/);
  assert.match(publish, /needs: \[distribution, desktop, desktop-linux\]/);
  assert.doesNotMatch(distribution, /PREFLIGHT_UPDATER_ENDPOINT/);
});

test("desktop CI keeps the verified engine, frontend, native host and package boundaries", () => {
  const engine = job(desktopCi, "engine");
  const frontend = job(desktopCi, "frontend");
  const contracts = job(desktopCi, "contracts");
  const native = job(desktopCi, "validate");
  const macWindows = job(desktopCi, "package");
  const linux = job(desktopCi, "package-linux");

  assert.match(engine, /validate_jar_bytes/);
  assert.match(engine, /actions\/upload-artifact@/);

  assert.match(frontend, /npm audit --omit=dev/);
  assert.match(frontend, /npm test/);
  assert.match(frontend, /npm run build/);
  assert.match(frontend, /frontend-dist-manifest\.mjs write dist frontend-dist\.json/);

  assert.match(contracts, /npm run test:release:prepared/);

  assert.match(native, /cargo fmt --check/);
  assert.match(native, /cargo test --locked/);
  assert.match(native, /cargo clippy --locked/);

  assert.match(macWindows, /os: \[macos-latest, windows-latest\]/);
  assert.match(macWindows, /frontend-dist-manifest\.mjs[\s\\]+verify/);
  assert.match(macWindows, /--config src-tauri\/tauri\.ci\.conf\.json/);
  assert.match(macWindows, /desktop:verify-package/);
  assert.match(macWindows, /desktop:exercise-install/);

  assert.match(linux, new RegExp(linuxBuilderImage.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.match(linux, /PREFLIGHT_LINUX_MAX_GLIBC: '2\.35'/);
  assert.match(linux, /verify_linux_glibc_floor\.py/);
  assert.match(linux, /write_linux_builder_provenance\.py/);
  assert.match(linux, /desktop:verify-package/);
  assert.match(linux, /desktop:exercise-install/);

  assert.match(normalTauriConfig, /"beforeBuildCommand": "npm run build"/);
  assert.match(ciTauriConfig, /"beforeBuildCommand": ""/);
});

test("release and CI Linux packages share the compatibility boundary", () => {
  for (const linux of [job(distribution, "desktop-linux"), job(desktopCi, "package-linux")]) {
    assert.match(linux, new RegExp(linuxBuilderImage.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
    assert.match(linux, /PREFLIGHT_LINUX_MAX_GLIBC: '2\.35'/);
    assert.match(linux, /LANG: C\.UTF-8/);
    assert.match(linux, /LC_ALL: C\.UTF-8/);
    assert.match(linux, /libasound2/);
    assert.match(linux, /verify_linux_glibc_floor\.py/);
    assert.match(linux, /APPIMAGE_EXTRACT_AND_RUN: '1'/);
  }
});

test("native package exercises preserve game data while removing owned integration", () => {
  const exercise = readFileSync(
    resolve(repository, "preflight-desktop/scripts/exercise-native-install.mjs"),
    "utf8",
  );
  assert.match(exercise, /exerciseSyntheticPackageContract/);
  assert.match(exercise, /exercisePackagedDesktopSmokeContract/);
  assert.match(exercise, /exercisePackagedAllDataRemoval/);
  assert.match(exercise, /gameModAndSaveDataRetained: true/);
  assert.match(exercise, /# preflight-integration: dev\.starsector\.preflight\.launcher-v1/);
  assert.match(exercise, /X-Preflight-Integration=dev\.starsector\.preflight\.launcher-v1/);
  assert.match(exercise, /REM preflight-integration: dev\.starsector\.preflight\.launcher-v1/);
  assert.match(exercise, /CFBundleIdentifier<\/key><string>dev\.starsector\.preflight\.launcher/);
});
