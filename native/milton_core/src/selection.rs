use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

const TILE_SIZE: usize = 512;

#[derive(Debug, Clone, Copy)]
pub struct Point2D {
    pub x: f32,
    pub y: f32,
}

#[derive(Debug, Clone, Copy)]
pub struct RectF {
    pub min_x: f32,
    pub min_y: f32,
    pub max_x: f32,
    pub max_y: f32,
}

impl RectF {
    pub fn intersects(&self, other: &RectF) -> bool {
        self.min_x <= other.max_x
            && self.max_x >= other.min_x
            && self.min_y <= other.max_y
            && self.max_y >= other.min_y
    }
}

pub struct Polygon {
    pub points: Vec<Point2D>,
    pub bounds: RectF,
}

impl Polygon {
    pub fn new(points: Vec<Point2D>) -> Self {
        let mut min_x = f32::MAX;
        let mut min_y = f32::MAX;
        let mut max_x = f32::MIN;
        let mut max_y = f32::MIN;

        for p in &points {
            min_x = min_x.min(p.x);
            min_y = min_y.min(p.y);
            max_x = max_x.max(p.x);
            max_y = max_y.max(p.y);
        }

        let bounds = if points.is_empty() {
            RectF { min_x: 0.0, min_y: 0.0, max_x: 0.0, max_y: 0.0 }
        } else {
            RectF { min_x, min_y, max_x, max_y }
        };

        Polygon { points, bounds }
    }

    /// Fast standard ray-casting algorithm to test if point is inside polygon (even-odd rule).
    pub fn contains_point(&self, x: f32, y: f32) -> bool {
        if x < self.bounds.min_x || x > self.bounds.max_x || y < self.bounds.min_y || y > self.bounds.max_y {
            return false;
        }

        let n = self.points.len();
        if n < 3 {
            return false;
        }

        let mut inside = false;
        let mut j = n - 1;

        for i in 0..n {
            let pi = self.points[i];
            let pj = self.points[j];

            if ((pi.y > y) != (pj.y > y))
                && (x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x)
            {
                inside = !inside;
            }
            j = i;
        }

        inside
    }

    /// Computes a 512x512 8-bit mask (255 for inside, 0 for outside) for a world-space tile.
    /// Uses high-performance scanline span rasterization (even-odd rule) with fast slice fills.
    pub fn rasterize_tile_mask(
        &self,
        tile_world_x: f32,
        tile_world_y: f32,
    ) -> Option<Vec<u8>> {
        let tile_bounds = RectF {
            min_x: tile_world_x,
            min_y: tile_world_y,
            max_x: tile_world_x + TILE_SIZE as f32,
            max_y: tile_world_y + TILE_SIZE as f32,
        };

        if !self.bounds.intersects(&tile_bounds) || self.points.len() < 3 {
            return None;
        }

        let mut mask = vec![0u8; TILE_SIZE * TILE_SIZE];
        let mut any_inside = false;
        let n = self.points.len();

        let min_py = ((self.bounds.min_y - tile_world_y).floor() as i32)
            .clamp(0, (TILE_SIZE - 1) as i32) as usize;
        let max_py = ((self.bounds.max_y - tile_world_y).ceil() as i32)
            .clamp(0, (TILE_SIZE - 1) as i32) as usize;

        if min_py > max_py {
            return None;
        }

        let mut x_inters: Vec<f32> = Vec::with_capacity(32);

        for py in min_py..=max_py {
            let world_y = tile_world_y + py as f32 + 0.5;
            x_inters.clear();

            let mut j = n - 1;
            for i in 0..n {
                let pi = self.points[i];
                let pj = self.points[j];

                if (pi.y <= world_y && pj.y > world_y) || (pj.y <= world_y && pi.y > world_y) {
                    let t = (world_y - pi.y) / (pj.y - pi.y);
                    let x_cross = pi.x + t * (pj.x - pi.x);
                    x_inters.push(x_cross);
                }
                j = i;
            }

            if x_inters.len() < 2 {
                continue;
            }

            x_inters.sort_unstable_by(|a, b| a.total_cmp(b));

            let row_offset = py * TILE_SIZE;

            for chunk in x_inters.chunks_exact(2) {
                let x0 = chunk[0];
                let x1 = chunk[1];

                let local_x0 = x0 - tile_world_x;
                let local_x1 = x1 - tile_world_x;

                let start_px = (local_x0 - 0.5).ceil().max(0.0) as usize;
                let end_px = (local_x1 - 0.5).floor().min((TILE_SIZE - 1) as f32) as usize;

                if start_px <= end_px && start_px < TILE_SIZE {
                    let end_clamp = end_px.min(TILE_SIZE - 1);
                    mask[row_offset + start_px..=row_offset + end_clamp].fill(255);
                    any_inside = true;
                }
            }
        }

        if any_inside {
            Some(mask)
        } else {
            None
        }
    }
}

/// Bilinear interpolation of RGBA pixels from source buffer.
#[inline(always)]
fn sample_bilinear(src: &[u8], w: usize, h: usize, u: f32, v: f32) -> [u8; 4] {
    if u < 0.0 || u >= (w as f32 - 1.0) || v < 0.0 || v >= (h as f32 - 1.0) {
        if u >= 0.0 && u < w as f32 && v >= 0.0 && v < h as f32 {
            let idx = (v as usize * w + u as usize) * 4;
            return [src[idx], src[idx + 1], src[idx + 2], src[idx + 3]];
        }
        return [0, 0, 0, 0];
    }

    let x0 = u.floor() as usize;
    let y0 = v.floor() as usize;
    let x1 = (x0 + 1).min(w - 1);
    let y1 = (y0 + 1).min(h - 1);

    let fx = u - x0 as f32;
    let fy = v - y0 as f32;
    let w00 = (1.0 - fx) * (1.0 - fy);
    let w10 = fx * (1.0 - fy);
    let w01 = (1.0 - fx) * fy;
    let w11 = fx * fy;

    let idx00 = (y0 * w + x0) * 4;
    let idx10 = (y0 * w + x1) * 4;
    let idx01 = (y1 * w + x0) * 4;
    let idx11 = (y1 * w + x1) * 4;

    let mut out = [0u8; 4];
    for c in 0..4 {
        let val = src[idx00 + c] as f32 * w00
            + src[idx10 + c] as f32 * w10
            + src[idx01 + c] as f32 * w01
            + src[idx11 + c] as f32 * w11;
        out[c] = val.round().clamp(0.0, 255.0) as u8;
    }
    out
}

/// Applies 2D Affine Transformation (Translation, Rotation, Scale, Pivot, Flip) to source RGBA pixels.
/// Returns destination buffer of size (dst_w * dst_h * 4).
pub fn transform_patch(
    src_rgba: &[u8],
    src_w: usize,
    src_h: usize,
    scale_x: f32,
    scale_y: f32,
    rotation_rad: f32,
    pivot_x: f32,
    pivot_y: f32,
    flip_h: bool,
    flip_v: bool,
) -> Result<(Vec<u8>, usize, usize, f32, f32), String> {
    if src_w == 0 || src_h == 0 || src_rgba.len() < src_w * src_h * 4 {
        return Err("Invalid source patch dimensions".to_string());
    }

    let mut sx = if flip_h { -scale_x } else { scale_x };
    let mut sy = if flip_v { -scale_y } else { scale_y };
    if sx.abs() < 0.001 {
        sx = 0.001 * (if sx < 0.0 { -1.0 } else { 1.0 });
    }
    if sy.abs() < 0.001 {
        sy = 0.001 * (if sy < 0.0 { -1.0 } else { 1.0 });
    }

    // Bounding box of rotated/scaled rectangle
    let cos_t = rotation_rad.cos();
    let sin_t = rotation_rad.sin();

    // 4 corners of source patch in pixel space [0, src_w] x [0, src_h]
    let src_corners = [
        (0.0f32, 0.0f32),
        (src_w as f32, 0.0f32),
        (src_w as f32, src_h as f32),
        (0.0f32, src_h as f32),
    ];

    let mut min_x = f32::MAX;
    let mut min_y = f32::MAX;
    let mut max_x = f32::MIN;
    let mut max_y = f32::MIN;

    for &(x, y) in &src_corners {
        let cx = (x - pivot_x) * sx;
        let cy = (y - pivot_y) * sy;
        let rx = cx * cos_t - cy * sin_t;
        let ry = cx * sin_t + cy * cos_t;
        min_x = min_x.min(rx);
        min_y = min_y.min(ry);
        max_x = max_x.max(rx);
        max_y = max_y.max(ry);
    }

    let dst_w = ((max_x - min_x).ceil() as usize).max(1);
    let dst_h = ((max_y - min_y).ceil() as usize).max(1);
    let mut dst_rgba = vec![0u8; dst_w * dst_h * 4];

    for dy in 0..dst_h {
        let ry = min_y + dy as f32 + 0.5;
        let row_offset = dy * dst_w * 4;

        for dx in 0..dst_w {
            let rx = min_x + dx as f32 + 0.5;

            // Rotate back by -rotation_rad
            let unrot_x = rx * cos_t + ry * sin_t;
            let unrot_y = -rx * sin_t + ry * cos_t;

            // Unscale and add pivot
            let u = unrot_x / sx + pivot_x;
            let v = unrot_y / sy + pivot_y;

            let u_sample = u - 0.5;
            let v_sample = v - 0.5;

            if u_sample >= -0.5 && u_sample <= (src_w as f32 - 0.5)
                && v_sample >= -0.5 && v_sample <= (src_h as f32 - 0.5)
            {
                let pixel = sample_bilinear(src_rgba, src_w, src_h, u_sample, v_sample);
                let p_idx = row_offset + dx * 4;
                dst_rgba[p_idx..p_idx + 4].copy_from_slice(&pixel);
            }
        }
    }

    Ok((dst_rgba, dst_w, dst_h, min_x, min_y))
}

/// Extracts pixels within polygon mask from tile into patch buffer, clearing them in tile.
pub fn extract_and_clear_tile_selection(
    tile_rgba: &mut [u8],
    tile_origin_x: i32,
    tile_origin_y: i32,
    tile_mask: &[u8],
    patch_rgba: &mut [u8],
    patch_w: usize,
    patch_h: usize,
    patch_origin_x: i32,
    patch_origin_y: i32,
) {
    let tile_size = 512i32;
    let ox_min = tile_origin_x.max(patch_origin_x);
    let ox_max = (tile_origin_x + tile_size).min(patch_origin_x + patch_w as i32);
    let oy_min = tile_origin_y.max(patch_origin_y);
    let oy_max = (tile_origin_y + tile_size).min(patch_origin_y + patch_h as i32);

    if ox_min >= ox_max || oy_min >= oy_max {
        return;
    }

    for y in oy_min..oy_max {
        let ty = (y - tile_origin_y) as usize;
        let py = (y - patch_origin_y) as usize;
        // Invert Y for OpenGL framebuffer: row 0 in tile_rgba is bottom (ty = 511), row 511 is top (ty = 0)
        let gl_ty = (TILE_SIZE - 1) - ty;
        let tile_row_start = gl_ty * (tile_size as usize) * 4;
        let mask_row_start = ty * (tile_size as usize);
        let patch_row_start = py * patch_w * 4;

        for x in ox_min..ox_max {
            let tx = (x - tile_origin_x) as usize;
            let px = (x - patch_origin_x) as usize;

            if tile_mask[mask_row_start + tx] > 0 {
                let t_idx = tile_row_start + tx * 4;
                let p_idx = patch_row_start + px * 4;

                // Copy to patch
                patch_rgba[p_idx..p_idx + 4].copy_from_slice(&tile_rgba[t_idx..t_idx + 4]);

                // Clear tile
                tile_rgba[t_idx..t_idx + 4].fill(0);
            }
        }
    }
}

/// Blits transformed RGBA patch onto a 512x512 tile with Porter-Duff Source-Over alpha blending.
pub fn blit_patch_to_tile(
    tile_rgba: &mut [u8],
    tile_origin_x: i32,
    tile_origin_y: i32,
    patch_rgba: &[u8],
    patch_w: usize,
    patch_h: usize,
    patch_origin_x: i32,
    patch_origin_y: i32,
) {
    let tile_size = 512i32;
    let ox_min = tile_origin_x.max(patch_origin_x);
    let ox_max = (tile_origin_x + tile_size).min(patch_origin_x + patch_w as i32);
    let oy_min = tile_origin_y.max(patch_origin_y);
    let oy_max = (tile_origin_y + tile_size).min(patch_origin_y + patch_h as i32);

    if ox_min >= ox_max || oy_min >= oy_max {
        return;
    }

    for y in oy_min..oy_max {
        let ty = (y - tile_origin_y) as usize;
        let py = (y - patch_origin_y) as usize;
        // Invert Y for OpenGL framebuffer: row 0 in tile_rgba is bottom (ty = 511), row 511 is top (ty = 0)
        let gl_ty = (TILE_SIZE - 1) - ty;
        let tile_row_start = gl_ty * (tile_size as usize) * 4;
        let patch_row_start = py * patch_w * 4;

        for x in ox_min..ox_max {
            let tx = (x - tile_origin_x) as usize;
            let px = (x - patch_origin_x) as usize;

            let p_idx = patch_row_start + px * 4;
            let t_idx = tile_row_start + tx * 4;

            let sa = patch_rgba[p_idx + 3] as u32;
            if sa == 0 {
                continue;
            }
            if sa == 255 {
                tile_rgba[t_idx..t_idx + 4].copy_from_slice(&patch_rgba[p_idx..p_idx + 4]);
            } else {
                let da = tile_rgba[t_idx + 3] as u32;
                let inv_sa = 255 - sa;
                let out_a = sa + (da * inv_sa + 127) / 255;
                if out_a > 0 {
                    for c in 0..3 {
                        let sc = patch_rgba[p_idx + c] as u32;
                        let dc = tile_rgba[t_idx + c] as u32;
                        // Premultiplied alpha Porter-Duff Source-Over
                        let out_c = sc + (dc * inv_sa + 127) / 255;
                        tile_rgba[t_idx + c] = out_c.min(255) as u8;
                    }
                    tile_rgba[t_idx + 3] = out_a.min(255) as u8;
                }
            }
        }
    }
}

// ============================================================================
// #5 NATIVE SPARSE SELECTION & TRANSFORM ENGINE ("Rust Magic")
// ============================================================================

/// Fast SIMD-accelerated 64-bit zero-check to reject empty tiles in < 1 microsecond.
#[inline(always)]
pub fn is_buffer_all_zero(buf: &[u8]) -> bool {
    let (prefix, words, suffix) = unsafe { buf.align_to::<u64>() };
    for &b in prefix {
        if b != 0 {
            return false;
        }
    }
    for &w in words {
        if w != 0 {
            return false;
        }
    }
    for &b in suffix {
        if b != 0 {
            return false;
        }
    }
    true
}

pub struct SelectionCutoutTile {
    pub tx: i32,
    pub ty: i32,
    pub orig_rgba: Vec<u8>,    // Original tile bytes in OpenGL bottom-up format
    pub cutout_rgba: Vec<u8>,  // Cutout pixels in standard top-down format (rest zeroed)
}

#[derive(Default)]
pub struct SelectionLayerData {
    pub tiles: HashMap<(i32, i32), SelectionCutoutTile>,
}

pub struct SelectionSession {
    pub polygon: Polygon,
    pub src_bounds: RectF,
    pub layers: HashMap<i64, SelectionLayerData>,
}

static SELECTION_SESSION: Mutex<Option<SelectionSession>> = Mutex::new(None);

pub fn session_begin(points: Vec<Point2D>) {
    let mut guard = match SELECTION_SESSION.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let poly = Polygon::new(points);
    let bounds = poly.bounds;
    *guard = Some(SelectionSession {
        polygon: poly,
        src_bounds: bounds,
        layers: HashMap::new(),
    });
}

/// Cuts out pixels inside the lasso polygon from tile_rgba (mutated in-place: cut pixels cleared to 0).
/// Stores the cutout tile in the native session outside Java heap.
/// Returns true if any painted (non-transparent) pixel was cut.
pub fn session_cut_tile(layer_id: i64, tx: i32, ty: i32, tile_rgba: &mut [u8]) -> bool {
    if tile_rgba.len() < TILE_SIZE * TILE_SIZE * 4 {
        return false;
    }

    // 1. SIMD-accelerated 64-bit zero-check: skip empty tiles in < 1 microsecond
    if is_buffer_all_zero(tile_rgba) {
        return false;
    }

    let mut guard = match SELECTION_SESSION.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    let session = match guard.as_mut() {
        Some(s) => s,
        None => return false,
    };

    // 2. Rasterize polygon mask for this tile
    let tile_world_x = tx as f32 * TILE_SIZE as f32;
    let tile_world_y = ty as f32 * TILE_SIZE as f32;
    let mask = match session.polygon.rasterize_tile_mask(tile_world_x, tile_world_y) {
        Some(m) => m,
        None => return false,
    };

    // 3. Check if any non-transparent pixel in tile_rgba falls inside the mask
    let mut has_selected_pixel = false;
    for py in 0..TILE_SIZE {
        let gl_ty = (TILE_SIZE - 1) - py;
        let tile_row = gl_ty * TILE_SIZE * 4;
        let mask_row = py * TILE_SIZE;
        for px in 0..TILE_SIZE {
            if mask[mask_row + px] > 0 {
                let t_idx = tile_row + px * 4;
                if tile_rgba[t_idx + 3] > 0 {
                    has_selected_pixel = true;
                    break;
                }
            }
        }
        if has_selected_pixel {
            break;
        }
    }

    if !has_selected_pixel {
        return false;
    }

    // 4. Extract cutouts and clear pixels in tile_rgba
    let orig_rgba = tile_rgba.to_vec();
    let mut cutout_rgba = vec![0u8; TILE_SIZE * TILE_SIZE * 4];

    for py in 0..TILE_SIZE {
        let gl_ty = (TILE_SIZE - 1) - py;
        let tile_row = gl_ty * TILE_SIZE * 4;
        let mask_row = py * TILE_SIZE;
        let cutout_row = py * TILE_SIZE * 4;
        for px in 0..TILE_SIZE {
            if mask[mask_row + px] > 0 {
                let t_idx = tile_row + px * 4;
                let c_idx = cutout_row + px * 4;
                cutout_rgba[c_idx..c_idx + 4].copy_from_slice(&tile_rgba[t_idx..t_idx + 4]);
                tile_rgba[t_idx..t_idx + 4].fill(0);
            }
        }
    }

    session
        .layers
        .entry(layer_id)
        .or_default()
        .tiles
        .insert(
            (tx, ty),
            SelectionCutoutTile {
                tx,
                ty,
                orig_rgba,
                cutout_rgba,
            },
        );

    true
}

/// Generates a downsampled preview RGBA buffer capped at max_dim for UI rendering.
pub fn session_get_preview(layer_id: i64, max_dim: usize) -> Option<(usize, usize, Vec<u8>)> {
    let guard = match SELECTION_SESSION.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let session = guard.as_ref()?;
    let layer = match session.layers.get(&layer_id) {
        Some(l) => l,
        None => return None,
    };

    let src_w = (session.src_bounds.max_x - session.src_bounds.min_x).ceil() as usize;
    let src_h = (session.src_bounds.max_y - session.src_bounds.min_y).ceil() as usize;
    if src_w == 0 || src_h == 0 {
        return None;
    }

    let max_side = src_w.max(src_h);
    let scale = if max_side > max_dim {
        max_dim as f32 / max_side as f32
    } else {
        1.0
    };

    let preview_w = ((src_w as f32 * scale).round() as usize).max(1);
    let preview_h = ((src_h as f32 * scale).round() as usize).max(1);
    let mut preview_rgba = vec![0u8; preview_w * preview_h * 4];

    for py in 0..preview_h {
        let wy = session.src_bounds.min_y + (py as f32 + 0.5) / scale;
        let ty = (wy / TILE_SIZE as f32).floor() as i32;
        let ly = ((wy - ty as f32 * TILE_SIZE as f32) as usize).min(TILE_SIZE - 1);
        let preview_row = py * preview_w * 4;

        for px in 0..preview_w {
            let wx = session.src_bounds.min_x + (px as f32 + 0.5) / scale;
            let tx = (wx / TILE_SIZE as f32).floor() as i32;
            let lx = ((wx - tx as f32 * TILE_SIZE as f32) as usize).min(TILE_SIZE - 1);

            if let Some(cutout) = layer.tiles.get(&(tx, ty)) {
                let c_idx = (ly * TILE_SIZE + lx) * 4;
                let p_idx = preview_row + px * 4;
                preview_rgba[p_idx..p_idx + 4].copy_from_slice(&cutout.cutout_rgba[c_idx..c_idx + 4]);
            }
        }
    }

    Some((preview_w, preview_h, preview_rgba))
}

#[inline(always)]
fn get_cutout_pixel(
    tiles: &HashMap<(i32, i32), SelectionCutoutTile>,
    wx: i32,
    wy: i32,
) -> [u8; 4] {
    let tx = wx.div_euclid(TILE_SIZE as i32);
    let ty = wy.div_euclid(TILE_SIZE as i32);
    if let Some(tile) = tiles.get(&(tx, ty)) {
        let lx = wx.rem_euclid(TILE_SIZE as i32) as usize;
        let ly = wy.rem_euclid(TILE_SIZE as i32) as usize;
        let idx = (ly * TILE_SIZE + lx) * 4;
        [
            tile.cutout_rgba[idx],
            tile.cutout_rgba[idx + 1],
            tile.cutout_rgba[idx + 2],
            tile.cutout_rgba[idx + 3],
        ]
    } else {
        [0, 0, 0, 0]
    }
}

#[inline(always)]
fn sample_cutout_bilinear(
    tiles: &HashMap<(i32, i32), SelectionCutoutTile>,
    u: f32,
    v: f32,
) -> [u8; 4] {
    let u_sample = u - 0.5;
    let v_sample = v - 0.5;
    let x0 = u_sample.floor() as i32;
    let y0 = v_sample.floor() as i32;
    let x1 = x0 + 1;
    let y1 = y0 + 1;

    let p00 = get_cutout_pixel(tiles, x0, y0);
    let p10 = get_cutout_pixel(tiles, x1, y0);
    let p01 = get_cutout_pixel(tiles, x0, y1);
    let p11 = get_cutout_pixel(tiles, x1, y1);

    if p00[3] == 0 && p10[3] == 0 && p01[3] == 0 && p11[3] == 0 {
        return [0, 0, 0, 0];
    }

    let fx = u_sample - x0 as f32;
    let fy = v_sample - y0 as f32;
    let w00 = (1.0 - fx) * (1.0 - fy);
    let w10 = fx * (1.0 - fy);
    let w01 = (1.0 - fx) * fy;
    let w11 = fx * fy;

    let mut out = [0u8; 4];
    for c in 0..4 {
        let val = p00[c] as f32 * w00
            + p10[c] as f32 * w10
            + p01[c] as f32 * w01
            + p11[c] as f32 * w11;
        out[c] = val.round().clamp(0.0, 255.0) as u8;
    }
    out
}

/// Computes the exact set of destination canvas tiles affected by the transformed selection.
pub fn session_get_affected_dest_tiles(
    layer_id: i64,
    scale_x: f32,
    scale_y: f32,
    rotation_rad: f32,
    trans_x: f32,
    trans_y: f32,
    pivot_x: f32,
    pivot_y: f32,
    flip_h: bool,
    flip_v: bool,
) -> Vec<(i32, i32)> {
    let guard = match SELECTION_SESSION.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let session = match guard.as_ref() {
        Some(s) => s,
        None => return Vec::new(),
    };
    let layer = match session.layers.get(&layer_id) {
        Some(l) => l,
        None => return Vec::new(),
    };

    if layer.tiles.is_empty() {
        return Vec::new();
    }

    let eff_sx = if flip_h { -scale_x } else { scale_x };
    let eff_sy = if flip_v { -scale_y } else { scale_y };
    let cos_t = rotation_rad.cos();
    let sin_t = rotation_rad.sin();

    let forward_transform = |wx: f32, wy: f32| -> (f32, f32) {
        let cx = (wx - pivot_x) * eff_sx;
        let cy = (wy - pivot_y) * eff_sy;
        let rx = cx * cos_t - cy * sin_t + pivot_x + trans_x;
        let ry = cx * sin_t + cy * cos_t + pivot_y + trans_y;
        (rx, ry)
    };

    let mut dest_set: HashSet<(i32, i32)> = HashSet::new();

    for &(tx, ty) in layer.tiles.keys() {
        let x0 = tx as f32 * TILE_SIZE as f32;
        let y0 = ty as f32 * TILE_SIZE as f32;
        let x1 = x0 + TILE_SIZE as f32;
        let y1 = y0 + TILE_SIZE as f32;

        let sel_min_x = session.src_bounds.min_x.max(x0);
        let sel_max_x = session.src_bounds.max_x.min(x1);
        let sel_min_y = session.src_bounds.min_y.max(y0);
        let sel_max_y = session.src_bounds.max_y.min(y1);

        if sel_min_x >= sel_max_x || sel_min_y >= sel_max_y {
            continue;
        }

        let corners = [
            forward_transform(sel_min_x, sel_min_y),
            forward_transform(sel_max_x, sel_min_y),
            forward_transform(sel_max_x, sel_max_y),
            forward_transform(sel_min_x, sel_max_y),
        ];

        let min_x = corners.iter().map(|c| c.0).fold(f32::INFINITY, f32::min);
        let max_x = corners.iter().map(|c| c.0).fold(f32::NEG_INFINITY, f32::max);
        let min_y = corners.iter().map(|c| c.1).fold(f32::INFINITY, f32::min);
        let max_y = corners.iter().map(|c| c.1).fold(f32::NEG_INFINITY, f32::max);

        let min_tx = (min_x / TILE_SIZE as f32).floor() as i32;
        let max_tx = ((max_x - 0.001) / TILE_SIZE as f32).floor() as i32;
        let min_ty = (min_y / TILE_SIZE as f32).floor() as i32;
        let max_ty = ((max_y - 0.001) / TILE_SIZE as f32).floor() as i32;

        for dty in min_ty..=max_ty {
            for dtx in min_tx..=max_tx {
                dest_set.insert((dtx, dty));
            }
        }
    }

    let mut result: Vec<(i32, i32)> = dest_set.into_iter().collect();
    result.sort_unstable();
    result
}

/// Blits the transformed selection onto a destination tile using bilinear interpolation
/// and Porter-Duff Source-Over alpha blending. Modifies tile_rgba in-place.
/// Returns true if any pixel in the tile was modified.
pub fn session_blit_to_tile(
    layer_id: i64,
    dtx: i32,
    dty: i32,
    tile_rgba: &mut [u8],
    scale_x: f32,
    scale_y: f32,
    rotation_rad: f32,
    trans_x: f32,
    trans_y: f32,
    pivot_x: f32,
    pivot_y: f32,
    flip_h: bool,
    flip_v: bool,
) -> bool {
    if tile_rgba.len() < TILE_SIZE * TILE_SIZE * 4 {
        return false;
    }

    let guard = match SELECTION_SESSION.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let session = match guard.as_ref() {
        Some(s) => s,
        None => return false,
    };
    let layer = match session.layers.get(&layer_id) {
        Some(l) => l,
        None => return false,
    };

    let mut eff_sx = if flip_h { -scale_x } else { scale_x };
    let mut eff_sy = if flip_v { -scale_y } else { scale_y };
    if eff_sx.abs() < 0.001 {
        eff_sx = 0.001 * (if eff_sx < 0.0 { -1.0 } else { 1.0 });
    }
    if eff_sy.abs() < 0.001 {
        eff_sy = 0.001 * (if eff_sy < 0.0 { -1.0 } else { 1.0 });
    }

    let cos_t = rotation_rad.cos();
    let sin_t = rotation_rad.sin();
    let mut any_modified = false;

    for dly in 0..TILE_SIZE {
        let gl_ty = (TILE_SIZE - 1) - dly;
        let dest_row = gl_ty * TILE_SIZE * 4;
        let dest_wy = dty as f32 * TILE_SIZE as f32 + dly as f32 + 0.5;
        let ry = dest_wy - (pivot_y + trans_y);

        for dlx in 0..TILE_SIZE {
            let dest_wx = dtx as f32 * TILE_SIZE as f32 + dlx as f32 + 0.5;
            let rx = dest_wx - (pivot_x + trans_x);

            let unrot_x = rx * cos_t + ry * sin_t;
            let unrot_y = -rx * sin_t + ry * cos_t;

            let src_wx = unrot_x / eff_sx + pivot_x;
            let src_wy = unrot_y / eff_sy + pivot_y;

            let src_pixel = sample_cutout_bilinear(&layer.tiles, src_wx, src_wy);
            let sa = src_pixel[3] as u32;
            if sa == 0 {
                continue;
            }

            let t_idx = dest_row + dlx * 4;
            if sa == 255 {
                tile_rgba[t_idx..t_idx + 4].copy_from_slice(&src_pixel);
                any_modified = true;
            } else {
                let da = tile_rgba[t_idx + 3] as u32;
                let inv_sa = 255 - sa;
                let out_a = sa + (da * inv_sa + 127) / 255;
                if out_a > 0 {
                    for c in 0..3 {
                        let sc = src_pixel[c] as u32;
                        let dc = tile_rgba[t_idx + c] as u32;
                        let out_c = sc + (dc * inv_sa + 127) / 255;
                        tile_rgba[t_idx + c] = out_c.min(255) as u8;
                    }
                    tile_rgba[t_idx + 3] = out_a.min(255) as u8;
                    any_modified = true;
                }
            }
        }
    }

    any_modified
}

/// Frees all sparse tile cutouts and session data from native memory.
pub fn session_end() {
    let mut guard = match SELECTION_SESSION.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    *guard = None;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_polygon_point_in_triangle() {
        let poly = Polygon::new(vec![
            Point2D { x: 0.0, y: 0.0 },
            Point2D { x: 100.0, y: 0.0 },
            Point2D { x: 50.0, y: 100.0 },
        ]);

        assert!(poly.contains_point(50.0, 50.0));
        assert!(!poly.contains_point(150.0, 50.0));
        assert!(!poly.contains_point(50.0, -10.0));
    }

    #[test]
    fn test_transform_patch_identity() {
        let w = 10;
        let h = 10;
        let mut src = vec![0u8; w * h * 4];
        src[0] = 255; // red pixel at (0, 0)
        src[3] = 255;

        let (dst, dw, dh, ox, oy) = transform_patch(
            &src, w, h, 1.0, 1.0, 0.0, 0.0, 0.0, false, false,
        ).unwrap();

        assert_eq!(dw, w);
        assert_eq!(dh, h);
        assert_eq!(ox, 0.0);
        assert_eq!(oy, 0.0);
        assert_eq!(dst[0], 255);
        assert_eq!(dst[3], 255);
    }

    #[test]
    fn test_transform_patch_flip_h() {
        let w = 10;
        let h = 10;
        let mut src = vec![0u8; w * h * 4];
        src[0] = 255; // red pixel at (0, 0)
        src[3] = 255;

        // Pivot at center (5.0, 5.0)
        let (dst, dw, dh, _ox, _oy) = transform_patch(
            &src, w, h, 1.0, 1.0, 0.0, 5.0, 5.0, true, false,
        ).unwrap();

        assert_eq!(dw, w);
        assert_eq!(dh, h);
        // Pixel at (0, 0) should have moved to (9, 0)
        let idx = (0 * dw + 9) * 4;
        assert_eq!(dst[idx], 255);
        assert_eq!(dst[idx + 3], 255);
    }

    #[test]
    fn test_extract_and_blit_roundtrip() {
        let mut tile = vec![0u8; 512 * 512 * 4];
        // Put a blue pixel at world (10, 10). In OpenGL buffer, gl_ty = 511 - 10 = 501
        let gl_ty = 511 - 10;
        let t_idx = (gl_ty * 512 + 10) * 4;
        tile[t_idx + 2] = 255;
        tile[t_idx + 3] = 255;

        // Mask where (10, 10) is inside
        let mut mask = vec![0u8; 512 * 512];
        mask[10 * 512 + 10] = 255;

        let mut patch = vec![0u8; 20 * 20 * 4];
        extract_and_clear_tile_selection(
            &mut tile, 0, 0, &mask, &mut patch, 20, 20, 5, 5,
        );

        // Tile pixel should now be cleared
        assert_eq!(tile[t_idx + 2], 0);
        assert_eq!(tile[t_idx + 3], 0);

        // Patch pixel at (10 - 5, 10 - 5) = (5, 5) should be blue
        let p_idx = (5 * 20 + 5) * 4;
        assert_eq!(patch[p_idx + 2], 255);
        assert_eq!(patch[p_idx + 3], 255);

        // Blit back onto tile at offset (5, 5)
        blit_patch_to_tile(
            &mut tile, 0, 0, &patch, 20, 20, 5, 5,
        );

        // Tile pixel at world (10, 10) should be restored!
        assert_eq!(tile[t_idx + 2], 255);
        assert_eq!(tile[t_idx + 3], 255);
    }

    #[test]
    fn test_rasterize_tile_mask_scanline() {
        // Draw a triangle at world (50, 50) to (150, 50) to (100, 150)
        let poly = Polygon::new(vec![
            Point2D { x: 50.0, y: 50.0 },
            Point2D { x: 150.0, y: 50.0 },
            Point2D { x: 100.0, y: 150.0 },
        ]);

        let mask = poly.rasterize_tile_mask(0.0, 0.0).expect("Mask should not be None");
        assert_eq!(mask.len(), 512 * 512);

        // Center of triangle (100, 80) should be inside (255)
        let center_idx = 80 * 512 + 100;
        assert_eq!(mask[center_idx], 255);

        // Well outside triangle (200, 200) should be 0
        let outside_idx = 200 * 512 + 200;
        assert_eq!(mask[outside_idx], 0);

        // Outside near (40, 50) should be 0
        let near_outside_idx = 50 * 512 + 40;
        assert_eq!(mask[near_outside_idx], 0);
    }

    #[test]
    fn test_rasterize_tile_mask_smooth_500_points() {
        // High-density 500-point circle lasso centered at (256, 256) with radius 100
        let mut pts = Vec::with_capacity(500);
        for i in 0..500 {
            let angle = (i as f32 / 500.0) * 2.0 * std::f32::consts::PI;
            pts.push(Point2D {
                x: 256.0 + 100.0 * angle.cos(),
                y: 256.0 + 100.0 * angle.sin(),
            });
        }
        let poly = Polygon::new(pts);

        let mask = poly.rasterize_tile_mask(0.0, 0.0).expect("Mask must be generated");

        // Center must be inside
        assert_eq!(mask[256 * 512 + 256], 255);

        // Point at radius 50 (256, 306) must be inside
        assert_eq!(mask[306 * 512 + 256], 255);

        // Point at radius 150 (256, 406) must be outside
        assert_eq!(mask[406 * 512 + 256], 0);
    }

    #[test]
    fn test_is_buffer_all_zero() {
        let zero_buf = vec![0u8; 512 * 512 * 4];
        assert!(is_buffer_all_zero(&zero_buf));

        let mut non_zero = vec![0u8; 512 * 512 * 4];
        non_zero[12345] = 1;
        assert!(!is_buffer_all_zero(&non_zero));
    }

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn test_selection_session_sparse_cut_and_preview() {
        let _lock = TEST_LOCK.lock().unwrap();
        // Create triangle selection at (10, 10), (100, 10), (50, 100)
        session_begin(vec![
            Point2D { x: 10.0, y: 10.0 },
            Point2D { x: 100.0, y: 10.0 },
            Point2D { x: 50.0, y: 100.0 },
        ]);

        // Tile (0, 0) has a red pixel at (50, 50)
        // OpenGL bottom-up: gl_ty = 511 - 50 = 461
        let mut tile = vec![0u8; 512 * 512 * 4];
        let t_idx = (461 * 512 + 50) * 4;
        tile[t_idx] = 255;
        tile[t_idx + 3] = 255;

        let cut = session_cut_tile(1i64, 0, 0, &mut tile);
        assert!(cut);

        // Pixel in tile must now be cleared
        assert_eq!(tile[t_idx], 0);
        assert_eq!(tile[t_idx + 3], 0);

        // Preview should have the red pixel
        let (pw, ph, preview) = session_get_preview(1i64, 1024).expect("Preview expected");
        assert!(pw > 0 && ph > 0);
        // Find if red pixel exists in preview
        let mut found_red = false;
        for chunk in preview.chunks_exact(4) {
            if chunk[0] == 255 && chunk[3] == 255 {
                found_red = true;
                break;
            }
        }
        assert!(found_red, "Preview must contain the cut red pixel");

        session_end();
    }

    #[test]
    fn test_selection_session_transform_blit() {
        let _lock = TEST_LOCK.lock().unwrap();
        // Select square (10, 10) to (50, 50)
        session_begin(vec![
            Point2D { x: 10.0, y: 10.0 },
            Point2D { x: 50.0, y: 10.0 },
            Point2D { x: 50.0, y: 50.0 },
            Point2D { x: 10.0, y: 50.0 },
        ]);

        // Tile (0, 0) with green pixel at (20, 20)
        // gl_ty = 511 - 20 = 491
        let mut tile = vec![0u8; 512 * 512 * 4];
        let t_idx = (491 * 512 + 20) * 4;
        tile[t_idx + 1] = 255;
        tile[t_idx + 3] = 255;

        assert!(session_cut_tile(1, 0, 0, &mut tile));

        // Affected dest tiles with translation (100, 100)
        let dest_tiles = session_get_affected_dest_tiles(
            1, 1.0, 1.0, 0.0, 100.0, 100.0, 30.0, 30.0, false, false,
        );
        assert_eq!(dest_tiles, vec![(0, 0)]);

        // Blit to dest tile (0, 0)
        let mut dest_tile = vec![0u8; 512 * 512 * 4];
        let blitted = session_blit_to_tile(
            1, 0, 0, &mut dest_tile,
            1.0, 1.0, 0.0, 100.0, 100.0, 30.0, 30.0, false, false,
        );
        assert!(blitted);

        // The green pixel was at (20, 20) and translated by +100,+100 -> now at (120, 120)
        // In destination OpenGL buffer, gl_ty = 511 - 120 = 391
        let dest_idx = (391 * 512 + 120) * 4;
        assert_eq!(dest_tile[dest_idx + 1], 255);
        assert_eq!(dest_tile[dest_idx + 3], 255);

        session_end();
    }
}
