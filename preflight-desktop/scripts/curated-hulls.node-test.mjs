import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

/*
 * The six shipped hulls exist twice: as traced contours in the design page, and as the generated
 * artifact compiled into the bundle. This is the check that they are still the same contours.
 *
 * It matters because the pair one step upstream -- the page and its contact sheet -- drifted three
 * separate times while both looked fine in a thumbnail, once because an optional field fell
 * through and the Conquest quietly grew the Paragon's eight bastions. Sixty-eight unexplained
 * edges were the only symptom. A generated file that nothing regenerates is just a copy.
 *
 * Deliberately parses the page rather than shelling out to the exporter: this suite runs wherever
 * the desktop tests run, and should not need Python to be installed to tell you the app is
 * shipping stale artwork.
 */

const pageUrl = new URL("../../docs/design/hangar-light/hangar-light.html", import.meta.url);
const artifactUrl = new URL("../src/generated/curated-wireframe-hulls.json", import.meta.url);

/** Scale and rounding the exporter applies. Kept here so a change to either fails loudly. */
const SCALE = 100;
const round = (value) => Math.round(value * SCALE * 100) / 100;

/** Bracket-matched, not pattern-matched: the arrays sit among hand-written comments, and every
 *  regex written for them has broken at least once. */
function array(source, from, key) {
  const start = source.indexOf(key, from) + key.length - 1;
  let depth = 0;
  for (let at = start; at < source.length; at += 1) {
    if (source[at] === "[") depth += 1;
    else if (source[at] === "]") {
      depth -= 1;
      if (depth === 0) return JSON.parse(source.slice(start, at + 1));
    }
  }
  throw new Error(`unterminated ${key}`);
}

function pageHulls(page, ids) {
  const table = page.slice(page.indexOf("var HULLS={"));
  return ids.map((id) => {
    // Bound the slice to this hull's own entry, or an optional field falls through to the next
    // ship that has one. That is the exact bug that produced the Conquest's phantom bastions.
    const at = table.indexOf(`${id}:{`);
    assert.notEqual(at, -1, `${id} is missing from the design page`);
    const ends = ids
      .filter((other) => other !== id)
      .map((other) => table.indexOf(`\n    ${other}:{`, at))
      .filter((index) => index > at);
    const entry = table.slice(at, ends.length > 0 ? Math.min(...ends) : undefined);
    return {
      id,
      name: /name:"([^"]*)"/.exec(entry)[1],
      thickness: Number(/thick:([\d.]+)/.exec(entry)[1]),
      engineBells: Number(/bells:(\d+)/.exec(entry)[1]),
      outline: array(entry, 0, "o:["),
      holes: array(entry, 0, "holes:["),
      inner: array(entry, 0, "inner:["),
    };
  });
}

const [page, artifactText] = await Promise.all([
  readFile(pageUrl, "utf8"),
  readFile(artifactUrl, "utf8"),
]);
const artifact = JSON.parse(artifactText);

test("the shipped hull artifact still matches the design page it was traced from", () => {
  assert.equal(artifact.format, "preflight-curated-wireframe-hulls-v1");
  assert.equal(artifact.scale, SCALE);
  assert.equal(artifact.source, "docs/design/hangar-light/hangar-light.html");

  const ids = artifact.hulls.map((hull) => hull.id);
  const traced = pageHulls(page, ids);

  for (const [index, hull] of artifact.hulls.entries()) {
    const source = traced[index];
    const where = `${hull.id}: re-run docs/design/hangar-light/export-product-hulls.py`;

    assert.equal(hull.name, source.name, where);
    assert.equal(hull.curated.thickness, source.thickness, where);
    assert.equal(hull.curated.engineBells, source.engineBells, where);

    const same = (shipped, page_) => {
      assert.equal(shipped.length, page_.length, where);
      for (const [at, point] of shipped.entries()) {
        assert.equal(point.x, round(page_[at][0]), `${where} (point ${at})`);
        assert.equal(point.y, round(page_[at][1]), `${where} (point ${at})`);
      }
    };

    same(hull.bounds, source.outline);
    assert.equal(hull.curated.holes.length, source.holes.length, where);
    source.holes.forEach((loop, at) => same(hull.curated.holes[at], loop));
    assert.equal(hull.curated.inner.length, source.inner.length, where);
    source.inner.forEach(([height, loop], at) => {
      assert.equal(hull.curated.inner[at].height, height, where);
      same(hull.curated.inner[at].points, loop);
    });
  }
});

test("every shipped hull is a drawable closed shape", () => {
  assert.equal(artifact.hulls.length, 6);
  for (const hull of artifact.hulls) {
    const loops = [
      hull.bounds,
      ...hull.curated.holes,
      ...hull.curated.inner.map((tier) => tier.points),
    ];
    for (const loop of loops) {
      assert.ok(loop.length >= 3, `${hull.id} has a loop with fewer than three points`);
      for (const point of loop) {
        assert.ok(Number.isFinite(point.x) && Number.isFinite(point.y), `${hull.id} has a non-finite point`);
      }
    }
    // The page normalises every hull about the midpoint of its own extent, and the renderer takes
    // its centreline from that. A hull that arrives off-centre would tilt its own plate.
    const ys = hull.bounds.map((point) => point.y);
    const axis = (Math.min(...ys) + Math.max(...ys)) / 2;
    assert.ok(Math.abs(axis) < 0.5, `${hull.id} is not centred on its own axis (${axis})`);
  }
});
