import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { setReleaseVersion } from "./set-release-version.mjs";

// A POM with one <version> in it is not a POM this project has ever had. The earlier fixture had
// exactly that, which is how a version bump that cannot run on the real root POM -- ten <version>
// elements, every one of them somebody else's -- passed its own test for months. These carry the
// shapes that actually break it: a dependency block, a managed plugin, and an enforcer range.
const ROOT_POM = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <groupId>dev.starsector.preflight</groupId>
  <artifactId>starsector-preflight</artifactId>
  <version>0.1.0</version>
  <packaging>pom</packaging>

  <modules><module>preflight-core</module></modules>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.junit</groupId>
        <artifactId>junit-bom</artifactId>
        <version>5.14.0</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.15.0</version>
          <configuration>
            <requireMavenVersion><version>[3.9,)</version></requireMavenVersion>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
`;

const MODULE_POM = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>dev.starsector.preflight</groupId>
    <artifactId>starsector-preflight</artifactId>
    <version>0.1.0</version>
  </parent>

  <artifactId>preflight-core</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.ow2.asm</groupId>
      <artifactId>asm</artifactId>
      <version>9.9</version>
    </dependency>
  </dependencies>
</project>
`;

function fixture(withNotes = true) {
  const repository = mkdtempSync(join(tmpdir(), "preflight-release-version-"));
  const desktop = join(repository, "preflight-desktop");
  mkdirSync(join(desktop, "src-tauri"), { recursive: true });
  mkdirSync(join(repository, "preflight-core"), { recursive: true });
  mkdirSync(join(repository, "docs", "releases"), { recursive: true });
  writeFileSync(join(repository, "pom.xml"), ROOT_POM);
  writeFileSync(join(repository, "preflight-core", "pom.xml"), MODULE_POM);
  writeFileSync(join(desktop, "package.json"), '{"name":"desktop","version":"0.1.0"}\n');
  writeFileSync(join(desktop, "package-lock.json"), '{"version":"0.1.0","packages":{"":{"version":"0.1.0"}}}\n');
  writeFileSync(join(desktop, "src-tauri", "tauri.conf.json"), '{"version":"0.1.0"}\n');
  writeFileSync(join(desktop, "src-tauri", "Cargo.toml"), '[package]\nversion = "0.1.0"\n');
  writeFileSync(join(desktop, "src-tauri", "Cargo.lock"), '[[package]]\nname = "starsector-preflight-desktop"\nversion = "0.1.0"\n');
  if (withNotes) writeFileSync(join(repository, "docs", "releases", "0.2.0.md"), "Reviewed notes.\n");
  return repository;
}

test("updates every shipped version source after release notes exist", () => {
  const repository = fixture();
  assert.equal(7, setReleaseVersion(repository, "0.2.0").length);
  for (const path of [
    "pom.xml",
    "preflight-core/pom.xml",
    "preflight-desktop/package.json",
    "preflight-desktop/package-lock.json",
    "preflight-desktop/src-tauri/tauri.conf.json",
    "preflight-desktop/src-tauri/Cargo.toml",
    "preflight-desktop/src-tauri/Cargo.lock",
  ]) {
    assert.match(readFileSync(join(repository, path), "utf8"), /0\.2\.0/);
    assert.doesNotMatch(readFileSync(join(repository, path), "utf8"), /0\.1\.0/);
  }
});

test("refuses to mutate versions before reviewed notes exist", () => {
  const repository = fixture(false);
  assert.throws(() => setReleaseVersion(repository, "0.2.0"), /Write reviewed release notes first/);
  assert.match(readFileSync(join(repository, "pom.xml"), "utf8"), /0\.1\.0/);
  assert.throws(() => setReleaseVersion(repository, "v0.2.0"), /without a v prefix/);
});

test("a release moves only Preflight's own version, never a dependency's", () => {
  const repository = fixture();
  setReleaseVersion(repository, "0.2.0");

  const root = readFileSync(join(repository, "pom.xml"), "utf8");
  assert.match(root, /<artifactId>starsector-preflight<\/artifactId>\s*<version>0\.2\.0<\/version>/);
  for (const untouched of ["5.14.0", "3.15.0", "[3.9,)"]) {
    assert.ok(root.includes(untouched), `the release rewrote a version it doesn't own: ${untouched}`);
  }

  const module = readFileSync(join(repository, "preflight-core", "pom.xml"), "utf8");
  assert.match(module, /<parent>[\s\S]*<version>0\.2\.0<\/version>[\s\S]*<\/parent>/);
  assert.ok(module.includes("9.9"), "the release rewrote a module dependency's version");
});

test("a version that has moved out of the project's coordinates is refused, not guessed at", () => {
  const repository = fixture();
  // Nothing here can tell a reordered POM's project version from a dependency's, so the only safe
  // answer is to stop. Silently writing the wrong one produces a release that builds and is wrong.
  writeFileSync(
    join(repository, "pom.xml"),
    '<project>\n  <build><plugins><plugin><version>3.15.0</version></plugin></plugins></build>\n'
    + '  <artifactId>starsector-preflight</artifactId>\n  <version>0.1.0</version>\n</project>\n',
  );
  assert.throws(() => setReleaseVersion(repository, "0.2.0"), /artifactId before its first versioned section/);
});
