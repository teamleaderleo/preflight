use crate::engine::canonical_game_directory;
use crate::hull_sprite_tracer::{TracedPoint, trace_installed_sprite};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fs;
use std::path::{Component, Path, PathBuf};

const MAX_HULL_FILES: usize = 1_024;
const MAX_HULL_BYTES: u64 = 1024 * 1024;
const MAX_CATALOG_BYTES: u64 = 16 * 1024 * 1024;
const MAX_BOUND_POINTS: usize = 256;
const MAX_SLOTS: usize = 256;
const MAX_LABEL_BYTES: usize = 128;
const MAX_SPRITE_PATH_BYTES: usize = 512;
const MAX_COORDINATE: f64 = 16_384.0;

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct WireframeHullCatalog {
    format: &'static str,
    hulls: Vec<WireframeHull>,
    skipped: usize,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct WireframeHull {
    id: String,
    name: String,
    hull_size: String,
    style: String,
    bounds: Vec<WireframePoint>,
    engines: Vec<WireframeEngine>,
    mounts: Vec<WireframeMount>,
    featured: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    trace: Option<WireframeTrace>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct WireframeTrace {
    holes: Vec<Vec<WireframePoint>>,
    inner: Vec<WireframeInnerContour>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
struct WireframeInnerContour {
    height: f64,
    points: Vec<WireframePoint>,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
struct WireframePoint {
    x: f64,
    y: f64,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct WireframeEngine {
    x: f64,
    y: f64,
    angle: f64,
    width: f64,
    length: f64,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct WireframeMount {
    x: f64,
    y: f64,
    angle: f64,
    size: String,
    mount: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawHull {
    hull_id: String,
    hull_name: String,
    hull_size: String,
    #[serde(default)]
    style: String,
    #[serde(default)]
    center: Option<[f64; 2]>,
    #[serde(default)]
    sprite_name: Option<String>,
    bounds: Vec<f64>,
    #[serde(default)]
    engine_slots: Vec<RawEngine>,
    #[serde(default)]
    weapon_slots: Vec<RawMount>,
}

#[derive(Deserialize)]
struct RawEngine {
    angle: f64,
    location: [f64; 2],
    width: f64,
    length: f64,
}

#[derive(Deserialize)]
struct RawMount {
    #[serde(default)]
    angle: f64,
    locations: Vec<f64>,
    size: String,
    mount: String,
}

#[tauri::command]
pub(crate) fn get_wireframe_hulls(game: String) -> Result<WireframeHullCatalog, String> {
    let game = canonical_game_directory(&game)?;
    read_wireframe_hulls(&game)
}

fn read_wireframe_hulls(game: &Path) -> Result<WireframeHullCatalog, String> {
    let directory = hull_directory(game).ok_or_else(|| {
        "This Starsector installation doesn't contain readable hull definitions.".to_string()
    })?;
    let directory_entries = fs::read_dir(&directory)
        .map_err(|error| format!("Could not inspect Starsector's hull catalog: {error}"))?;
    let mut entries = Vec::new();
    let mut skipped = 0;
    for entry in directory_entries {
        let Ok(entry) = entry else {
            skipped += 1;
            continue;
        };
        if entry.path().extension().and_then(|value| value.to_str()) != Some("ship") {
            continue;
        }
        if entries.len() >= MAX_HULL_FILES {
            return Err(
                "This installation contains too many hull definitions to display safely."
                    .to_string(),
            );
        }
        entries.push(entry);
    }
    entries.sort_by_key(|entry| entry.file_name());

    let mut hulls = Vec::new();
    let mut catalog_bytes = 0;
    for entry in entries {
        let path = entry.path();
        let Ok(metadata) = fs::symlink_metadata(&path) else {
            skipped += 1;
            continue;
        };
        if !metadata.file_type().is_file() || metadata.len() > MAX_HULL_BYTES {
            skipped += 1;
            continue;
        }
        let Ok(bytes) = fs::read(&path) else {
            skipped += 1;
            continue;
        };
        if bytes.len() as u64 > MAX_HULL_BYTES {
            skipped += 1;
            continue;
        }
        let Some(next_catalog_bytes) = bounded_catalog_bytes(catalog_bytes, bytes.len() as u64)
        else {
            return Err(
                "This installation contains too much hull data to display safely.".to_string(),
            );
        };
        catalog_bytes = next_catalog_bytes;
        let hull = serde_json::from_slice::<RawHull>(&bytes)
            .ok()
            .and_then(|raw| validate_hull(raw, &directory));
        match hull {
            Some(hull) => hulls.push(hull),
            None => skipped += 1,
        }
    }
    let mut ids = HashSet::new();
    let before_deduplication = hulls.len();
    hulls.retain(|hull| ids.insert(hull.id.clone()));
    skipped += before_deduplication - hulls.len();
    hulls.sort_by(|left, right| {
        featured_rank(&left.id)
            .cmp(&featured_rank(&right.id))
            .then_with(|| left.name.to_lowercase().cmp(&right.name.to_lowercase()))
            .then_with(|| left.id.cmp(&right.id))
    });
    Ok(WireframeHullCatalog {
        format: "preflight-wireframe-hulls-v1",
        hulls,
        skipped,
    })
}

fn bounded_catalog_bytes(current: u64, next: u64) -> Option<u64> {
    current
        .checked_add(next)
        .filter(|total| *total <= MAX_CATALOG_BYTES)
}

fn hull_directory(game: &Path) -> Option<PathBuf> {
    let game = game.canonicalize().ok()?;
    [
        game.join("data/hulls"),
        game.join("starsector-core/data/hulls"),
        game.join("Contents/Resources/Java/data/hulls"),
        game.join("Contents/Resources/Java/starsector-core/data/hulls"),
    ]
    .into_iter()
    .find_map(|candidate| {
        let canonical = candidate.canonicalize().ok()?;
        (canonical.starts_with(&game) && canonical.is_dir()).then_some(canonical)
    })
}

fn validate_hull(raw: RawHull, hull_directory: &Path) -> Option<WireframeHull> {
    if !valid_label(&raw.hull_id)
        || !valid_label(&raw.hull_name)
        || !valid_label(&raw.hull_size)
        || !valid_optional_label(&raw.style)
        || raw.bounds.len() < 6
        || raw.bounds.len() % 2 != 0
        || raw.bounds.len() / 2 > MAX_BOUND_POINTS
        || raw.engine_slots.len() > MAX_SLOTS
        || raw.weapon_slots.len() > MAX_SLOTS
    {
        return None;
    }
    let mut bounds = raw
        .bounds
        .chunks_exact(2)
        .map(|point| checked_point(point[0], point[1]))
        .collect::<Option<Vec<_>>>()?;
    let featured = featured_rank(&raw.hull_id) != usize::MAX;
    let trace = featured
        .then(|| trace_featured_sprite(&raw, hull_directory, &bounds))
        .flatten();
    if let Some((outline, _)) = trace.as_ref() {
        bounds = outline.clone();
    }
    let engines = raw
        .engine_slots
        .into_iter()
        .filter_map(|engine| {
            valid_number(engine.angle)
                .then(|| checked_point(engine.location[0], engine.location[1]))
                .flatten()
                .filter(|_| valid_positive(engine.width) && valid_positive(engine.length))
                .map(|point| WireframeEngine {
                    x: point.x,
                    y: point.y,
                    angle: engine.angle,
                    width: engine.width,
                    length: engine.length,
                })
        })
        .collect();
    let mounts = raw
        .weapon_slots
        .into_iter()
        .filter(|mount| mount.size == "LARGE" || mount.size == "MEDIUM")
        .filter(|mount| mount.mount == "TURRET" || mount.mount == "HARDPOINT")
        .filter_map(|mount| {
            let (&x, &y) = (mount.locations.first()?, mount.locations.get(1)?);
            valid_number(mount.angle)
                .then(|| checked_point(x, y))
                .flatten()
                .filter(|_| valid_label(&mount.mount))
                .map(|point| WireframeMount {
                    x: point.x,
                    y: point.y,
                    angle: mount.angle,
                    size: mount.size,
                    mount: mount.mount,
                })
        })
        .collect();
    Some(WireframeHull {
        featured,
        id: raw.hull_id,
        name: raw.hull_name,
        hull_size: raw.hull_size,
        style: raw.style,
        bounds,
        engines,
        mounts,
        trace: trace.map(|(_, trace)| trace),
    })
}

fn trace_featured_sprite(
    raw: &RawHull,
    hull_directory: &Path,
    bounds: &[WireframePoint],
) -> Option<(Vec<WireframePoint>, WireframeTrace)> {
    let center = raw.center?;
    if !center.into_iter().all(valid_number) {
        return None;
    }
    let sprite = resolve_sprite(hull_directory, raw.sprite_name.as_deref()?)?;
    let traced = trace_installed_sprite(&sprite, center).ok()?;
    let min_x = bounds
        .iter()
        .map(|point| point.x)
        .fold(f64::INFINITY, f64::min);
    let max_x = bounds
        .iter()
        .map(|point| point.x)
        .fold(f64::NEG_INFINITY, f64::max);
    let min_y = bounds
        .iter()
        .map(|point| point.y)
        .fold(f64::INFINITY, f64::min);
    let max_y = bounds
        .iter()
        .map(|point| point.y)
        .fold(f64::NEG_INFINITY, f64::max);
    let origin = WireframePoint {
        x: (min_x + max_x) / 2.0,
        y: (min_y + max_y) / 2.0,
    };
    let span = ((max_x - min_x).max(max_y - min_y)) / 2.0;
    if !valid_positive(span) {
        return None;
    }
    let scale = |point: TracedPoint| WireframePoint {
        x: origin.x + point.x * span,
        y: origin.y + point.y * span,
    };
    let outline = traced.outline.into_iter().map(scale).collect();
    let holes = traced
        .holes
        .into_iter()
        .map(|contour| contour.into_iter().map(scale).collect())
        .collect();
    let inner = traced
        .inner
        .into_iter()
        .map(|contour| WireframeInnerContour {
            height: contour.height,
            points: contour.points.into_iter().map(scale).collect(),
        })
        .collect();
    Some((outline, WireframeTrace { holes, inner }))
}

fn resolve_sprite(hull_directory: &Path, sprite_name: &str) -> Option<PathBuf> {
    if sprite_name.is_empty()
        || sprite_name.len() > MAX_SPRITE_PATH_BYTES
        || sprite_name.chars().any(char::is_control)
    {
        return None;
    }
    let relative = Path::new(sprite_name);
    if relative.is_absolute()
        || relative
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
    {
        return None;
    }
    let root = hull_directory.parent()?.parent()?.canonicalize().ok()?;
    let sprite = root.join(relative).canonicalize().ok()?;
    (sprite.starts_with(&root) && sprite.is_file()).then_some(sprite)
}

fn checked_point(x: f64, y: f64) -> Option<WireframePoint> {
    (valid_number(x) && valid_number(y)).then_some(WireframePoint { x, y })
}

fn valid_number(value: f64) -> bool {
    value.is_finite() && value.abs() <= MAX_COORDINATE
}

fn valid_positive(value: f64) -> bool {
    valid_number(value) && value > 0.0
}

fn valid_label(value: &str) -> bool {
    !value.trim().is_empty() && valid_optional_label(value)
}

fn valid_optional_label(value: &str) -> bool {
    value.len() <= MAX_LABEL_BYTES && !value.chars().any(char::is_control)
}

fn featured_rank(id: &str) -> usize {
    const FEATURED: [&str; 6] = [
        "odyssey",
        "onslaught",
        "conquest",
        "paragon",
        "astral",
        "hammerhead",
    ];
    FEATURED
        .iter()
        .position(|candidate| *candidate == id)
        .unwrap_or(usize::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cmp::Ordering;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temporary_directory(label: &str) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock")
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "preflight-hulls-{label}-{}-{nonce}",
            std::process::id()
        ));
        fs::create_dir_all(&path).expect("temporary directory");
        path
    }

    fn hull(id: &str, name: &str) -> String {
        format!(
            r#"{{
                "hullId":"{id}",
                "hullName":"{name}",
                "hullSize":"DESTROYER",
                "style":"MIDLINE",
                "bounds":[10,0,-5,8,-5,-8],
                "engineSlots":[{{"angle":180,"location":[-5,0],"width":4,"length":8}}],
                "weaponSlots":[
                    {{"angle":0,"locations":[5,0],"size":"LARGE","mount":"HARDPOINT"}},
                    {{"angle":0,"locations":[1,1],"size":"SMALL","mount":"TURRET"}}
                ]
            }}"#
        )
    }

    fn write_test_sprite(path: &Path) {
        let mut pixels = vec![0_u8; 16 * 16 * 4];
        for y in 3_usize..13 {
            for x in 2_usize..14 {
                if x.abs_diff(8) <= (y - 2) / 2 {
                    let offset = (y * 16 + x) * 4;
                    pixels[offset..offset + 4].copy_from_slice(&[150, 180, 220, 255]);
                }
            }
        }
        let file = fs::File::create(path).expect("sprite file");
        let mut encoder = png::Encoder::new(file, 16, 16);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().expect("PNG header");
        writer.write_image_data(&pixels).expect("PNG pixels");
        writer.finish().expect("PNG finish");
    }

    #[test]
    fn reads_bounded_hulls_and_puts_featured_choices_first() {
        let root = temporary_directory("catalog");
        let directory = root.join("data/hulls");
        fs::create_dir_all(&directory).expect("hull directory");
        fs::write(directory.join("z.ship"), hull("zeta", "Zeta")).expect("zeta");
        fs::write(
            directory.join("hammerhead.ship"),
            hull("hammerhead", "Hammerhead"),
        )
        .expect("hammerhead");

        let catalog = read_wireframe_hulls(&root).expect("catalog");
        assert_eq!(catalog.format, "preflight-wireframe-hulls-v1");
        assert_eq!(catalog.skipped, 0);
        assert_eq!(catalog.hulls.len(), 2);
        assert_eq!(catalog.hulls[0].id, "hammerhead");
        assert!(catalog.hulls[0].featured);
        assert_eq!(catalog.hulls[0].mounts.len(), 1);
        assert_eq!(catalog.hulls[1].id, "zeta");
        assert!(!catalog.hulls[1].featured);
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn featured_hulls_derive_richer_lines_from_the_selected_installation_only() {
        let root = temporary_directory("local-sprite");
        let hulls = root.join("data/hulls");
        let sprites = root.join("graphics/ships");
        fs::create_dir_all(&hulls).expect("hulls");
        fs::create_dir_all(&sprites).expect("sprites");
        write_test_sprite(&sprites.join("odyssey.png"));
        fs::write(
            hulls.join("odyssey.ship"),
            r#"{
                "hullId":"odyssey",
                "hullName":"Odyssey",
                "hullSize":"CAPITAL_SHIP",
                "style":"HIGH_TECH",
                "center":[8,8],
                "spriteName":"graphics/ships/odyssey.png",
                "bounds":[10,0,-5,8,-5,-8]
            }"#,
        )
        .expect("hull");

        let catalog = read_wireframe_hulls(&root).expect("catalog");
        let odyssey = &catalog.hulls[0];
        assert_eq!(odyssey.id, "odyssey");
        assert!(odyssey.bounds.len() > 3);
        assert!(odyssey.trace.is_some());
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn malformed_and_oversized_hulls_are_skipped_without_losing_the_catalog() {
        let root = temporary_directory("invalid");
        let directory = root.join("starsector-core/data/hulls");
        fs::create_dir_all(&directory).expect("hull directory");
        fs::write(directory.join("good.ship"), hull("good", "Good")).expect("good");
        fs::write(directory.join("bad.ship"), "{not json").expect("bad");
        fs::write(
            directory.join("large.ship"),
            vec![b' '; MAX_HULL_BYTES as usize + 1],
        )
        .expect("large");

        let catalog = read_wireframe_hulls(&root).expect("catalog");
        assert_eq!(catalog.hulls.len(), 1);
        assert_eq!(catalog.skipped, 2);
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn duplicate_hull_ids_keep_one_deterministic_choice() {
        let game = temporary_directory("duplicates");
        let directory = game.join("data/hulls");
        fs::create_dir_all(&directory).expect("hulls");
        fs::write(directory.join("a.ship"), hull("wolf", "First Wolf")).expect("first");
        fs::write(directory.join("b.ship"), hull("wolf", "Second Wolf")).expect("second");

        let catalog = read_wireframe_hulls(&game).expect("catalog");
        assert_eq!(catalog.hulls.len(), 1);
        assert_eq!(catalog.hulls[0].name, "First Wolf");
        assert_eq!(catalog.skipped, 1);

        fs::remove_dir_all(game).expect("cleanup");
    }

    #[cfg(unix)]
    #[test]
    fn hull_directory_cannot_escape_through_a_symlink() {
        use std::os::unix::fs::symlink;

        let root = temporary_directory("symlink-root");
        let outside = temporary_directory("symlink-outside");
        fs::create_dir_all(root.join("data")).expect("data directory");
        fs::create_dir_all(outside.join("hulls")).expect("outside hulls");
        symlink(outside.join("hulls"), root.join("data/hulls")).expect("symlink");

        assert!(hull_directory(&root).is_none());
        fs::remove_dir_all(root).expect("root cleanup");
        fs::remove_dir_all(outside).expect("outside cleanup");
    }

    #[test]
    fn coordinates_and_labels_fail_closed() {
        let raw: RawHull = serde_json::from_str(
            r#"{
                "hullId":"bad",
                "hullName":"Bad",
                "hullSize":"FRIGATE",
                "bounds":[20000,0,0,1,0,-1]
            }"#,
        )
        .expect("raw hull");
        assert!(validate_hull(raw, Path::new(".")).is_none());

        let mut oversized_style: RawHull =
            serde_json::from_str(&hull("style", "Style")).expect("raw hull with style");
        oversized_style.style = "x".repeat(MAX_LABEL_BYTES + 1);
        assert!(validate_hull(oversized_style, Path::new(".")).is_none());
    }

    #[test]
    fn launch_bay_polygons_do_not_drop_an_otherwise_valid_hull() {
        let raw: RawHull = serde_json::from_str(
            r#"{
                "hullId":"odyssey",
                "hullName":"Odyssey",
                "hullSize":"CAPITAL_SHIP",
                "bounds":[10,0,-5,8,-5,-8],
                "weaponSlots":[
                    {"angle":0,"locations":[8,4],"size":"LARGE","mount":"TURRET"},
                    {"angle":0,"locations":[8,4,6,2,4,0],"size":"LARGE","mount":"HIDDEN"}
                ]
            }"#,
        )
        .expect("hull with a launch bay polygon");

        let hull = validate_hull(raw, Path::new(".")).expect("valid hull");
        assert_eq!(hull.mounts.len(), 1);
        assert_eq!(hull.mounts[0].mount, "TURRET");
    }

    #[test]
    fn catalog_byte_budget_is_inclusive_and_overflow_safe() {
        assert_eq!(
            Some(MAX_CATALOG_BYTES),
            bounded_catalog_bytes(MAX_CATALOG_BYTES - 1, 1)
        );
        assert_eq!(None, bounded_catalog_bytes(MAX_CATALOG_BYTES, 1));
        assert_eq!(None, bounded_catalog_bytes(u64::MAX, 1));
    }

    #[test]
    fn featured_order_is_stable() {
        assert_eq!(featured_rank("odyssey"), 0);
        assert_eq!(featured_rank("hammerhead"), 5);
        assert_eq!(featured_rank("custom"), usize::MAX);
        assert_eq!(
            Ordering::Less,
            featured_rank("odyssey").cmp(&featured_rank("custom"))
        );
    }
}
