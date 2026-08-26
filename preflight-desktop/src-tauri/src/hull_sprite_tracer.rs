//! Bounded extraction of cosmetic wireframe contours from a locally installed hull sprite.
//!
//! The caller owns installation discovery and path containment. This module accepts one PNG and
//! returns only normalized points. Pixel data never leaves the process and is not retained in the
//! result.

use png::{BitDepth, ColorType, Decoder, Limits, Transformations};
use std::collections::VecDeque;
use std::fmt;
use std::fs::{self, File};
use std::io::{Cursor, Read};
use std::path::Path;

const MAX_PNG_BYTES: u64 = 16 * 1024 * 1024;
const MAX_DIMENSION: usize = 4_096;
const MAX_PIXELS: usize = 4_194_304;
const MAX_DECODED_BYTES: usize = MAX_PIXELS * 4;
const MAX_DECODER_INTERNAL_BYTES: usize = 32 * 1024 * 1024;
const MAX_RAW_CONTOUR_POINTS: usize = 262_144;
const MAX_HOLES: usize = 16;
const MAX_INNER_CONTOURS: usize = 16;
const OUTER_POINT_CAP: usize = 260;
const HOLE_POINT_CAP: usize = 90;
const INNER_POINT_CAP: usize = 70;

const ALPHA_CUT: u8 = 16;
const FOLD_ASYMMETRY_LIMIT: f64 = 0.06;
const BLUR_FRACTION: f64 = 0.085;
const MIN_COMPONENT_FRACTION: f64 = 0.022;
const OUTER_EPSILON: f64 = 1.2;
const HOLE_EPSILON: f64 = 0.8;
const INNER_EPSILON: f64 = 2.0;
const INNER_LEVELS: [(f64, f64); 2] = [(0.32, 0.42), (0.66, 0.86)];
const NEIGHBOURS: [(isize, isize); 8] = [
    (1, 0),
    (1, 1),
    (0, 1),
    (-1, 1),
    (-1, 0),
    (-1, -1),
    (0, -1),
    (1, -1),
];

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TracedPoint {
    /// Fore/aft position, normalized so the longest complete-hull axis spans `-1..=1`.
    pub x: f64,
    /// Lateral position, normalized in the same frame as `x`.
    pub y: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct TracedInnerContour {
    pub height: f64,
    pub points: Vec<TracedPoint>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct TracedSpriteHull {
    pub outline: Vec<TracedPoint>,
    pub holes: Vec<Vec<TracedPoint>>,
    pub inner: Vec<TracedInnerContour>,
    pub source_width: u32,
    pub source_height: u32,
    pub asymmetry: f64,
    pub folded: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SpriteTraceError {
    Io(String),
    InvalidPng(String),
    UnsupportedPng(&'static str),
    TooLarge(&'static str),
    InvalidCenter,
    EmptySprite,
    DegenerateOutline,
    TooComplex(&'static str),
}

impl fmt::Display for SpriteTraceError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(message) => write!(formatter, "could not read hull sprite: {message}"),
            Self::InvalidPng(message) => {
                write!(formatter, "could not decode hull sprite: {message}")
            }
            Self::UnsupportedPng(message) => {
                write!(formatter, "unsupported hull sprite: {message}")
            }
            Self::TooLarge(message) => write!(formatter, "hull sprite is too large: {message}"),
            Self::InvalidCenter => formatter.write_str("hull sprite has an invalid center"),
            Self::EmptySprite => formatter.write_str("hull sprite has no visible pixels"),
            Self::DegenerateOutline => formatter.write_str("hull sprite has no usable outline"),
            Self::TooComplex(message) => write!(formatter, "hull sprite is too complex: {message}"),
        }
    }
}

impl std::error::Error for SpriteTraceError {}

#[derive(Clone)]
struct DecodedSprite {
    width: usize,
    height: usize,
    rgba: Vec<u8>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct PixelPoint {
    x: usize,
    y: usize,
}

/// Trace a PNG after the caller has resolved it inside the selected installation.
pub fn trace_installed_sprite(
    path: &Path,
    center: [f64; 2],
) -> Result<TracedSpriteHull, SpriteTraceError> {
    let metadata =
        fs::symlink_metadata(path).map_err(|error| SpriteTraceError::Io(error.to_string()))?;
    if !metadata.file_type().is_file() {
        return Err(SpriteTraceError::UnsupportedPng(
            "the sprite path is not a regular file",
        ));
    }
    if metadata.len() > MAX_PNG_BYTES {
        return Err(SpriteTraceError::TooLarge("encoded file limit exceeded"));
    }
    let file = File::open(path).map_err(|error| SpriteTraceError::Io(error.to_string()))?;
    if !file
        .metadata()
        .map_err(|error| SpriteTraceError::Io(error.to_string()))?
        .file_type()
        .is_file()
    {
        return Err(SpriteTraceError::UnsupportedPng(
            "the opened sprite is not a regular file",
        ));
    }
    let mut bytes = Vec::with_capacity(metadata.len().min(MAX_PNG_BYTES + 1) as usize);
    file.take(MAX_PNG_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| SpriteTraceError::Io(error.to_string()))?;
    if bytes.len() as u64 > MAX_PNG_BYTES {
        return Err(SpriteTraceError::TooLarge("encoded file limit exceeded"));
    }
    trace_png_bytes(&bytes, center)
}

/// Byte-oriented entry point used by tests and by callers that already performed a bounded read.
pub fn trace_png_bytes(
    bytes: &[u8],
    center: [f64; 2],
) -> Result<TracedSpriteHull, SpriteTraceError> {
    if bytes.len() as u64 > MAX_PNG_BYTES {
        return Err(SpriteTraceError::TooLarge("encoded file limit exceeded"));
    }
    let sprite = decode_png(bytes)?;
    validate_center(center, sprite.width, sprite.height)?;
    trace_decoded(sprite, center)
}

fn decode_png(bytes: &[u8]) -> Result<DecodedSprite, SpriteTraceError> {
    let limits = Limits {
        bytes: MAX_DECODER_INTERNAL_BYTES,
    };
    let mut decoder = Decoder::new_with_limits(Cursor::new(bytes), limits);
    decoder.set_ignore_text_chunk(true);
    decoder.set_transformations(Transformations::normalize_to_color8());
    let mut reader = decoder
        .read_info()
        .map_err(|error| SpriteTraceError::InvalidPng(error.to_string()))?;
    if reader.info().animation_control.is_some() {
        return Err(SpriteTraceError::UnsupportedPng("animated PNG"));
    }
    let width = usize::try_from(reader.info().width)
        .map_err(|_| SpriteTraceError::TooLarge("width is not addressable"))?;
    let height = usize::try_from(reader.info().height)
        .map_err(|_| SpriteTraceError::TooLarge("height is not addressable"))?;
    validate_dimensions(width, height)?;
    let output_size = reader
        .output_buffer_size()
        .ok_or(SpriteTraceError::TooLarge("decoded buffer overflows"))?;
    if output_size > MAX_DECODED_BYTES {
        return Err(SpriteTraceError::TooLarge("decoded pixel limit exceeded"));
    }
    let mut output = vec![0; output_size];
    let frame = reader
        .next_frame(&mut output)
        .map_err(|error| SpriteTraceError::InvalidPng(error.to_string()))?;
    if frame.bit_depth != BitDepth::Eight {
        return Err(SpriteTraceError::UnsupportedPng("non-8-bit decoded pixels"));
    }
    let used = frame.buffer_size();
    output.truncate(used);
    let rgba = match frame.color_type {
        ColorType::Rgba => output,
        ColorType::GrayscaleAlpha => output
            .as_chunks::<2>()
            .0
            .iter()
            .flat_map(|pixel| [pixel[0], pixel[0], pixel[0], pixel[1]])
            .collect(),
        _ => {
            return Err(SpriteTraceError::UnsupportedPng(
                "the sprite does not contain transparency",
            ));
        }
    };
    let expected = width
        .checked_mul(height)
        .and_then(|pixels| pixels.checked_mul(4))
        .ok_or(SpriteTraceError::TooLarge("decoded buffer overflows"))?;
    if rgba.len() != expected {
        return Err(SpriteTraceError::InvalidPng(
            "decoded pixel count does not match the header".to_string(),
        ));
    }
    Ok(DecodedSprite {
        width,
        height,
        rgba,
    })
}

fn validate_dimensions(width: usize, height: usize) -> Result<(), SpriteTraceError> {
    if width == 0 || height == 0 {
        return Err(SpriteTraceError::InvalidPng("zero-sized image".to_string()));
    }
    if width > MAX_DIMENSION || height > MAX_DIMENSION {
        return Err(SpriteTraceError::TooLarge("dimension limit exceeded"));
    }
    let pixels = width
        .checked_mul(height)
        .ok_or(SpriteTraceError::TooLarge("pixel count overflows"))?;
    if pixels > MAX_PIXELS {
        return Err(SpriteTraceError::TooLarge("pixel count limit exceeded"));
    }
    Ok(())
}

fn validate_center(center: [f64; 2], width: usize, height: usize) -> Result<(), SpriteTraceError> {
    if center[0].is_finite()
        && center[1].is_finite()
        && center[0] >= 0.0
        && center[1] >= 0.0
        && center[0] <= width as f64
        && center[1] <= height as f64
    {
        Ok(())
    } else {
        Err(SpriteTraceError::InvalidCenter)
    }
}

fn trace_decoded(
    sprite: DecodedSprite,
    center: [f64; 2],
) -> Result<TracedSpriteHull, SpriteTraceError> {
    let DecodedSprite {
        width,
        height,
        rgba,
    } = sprite;
    let mut mask: Vec<bool> = rgba
        .as_chunks::<4>()
        .0
        .iter()
        .map(|pixel| pixel[3] > ALPHA_CUT)
        .collect();
    if !mask.iter().any(|visible| *visible) {
        return Err(SpriteTraceError::EmptySprite);
    }
    let axis = symmetry_axis(&mask, width, height, center[0]);
    let asymmetry = asymmetry(&mask, width, height, axis);
    let folded = asymmetry <= FOLD_ASYMMETRY_LIMIT;
    if folded {
        fold(&mut mask, width, height, axis);
    }

    // Folding can add a row-major-earlier pixel. Recompute the seed so contour ordering stays
    // deterministic and matches the reference extraction algorithm.
    let start = mask
        .iter()
        .position(|visible| *visible)
        .ok_or(SpriteTraceError::EmptySprite)?;
    let raw_outline = march(&mask, width, height, pixel_point(start, width), true)?;
    let outline = simplify_to_budget(&raw_outline, OUTER_EPSILON, OUTER_POINT_CAP);
    if outline.len() < 3 {
        return Err(SpriteTraceError::DegenerateOutline);
    }

    let raw_holes = find_holes(&mask, width, height)?;
    if raw_holes.len() > MAX_HOLES {
        return Err(SpriteTraceError::TooComplex("too many interior voids"));
    }
    let holes = raw_holes
        .iter()
        .map(|contour| simplify_to_budget(contour, HOLE_EPSILON, HOLE_POINT_CAP))
        .filter(|contour| contour.len() >= 3)
        .collect::<Vec<_>>();

    let mut blurred = blurred_luminance(&rgba, &mask, width, height);
    if folded {
        fold_brightness(&mut blurred, width, height, axis);
    }
    let levels = luminance_levels(&blurred, &mask)?;
    let minimum_component = ((width * height) as f64 * MIN_COMPONENT_FRACTION).ceil() as usize;
    let mut inner = Vec::new();
    for ((cut, lift), level) in INNER_LEVELS.into_iter().zip(levels) {
        let threshold = level.0 + (level.1 - level.0) * cut;
        let bright: Vec<bool> = mask
            .iter()
            .zip(&blurred)
            .map(|(visible, value)| *visible && *value >= threshold)
            .collect();
        for contour in component_contours(&bright, width, height, minimum_component)? {
            if inner.len() >= MAX_INNER_CONTOURS {
                return Err(SpriteTraceError::TooComplex("too many luminance contours"));
            }
            let points = simplify_to_budget(&contour, INNER_EPSILON, INNER_POINT_CAP);
            if points.len() >= 3 {
                inner.push((lift, points));
            }
        }
    }

    normalize_geometry(
        outline,
        holes,
        inner,
        u32::try_from(width).expect("validated PNG width fits u32"),
        u32::try_from(height).expect("validated PNG height fits u32"),
        asymmetry,
        folded,
    )
}

fn pixel_point(index: usize, width: usize) -> PixelPoint {
    PixelPoint {
        x: index % width,
        y: index / width,
    }
}

fn march(
    mask: &[bool],
    width: usize,
    height: usize,
    seed: PixelPoint,
    wanted: bool,
) -> Result<Vec<PixelPoint>, SpriteTraceError> {
    let mut contour = vec![seed];
    let mut current = seed;
    let mut back = 4usize;
    let maximum_steps = width
        .checked_mul(height)
        .and_then(|pixels| pixels.checked_mul(4))
        .ok_or(SpriteTraceError::TooLarge("contour budget overflows"))?;
    for _ in 0..maximum_steps {
        let mut next = None;
        for offset in 0..8 {
            let direction = (back + 1 + offset) % 8;
            let (dx, dy) = NEIGHBOURS[direction];
            let Some(x) = current.x.checked_add_signed(dx) else {
                continue;
            };
            let Some(y) = current.y.checked_add_signed(dy) else {
                continue;
            };
            if x < width && y < height && mask[y * width + x] == wanted {
                next = Some((direction, PixelPoint { x, y }));
                break;
            }
        }
        let Some((direction, point)) = next else {
            break;
        };
        back = (direction + 5) % 8;
        current = point;
        contour.push(point);
        if contour.len() > MAX_RAW_CONTOUR_POINTS {
            return Err(SpriteTraceError::TooComplex("contour point limit exceeded"));
        }
        if contour.len() > 3 && point == seed {
            contour.pop();
            return Ok(contour);
        }
    }
    Ok(contour)
}

fn simplify_to_budget(points: &[PixelPoint], initial_epsilon: f64, cap: usize) -> Vec<PixelPoint> {
    let mut epsilon = initial_epsilon;
    let mut simplified = simplify(points, epsilon);
    for _ in 0..64 {
        if simplified.len() <= cap {
            return simplified;
        }
        epsilon *= 1.25;
        simplified = simplify(points, epsilon);
    }
    simplified
}

fn simplify(points: &[PixelPoint], epsilon: f64) -> Vec<PixelPoint> {
    if points.len() < 3 {
        return points.to_vec();
    }
    let mut keep = vec![false; points.len()];
    keep[0] = true;
    keep[points.len() - 1] = true;
    let mut stack = vec![(0usize, points.len() - 1)];
    while let Some((first, last)) = stack.pop() {
        if last <= first + 1 {
            continue;
        }
        // Match the design tracer's ship-space axes before measuring. The operation is a rigid
        // transform, but spelling it out keeps points that land exactly on the configured
        // tolerance on the same side of the floating-point comparison as the reference tracer.
        let a = (-(points[first].y as f64), -(points[first].x as f64));
        let b = (-(points[last].y as f64), -(points[last].x as f64));
        let dx = b.0 - a.0;
        let dy = b.1 - a.1;
        let norm = dx.hypot(dy).max(1.0);
        let mut worst = -1.0;
        let mut at = first + 1;
        for (offset, point) in points[first + 1..last].iter().enumerate() {
            let point = (-(point.y as f64), -(point.x as f64));
            let distance = (dy * point.0 - dx * point.1 + b.0 * a.1 - b.1 * a.0).abs() / norm;
            if distance > worst {
                worst = distance;
                at = first + 1 + offset;
            }
        }
        if worst > epsilon {
            keep[at] = true;
            stack.push((at, last));
            stack.push((first, at));
        }
    }
    points
        .iter()
        .zip(keep)
        .filter_map(|(point, keep)| keep.then_some(*point))
        .collect()
}

fn symmetry_axis(mask: &[bool], width: usize, height: usize, center_x: f64) -> usize {
    let base = center_x.round() as isize;
    let mut best = (
        0usize,
        base.clamp(0, width.saturating_sub(1) as isize) as usize,
    );
    for candidate in base - 6..=base + 6 {
        if candidate < 0 || candidate >= width as isize {
            continue;
        }
        let mut agreement = 0usize;
        for y in (0..height).step_by(3) {
            for x in (0..width).step_by(2) {
                let reflected = 2 * candidate - x as isize;
                if reflected >= 0
                    && reflected < width as isize
                    && mask[y * width + x] == mask[y * width + reflected as usize]
                {
                    agreement += 1;
                }
            }
        }
        if agreement > best.0 {
            best = (agreement, candidate as usize);
        }
    }
    best.1
}

fn asymmetry(mask: &[bool], width: usize, height: usize, axis: usize) -> f64 {
    let mut visible = 0usize;
    let mut mismatched = 0usize;
    for y in 0..height {
        for x in 0..width {
            let value = mask[y * width + x];
            visible += usize::from(value);
            let reflected = 2 * axis as isize - x as isize;
            if reflected >= 0
                && reflected < width as isize
                && value != mask[y * width + reflected as usize]
            {
                mismatched += 1;
            }
        }
    }
    if visible == 0 {
        0.0
    } else {
        mismatched as f64 / visible as f64
    }
}

fn fold(mask: &mut [bool], width: usize, height: usize, axis: usize) {
    for y in 0..height {
        for x in 0..width {
            let reflected = 2 * axis as isize - x as isize;
            if reflected >= 0 && reflected < width as isize && mask[y * width + reflected as usize]
            {
                mask[y * width + x] = true;
            }
        }
    }
}

/// The luminance twin of [`fold`]: where the mask takes the union, brightness takes the brighter.
///
/// Folding only the alpha left the two channels disagreeing about the same ship. The silhouette
/// came back symmetric to a fraction of a percent and the lit contours did not, because a raised
/// block and its mirror are shaded differently and the darker of the pair can fall under the
/// minimum component size on its own. What reached the renderer was a hull with a flight deck down
/// one flank and nothing down the other. Reading the brighter of each pair applies the decision
/// this hull has already been given -- that it is symmetric -- to the channel that was missing it.
fn fold_brightness(values: &mut [f64], width: usize, height: usize, axis: usize) {
    // In place is safe: the pass converges on the pairwise maximum, so a cell that has already
    // taken its mirror's value still yields that same maximum when its mirror reads it back.
    for y in 0..height {
        for x in 0..width {
            let reflected = 2 * axis as isize - x as isize;
            if reflected >= 0 && reflected < width as isize {
                let mirrored = values[y * width + reflected as usize];
                let at = &mut values[y * width + x];
                if mirrored > *at {
                    *at = mirrored;
                }
            }
        }
    }
}

fn find_holes(
    mask: &[bool],
    width: usize,
    height: usize,
) -> Result<Vec<Vec<PixelPoint>>, SpriteTraceError> {
    let mut outside = vec![false; mask.len()];
    let mut queue = VecDeque::new();
    for x in 0..width {
        enqueue_background(mask, &mut outside, &mut queue, x, 0, width);
        enqueue_background(mask, &mut outside, &mut queue, x, height - 1, width);
    }
    for y in 0..height {
        enqueue_background(mask, &mut outside, &mut queue, 0, y, width);
        enqueue_background(mask, &mut outside, &mut queue, width - 1, y, width);
    }
    flood(mask, &mut outside, &mut queue, width, height, false);

    let mut visited = outside;
    let mut holes = Vec::new();
    for seed in 0..mask.len() {
        if mask[seed] || visited[seed] {
            continue;
        }
        let (count, first) = component(mask, &mut visited, seed, width, height, false);
        if count >= 60 {
            holes.push(march(
                mask,
                width,
                height,
                pixel_point(first, width),
                false,
            )?);
            if holes.len() > MAX_HOLES {
                return Err(SpriteTraceError::TooComplex("too many interior voids"));
            }
        }
    }
    Ok(holes)
}

fn enqueue_background(
    mask: &[bool],
    visited: &mut [bool],
    queue: &mut VecDeque<usize>,
    x: usize,
    y: usize,
    width: usize,
) {
    let at = y * width + x;
    if !mask[at] && !visited[at] {
        visited[at] = true;
        queue.push_back(at);
    }
}

fn flood(
    values: &[bool],
    visited: &mut [bool],
    queue: &mut VecDeque<usize>,
    width: usize,
    height: usize,
    wanted: bool,
) {
    while let Some(at) = queue.pop_front() {
        let point = pixel_point(at, width);
        for (dx, dy) in [(1isize, 0isize), (-1, 0), (0, 1), (0, -1)] {
            let Some(x) = point.x.checked_add_signed(dx) else {
                continue;
            };
            let Some(y) = point.y.checked_add_signed(dy) else {
                continue;
            };
            if x >= width || y >= height {
                continue;
            }
            let next = y * width + x;
            if values[next] == wanted && !visited[next] {
                visited[next] = true;
                queue.push_back(next);
            }
        }
    }
}

fn component(
    values: &[bool],
    visited: &mut [bool],
    seed: usize,
    width: usize,
    height: usize,
    wanted: bool,
) -> (usize, usize) {
    let mut queue = VecDeque::from([seed]);
    visited[seed] = true;
    let mut count = 0usize;
    let mut first = seed;
    while let Some(at) = queue.pop_front() {
        count += 1;
        let point = pixel_point(at, width);
        if (point.y, point.x) < (first / width, first % width) {
            first = at;
        }
        for (dx, dy) in [(1isize, 0isize), (-1, 0), (0, 1), (0, -1)] {
            let Some(x) = point.x.checked_add_signed(dx) else {
                continue;
            };
            let Some(y) = point.y.checked_add_signed(dy) else {
                continue;
            };
            if x >= width || y >= height {
                continue;
            }
            let next = y * width + x;
            if values[next] == wanted && !visited[next] {
                visited[next] = true;
                queue.push_back(next);
            }
        }
    }
    (count, first)
}

fn blurred_luminance(rgba: &[u8], mask: &[bool], width: usize, height: usize) -> Vec<f64> {
    let luminance = rgba
        .as_chunks::<4>()
        .0
        .iter()
        .zip(mask)
        .map(|(pixel, visible)| {
            if *visible {
                (0.299 * f64::from(pixel[0])
                    + 0.587 * f64::from(pixel[1])
                    + 0.114 * f64::from(pixel[2]))
                    / 255.0
            } else {
                0.0
            }
        })
        .collect::<Vec<_>>();
    let radius = ((width as f64 * BLUR_FRACTION) as usize).max(2);
    let horizontal = blur_sweep(&luminance, mask, width, height, radius, true);
    blur_sweep(&horizontal, mask, width, height, radius, false)
}

fn blur_sweep(
    source: &[f64],
    mask: &[bool],
    width: usize,
    height: usize,
    radius: usize,
    horizontal: bool,
) -> Vec<f64> {
    let mut output = vec![0.0; source.len()];
    let (outer, inner) = if horizontal {
        (height, width)
    } else {
        (width, height)
    };
    for cross in 0..outer {
        let mut sum = 0.0f64;
        let mut count = 0usize;
        for along in 0..inner.saturating_add(radius) {
            if along < inner {
                let at = if horizontal {
                    cross * width + along
                } else {
                    along * width + cross
                };
                if mask[at] {
                    sum += source[at];
                    count += 1;
                }
            }
            if let Some(remove) = along.checked_sub(2 * radius + 1)
                && remove < inner
            {
                let at = if horizontal {
                    cross * width + remove
                } else {
                    remove * width + cross
                };
                if mask[at] {
                    sum -= source[at];
                    count -= 1;
                }
            }
            if let Some(middle) = along.checked_sub(radius)
                && middle < inner
            {
                let at = if horizontal {
                    cross * width + middle
                } else {
                    middle * width + cross
                };
                output[at] = if count == 0 { 0.0 } else { sum / count as f64 };
            }
        }
    }
    output
}

fn luminance_levels(luminance: &[f64], mask: &[bool]) -> Result<[(f64, f64); 2], SpriteTraceError> {
    let mut values = luminance
        .iter()
        .zip(mask)
        .filter_map(|(value, visible)| visible.then_some(*value))
        .collect::<Vec<_>>();
    if values.is_empty() {
        return Err(SpriteTraceError::EmptySprite);
    }
    values.sort_by(f64::total_cmp);
    let low = values[values.len() / 50];
    // Python's reference expression uses floor division on a negative index, so this is a
    // ceiling division rather than `values.len() / 50`.
    let upper_trim = values.len().div_ceil(50);
    let high = values[values.len().saturating_sub(upper_trim + 1)];
    Ok([(low, high), (low, high)])
}

fn component_contours(
    mask: &[bool],
    width: usize,
    height: usize,
    minimum_size: usize,
) -> Result<Vec<Vec<PixelPoint>>, SpriteTraceError> {
    let mut visited = vec![false; mask.len()];
    let mut contours = Vec::new();
    for seed in 0..mask.len() {
        if !mask[seed] || visited[seed] {
            continue;
        }
        let (count, first) = component(mask, &mut visited, seed, width, height, true);
        if count >= minimum_size {
            contours.push(march(mask, width, height, pixel_point(first, width), true)?);
            if contours.len() > MAX_INNER_CONTOURS {
                return Err(SpriteTraceError::TooComplex("too many luminance contours"));
            }
        }
    }
    Ok(contours)
}

fn normalize_geometry(
    outline: Vec<PixelPoint>,
    holes: Vec<Vec<PixelPoint>>,
    inner: Vec<(f64, Vec<PixelPoint>)>,
    source_width: u32,
    source_height: u32,
    asymmetry: f64,
    folded: bool,
) -> Result<TracedSpriteHull, SpriteTraceError> {
    let mut min_forward = f64::INFINITY;
    let mut max_forward = f64::NEG_INFINITY;
    let mut min_lateral = f64::INFINITY;
    let mut max_lateral = f64::NEG_INFINITY;
    for point in outline.iter().chain(holes.iter().flatten()) {
        let forward = -(point.y as f64);
        let lateral = -(point.x as f64);
        min_forward = min_forward.min(forward);
        max_forward = max_forward.max(forward);
        min_lateral = min_lateral.min(lateral);
        max_lateral = max_lateral.max(lateral);
    }
    let center_forward = (min_forward + max_forward) / 2.0;
    let center_lateral = (min_lateral + max_lateral) / 2.0;
    let span = ((max_forward - min_forward).max(max_lateral - min_lateral)) / 2.0;
    if !span.is_finite() || span <= 0.0 {
        return Err(SpriteTraceError::DegenerateOutline);
    }
    let normalize = |point: PixelPoint| TracedPoint {
        x: round_milli((-(point.y as f64) - center_forward) / span),
        y: round_milli((-(point.x as f64) - center_lateral) / span),
    };
    Ok(TracedSpriteHull {
        outline: outline.into_iter().map(normalize).collect(),
        holes: holes
            .into_iter()
            .map(|contour| contour.into_iter().map(normalize).collect())
            .collect(),
        inner: inner
            .into_iter()
            .map(|(height, points)| TracedInnerContour {
                height,
                points: points.into_iter().map(normalize).collect(),
            })
            .collect(),
        source_width,
        source_height,
        asymmetry,
        folded,
    })
}

fn round_milli(value: f64) -> f64 {
    (value * 1_000.0).round() / 1_000.0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rgba_png(width: u32, height: u32, pixels: &[u8]) -> Vec<u8> {
        let mut encoded = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut encoded, width, height);
            encoder.set_color(ColorType::Rgba);
            encoder.set_depth(BitDepth::Eight);
            let mut writer = encoder.write_header().expect("PNG header");
            writer.write_image_data(pixels).expect("PNG pixels");
        }
        encoded
    }

    fn sprite_with_hole() -> Vec<u8> {
        let width = 64usize;
        let height = 64usize;
        let mut pixels = vec![0u8; width * height * 4];
        for y in 8..56 {
            for x in 8..56 {
                let at = (y * width + x) * 4;
                let bright = (16..32).contains(&x) && (14..34).contains(&y);
                pixels[at..at + 4].copy_from_slice(if bright {
                    &[240, 220, 180, 255]
                } else {
                    &[80, 70, 60, 255]
                });
            }
        }
        for y in 24..40 {
            for x in 27..37 {
                pixels[(y * width + x) * 4 + 3] = 0;
            }
        }
        rgba_png(width as u32, height as u32, &pixels)
    }

    #[test]
    fn extracts_a_normalized_outline_hole_and_luminance_mass() {
        let trace = trace_png_bytes(&sprite_with_hole(), [32.0, 32.0]).expect("trace");

        assert!(trace.folded);
        assert_eq!((trace.source_width, trace.source_height), (64, 64));
        assert!(trace.outline.len() >= 3);
        assert_eq!(trace.holes.len(), 1);
        assert!(!trace.inner.is_empty());
        let coordinates = trace
            .outline
            .iter()
            .flat_map(|point| [point.x, point.y])
            .collect::<Vec<_>>();
        assert!(coordinates.iter().all(|value| (-1.0..=1.0).contains(value)));
        assert!(coordinates.iter().any(|value| *value == -1.0));
        assert!(coordinates.contains(&1.0));
    }

    #[test]
    fn a_symmetric_hull_keeps_both_of_a_shaded_pair() {
        // A symmetric silhouette with a matched pair of raised blocks, lit the way a sprite is:
        // the port one bright, the starboard one in shadow. Folding only the alpha finds the lit
        // block and loses its twin under the minimum component size, and the ship reaches the
        // renderer with a flight deck down one flank.
        let width = 96usize;
        let height = 96usize;
        let mut pixels = vec![0u8; width * height * 4];
        for y in 8..88 {
            for x in 8..88 {
                let at = (y * width + x) * 4;
                let port = (16..40).contains(&x) && (24..72).contains(&y);
                let starboard = (56..80).contains(&x) && (24..72).contains(&y);
                pixels[at..at + 4].copy_from_slice(match (port, starboard) {
                    (true, _) => &[248, 236, 208, 255],
                    (_, true) => &[150, 142, 124, 255],
                    _ => &[64, 60, 54, 255],
                });
            }
        }
        let trace = trace_png_bytes(
            &rgba_png(width as u32, height as u32, &pixels),
            [48.0, 48.0],
        )
        .expect("trace");

        assert!(trace.folded);
        let flanks = trace
            .inner
            .iter()
            .map(|contour| {
                let total: f64 = contour.points.iter().map(|point| point.y).sum();
                total / contour.points.len() as f64
            })
            .filter(|lateral| lateral.abs() > 0.1)
            .collect::<Vec<_>>();
        // Both luminance bands can fire on the same block, so the count is not the assertion --
        // the balance is. Every flank contour has to answer one on the other side.
        let port = flanks.iter().filter(|lateral| **lateral > 0.0).count();
        let starboard = flanks.iter().filter(|lateral| **lateral < 0.0).count();
        assert!(port > 0, "expected flank blocks at all, got {flanks:?}");
        assert_eq!(
            port, starboard,
            "expected one block per side, got {flanks:?}"
        );
    }

    #[test]
    fn preserves_deliberate_asymmetry() {
        let width = 64usize;
        let height = 64usize;
        let mut pixels = vec![0u8; width * height * 4];
        for y in 10..54 {
            for x in 8..30 {
                pixels[(y * width + x) * 4..(y * width + x) * 4 + 4]
                    .copy_from_slice(&[180, 180, 180, 255]);
            }
        }
        let encoded = rgba_png(width as u32, height as u32, &pixels);
        let trace = trace_png_bytes(&encoded, [32.0, 32.0]).expect("trace");

        assert!(!trace.folded);
        assert!(trace.asymmetry > FOLD_ASYMMETRY_LIMIT);
    }

    #[test]
    fn rejects_non_alpha_malformed_and_invalid_center_inputs() {
        assert!(matches!(
            trace_png_bytes(b"not a PNG", [1.0, 1.0]),
            Err(SpriteTraceError::InvalidPng(_))
        ));
        assert!(matches!(
            trace_png_bytes(&sprite_with_hole(), [f64::NAN, 1.0]),
            Err(SpriteTraceError::InvalidCenter)
        ));

        let mut encoded = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut encoded, 4, 4);
            encoder.set_color(ColorType::Rgb);
            encoder.set_depth(BitDepth::Eight);
            let mut writer = encoder.write_header().expect("PNG header");
            writer
                .write_image_data(&[255; 4 * 4 * 3])
                .expect("PNG pixels");
        }
        assert!(matches!(
            trace_png_bytes(&encoded, [2.0, 2.0]),
            Err(SpriteTraceError::UnsupportedPng(_))
        ));

        let transparent = rgba_png(4, 4, &[0; 4 * 4 * 4]);
        assert_eq!(
            trace_png_bytes(&transparent, [2.0, 2.0]),
            Err(SpriteTraceError::EmptySprite)
        );

        let mut single_pixel = [0; 4 * 4 * 4];
        single_pixel[(2 * 4 + 2) * 4..(2 * 4 + 2) * 4 + 4].copy_from_slice(&[255, 255, 255, 255]);
        assert_eq!(
            trace_png_bytes(&rgba_png(4, 4, &single_pixel), [2.0, 2.0]),
            Err(SpriteTraceError::DegenerateOutline)
        );
    }

    #[test]
    fn hard_limits_are_overflow_safe() {
        assert!(matches!(
            validate_dimensions(MAX_DIMENSION + 1, 1),
            Err(SpriteTraceError::TooLarge(_))
        ));
        assert!(matches!(
            validate_dimensions(MAX_PIXELS, 2),
            Err(SpriteTraceError::TooLarge(_))
        ));
        assert!(matches!(
            trace_png_bytes(&vec![0; MAX_PNG_BYTES as usize + 1], [0.0, 0.0]),
            Err(SpriteTraceError::TooLarge(_))
        ));
    }

    #[test]
    fn iterative_simplification_is_deterministic_and_bounded() {
        let points = (0..1_000)
            .map(|value| PixelPoint {
                x: value,
                y: value % 7,
            })
            .collect::<Vec<_>>();
        let first = simplify_to_budget(&points, 0.01, 40);
        let second = simplify_to_budget(&points, 0.01, 40);
        assert_eq!(first, second);
        assert!(first.len() <= 40);
    }
}
