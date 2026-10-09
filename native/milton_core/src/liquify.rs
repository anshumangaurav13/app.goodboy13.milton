const TILE_SIZE: usize = 512;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum LiquifyMode {
    Push = 0,
    Expand = 1,
    Pinch = 2,
    TwirlCw = 3,
    TwirlCcw = 4,
    Reconstruct = 5,
}

impl LiquifyMode {
    pub fn from_i32(val: i32) -> Self {
        match val {
            1 => LiquifyMode::Expand,
            2 => LiquifyMode::Pinch,
            3 => LiquifyMode::TwirlCw,
            4 => LiquifyMode::TwirlCcw,
            5 => LiquifyMode::Reconstruct,
            _ => LiquifyMode::Push,
        }
    }
}

#[inline(always)]
fn catmull_rom_weights(t: f32) -> (f32, f32, f32, f32) {
    let t2 = t * t;
    let t3 = t2 * t;
    let w_neg1 = -0.5 * t3 + t2 - 0.5 * t;
    let w0 = 1.5 * t3 - 2.5 * t2 + 1.0;
    let w1 = -1.5 * t3 + 2.0 * t2 + 0.5 * t;
    let w2 = 0.5 * t3 - 0.5 * t2;
    (w_neg1, w0, w1, w2)
}

#[inline(always)]
pub fn sample_catmull_rom_rgba(
    src: &[u8],
    width: usize,
    height: usize,
    u: f32,
    v: f32,
) -> [u8; 4] {
    let w_i = width as i32;
    let h_i = height as i32;

    let x_floor = u.floor() as i32;
    let y_floor = v.floor() as i32;
    let fx = u - x_floor as f32;
    let fy = v - y_floor as f32;

    let (wx_neg1, wx0, wx1, wx2) = catmull_rom_weights(fx);
    let (wy_neg1, wy0, wy1, wy2) = catmull_rom_weights(fy);

    let xs = [
        (x_floor - 1).clamp(0, w_i - 1) as usize,
        x_floor.clamp(0, w_i - 1) as usize,
        (x_floor + 1).clamp(0, w_i - 1) as usize,
        (x_floor + 2).clamp(0, w_i - 1) as usize,
    ];
    let ys = [
        (y_floor - 1).clamp(0, h_i - 1) as usize,
        y_floor.clamp(0, h_i - 1) as usize,
        (y_floor + 1).clamp(0, h_i - 1) as usize,
        (y_floor + 2).clamp(0, h_i - 1) as usize,
    ];
    let wx = [wx_neg1, wx0, wx1, wx2];
    let wy = [wy_neg1, wy0, wy1, wy2];

    let mut accum = [0.0f32; 4];
    for ky in 0..4 {
        let row_y = ys[ky];
        let w_y = wy[ky];
        let row_start = row_y * width * 4;
        for kx in 0..4 {
            let col_x = xs[kx];
            let w = w_y * wx[kx];
            let idx = row_start + col_x * 4;
            for c in 0..4 {
                accum[c] += src[idx + c] as f32 * w;
            }
        }
    }

    [
        accum[0].round().clamp(0.0, 255.0) as u8,
        accum[1].round().clamp(0.0, 255.0) as u8,
        accum[2].round().clamp(0.0, 255.0) as u8,
        accum[3].round().clamp(0.0, 255.0) as u8,
    ]
}

#[inline(always)]
fn sample_displacement_bilinear(disp: &[f32], w: usize, h: usize, u: f32, v: f32) -> (f32, f32) {
    let cu = u.clamp(0.0, (w - 1) as f32);
    let cv = v.clamp(0.0, (h - 1) as f32);
    let x0 = cu.floor() as usize;
    let y0 = cv.floor() as usize;
    let x1 = (x0 + 1).min(w - 1);
    let y1 = (y0 + 1).min(h - 1);
    let fx = cu - x0 as f32;
    let fy = cv - y0 as f32;

    let w00 = (1.0 - fx) * (1.0 - fy);
    let w10 = fx * (1.0 - fy);
    let w01 = (1.0 - fx) * fy;
    let w11 = fx * fy;

    let i00 = (y0 * w + x0) * 2;
    let i10 = (y0 * w + x1) * 2;
    let i01 = (y1 * w + x0) * 2;
    let i11 = (y1 * w + x1) * 2;

    let dx = w00 * disp[i00] + w10 * disp[i10] + w01 * disp[i01] + w11 * disp[i11];
    let dy = w00 * disp[i00 + 1] + w10 * disp[i10 + 1] + w01 * disp[i01 + 1] + w11 * disp[i11 + 1];
    (dx, dy)
}

/// Applies a 2D Liquify warp dab to a contiguous RGBA patch.
/// When `orig_rgba` and `disp_field` are provided, accumulates deformation in `disp_field`
/// and samples from the pristine `orig_rgba` using Catmull-Rom bicubic interpolation,
/// ensuring ZERO repeated-resampling blurring across any number of dabs.
pub fn liquify_patch(
    patch_rgba: &mut [u8],
    orig_rgba: Option<&[u8]>,
    mut disp_field: Option<&mut [f32]>,
    width: usize,
    height: usize,
    patch_origin_x: f32,
    patch_origin_y: f32,
    center_x: f32,
    center_y: f32,
    radius: f32,
    strength: f32,
    mode: LiquifyMode,
    dir_x: f32,
    dir_y: f32,
) {
    if width == 0 || height == 0 || radius <= 0.0 || strength <= 0.0 {
        return;
    }

    let local_cx = center_x - patch_origin_x;
    let local_cy = center_y - patch_origin_y;

    let min_x = (local_cx - radius).floor().max(0.0) as usize;
    let max_x = ((local_cx + radius).ceil() as usize).min(width.saturating_sub(1));
    let min_y = (local_cy - radius).floor().max(0.0) as usize;
    let max_y = ((local_cy + radius).ceil() as usize).min(height.saturating_sub(1));

    if min_x > max_x || min_y > max_y {
        return;
    }

    let r_sq = radius * radius;

    // Zero-blur path: accumulated 2D displacement sampled from pristine original with Catmull-Rom
    if let (Some(orig), Some(disp)) = (orig_rgba, disp_field.as_deref_mut()) {
        for y in min_y..=max_y {
            let dy = y as f32 - local_cy;
            let dy_sq = dy * dy;
            for x in min_x..=max_x {
                let dx = x as f32 - local_cx;
                let dist_sq = dx * dx + dy_sq;
                if dist_sq > r_sq {
                    continue;
                }

                let dist = dist_sq.sqrt();
                let rho = (dist / radius).clamp(0.0, 1.0);
                let w = (1.0 - rho * rho) * (1.0 - rho * rho);
                let eff_s = (strength * w).clamp(0.0, 1.0);

                let disp_idx = (y * width + x) * 2;
                let dst_idx = (y * width + x) * 4;

                let (new_dx, new_dy) = if mode == LiquifyMode::Reconstruct {
                    let old_dx = disp[disp_idx];
                    let old_dy = disp[disp_idx + 1];
                    (old_dx * (1.0 - eff_s), old_dy * (1.0 - eff_s))
                } else {
                    let (u, v) = match mode {
                        LiquifyMode::Push => (x as f32 - eff_s * dir_x, y as f32 - eff_s * dir_y),
                        LiquifyMode::Expand => {
                            (local_cx + dx * (1.0 - eff_s * 0.4), local_cy + dy * (1.0 - eff_s * 0.4))
                        }
                        LiquifyMode::Pinch => {
                            (local_cx + dx * (1.0 + eff_s * 0.4), local_cy + dy * (1.0 + eff_s * 0.4))
                        }
                        LiquifyMode::TwirlCw => {
                            let theta = dy.atan2(dx);
                            let new_theta = theta - eff_s * 0.8;
                            (local_cx + dist * new_theta.cos(), local_cy + dist * new_theta.sin())
                        }
                        LiquifyMode::TwirlCcw => {
                            let theta = dy.atan2(dx);
                            let new_theta = theta + eff_s * 0.8;
                            (local_cx + dist * new_theta.cos(), local_cy + dist * new_theta.sin())
                        }
                        LiquifyMode::Reconstruct => unreachable!(),
                    };

                    let (prev_dx, prev_dy) = sample_displacement_bilinear(disp, width, height, u, v);
                    ((u - x as f32) + prev_dx, (v - y as f32) + prev_dy)
                };

                disp[disp_idx] = new_dx;
                disp[disp_idx + 1] = new_dy;

                // Sample pristine source image at (x + new_dx, y + new_dy) with Catmull-Rom
                let sx = x as f32 + new_dx;
                let sy = y as f32 + new_dy;
                let pixel = sample_catmull_rom_rgba(orig, width, height, sx, sy);
                patch_rgba[dst_idx..dst_idx + 4].copy_from_slice(&pixel);
            }
        }
        return;
    }

    // Fallback path if displacement field is omitted
    let src = patch_rgba.to_vec();

    for y in min_y..=max_y {
        let dy = y as f32 - local_cy;
        let dy_sq = dy * dy;
        for x in min_x..=max_x {
            let dx = x as f32 - local_cx;
            let dist_sq = dx * dx + dy_sq;
            if dist_sq > r_sq {
                continue;
            }

            let dist = dist_sq.sqrt();
            let rho = (dist / radius).clamp(0.0, 1.0);
            let w = (1.0 - rho * rho) * (1.0 - rho * rho);
            let eff_s = (strength * w).clamp(0.0, 1.0);

            let dst_idx = (y * width + x) * 4;

            if mode == LiquifyMode::Reconstruct {
                if let Some(orig) = orig_rgba {
                    for c in 0..4 {
                        let cur_v = src[dst_idx + c] as f32;
                        let orig_v = orig[dst_idx + c] as f32;
                        let out_v = cur_v * (1.0 - eff_s) + orig_v * eff_s;
                        patch_rgba[dst_idx + c] = out_v.round().clamp(0.0, 255.0) as u8;
                    }
                }
                continue;
            }

            let (u, v) = match mode {
                LiquifyMode::Push => (x as f32 - eff_s * dir_x, y as f32 - eff_s * dir_y),
                LiquifyMode::Expand => {
                    (local_cx + dx * (1.0 - eff_s * 0.4), local_cy + dy * (1.0 - eff_s * 0.4))
                }
                LiquifyMode::Pinch => {
                    (local_cx + dx * (1.0 + eff_s * 0.4), local_cy + dy * (1.0 + eff_s * 0.4))
                }
                LiquifyMode::TwirlCw => {
                    let theta = dy.atan2(dx);
                    let new_theta = theta - eff_s * 0.8;
                    (local_cx + dist * new_theta.cos(), local_cy + dist * new_theta.sin())
                }
                LiquifyMode::TwirlCcw => {
                    let theta = dy.atan2(dx);
                    let new_theta = theta + eff_s * 0.8;
                    (local_cx + dist * new_theta.cos(), local_cy + dist * new_theta.sin())
                }
                LiquifyMode::Reconstruct => unreachable!(),
            };

            let cu = u.clamp(0.0, (width - 1) as f32);
            let cv = v.clamp(0.0, (height - 1) as f32);

            let x0 = cu.floor() as usize;
            let y0 = cv.floor() as usize;
            let x1 = (x0 + 1).min(width - 1);
            let y1 = (y0 + 1).min(height - 1);

            let fx = cu - x0 as f32;
            let fy = cv - y0 as f32;

            let w00 = (1.0 - fx) * (1.0 - fy);
            let w10 = fx * (1.0 - fy);
            let w01 = (1.0 - fx) * fy;
            let w11 = fx * fy;

            let idx00 = (y0 * width + x0) * 4;
            let idx10 = (y0 * width + x1) * 4;
            let idx01 = (y1 * width + x0) * 4;
            let idx11 = (y1 * width + x1) * 4;

            for c in 0..4 {
                let v00 = src[idx00 + c] as f32;
                let v10 = src[idx10 + c] as f32;
                let v01 = src[idx01 + c] as f32;
                let v11 = src[idx11 + c] as f32;
                let val = w00 * v00 + w10 * v10 + w01 * v01 + w11 * v11;
                patch_rgba[dst_idx + c] = val.round().clamp(0.0, 255.0) as u8;
            }
        }
    }
}

/// Extracts RGBA pixels from an OpenGL 512x512 tile into a destination patch.
pub fn extract_tile_region(
    tile_rgba: &[u8],
    tile_origin_x: i32,
    tile_origin_y: i32,
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
        let tile_row_start = gl_ty * TILE_SIZE * 4;
        let patch_row_start = py * patch_w * 4;

        for x in ox_min..ox_max {
            let tx = (x - tile_origin_x) as usize;
            let px = (x - patch_origin_x) as usize;
            let t_idx = tile_row_start + tx * 4;
            let p_idx = patch_row_start + px * 4;
            patch_rgba[p_idx..p_idx + 4].copy_from_slice(&tile_rgba[t_idx..t_idx + 4]);
        }
    }
}

/// Overwrites a region of an OpenGL 512x512 tile from a source patch.
pub fn blit_patch_to_tile_overwrite(
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
        let tile_row_start = gl_ty * TILE_SIZE * 4;
        let patch_row_start = py * patch_w * 4;

        for x in ox_min..ox_max {
            let tx = (x - tile_origin_x) as usize;
            let px = (x - patch_origin_x) as usize;
            let t_idx = tile_row_start + tx * 4;
            let p_idx = patch_row_start + px * 4;
            tile_rgba[t_idx..t_idx + 4].copy_from_slice(&patch_rgba[p_idx..p_idx + 4]);
        }
    }
}

/// Extracts 2D displacement floats (dx, dy) from a 512x512 tile into a destination patch.
pub fn extract_tile_displacement(
    tile_disp: &[f32],
    tile_origin_x: i32,
    tile_origin_y: i32,
    patch_disp: &mut [f32],
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
        let gl_ty = (TILE_SIZE - 1) - ty;
        let tile_row_start = gl_ty * TILE_SIZE * 2;
        let patch_row_start = py * patch_w * 2;

        for x in ox_min..ox_max {
            let tx = (x - tile_origin_x) as usize;
            let px = (x - patch_origin_x) as usize;
            let t_idx = tile_row_start + tx * 2;
            let p_idx = patch_row_start + px * 2;
            patch_disp[p_idx] = tile_disp[t_idx];
            patch_disp[p_idx + 1] = tile_disp[t_idx + 1];
        }
    }
}

/// Blits 2D displacement floats from a patch back into a 512x512 tile.
pub fn blit_tile_displacement_overwrite(
    tile_disp: &mut [f32],
    tile_origin_x: i32,
    tile_origin_y: i32,
    patch_disp: &[f32],
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
        let gl_ty = (TILE_SIZE - 1) - ty;
        let tile_row_start = gl_ty * TILE_SIZE * 2;
        let patch_row_start = py * patch_w * 2;

        for x in ox_min..ox_max {
            let tx = (x - tile_origin_x) as usize;
            let px = (x - patch_origin_x) as usize;
            let t_idx = tile_row_start + tx * 2;
            let p_idx = patch_row_start + px * 2;
            tile_disp[t_idx] = patch_disp[p_idx];
            tile_disp[t_idx + 1] = patch_disp[p_idx + 1];
        }
    }
}

// ============================================================================
// # HIGH-PERFORMANCE NATIVE LIQUIFY SESSION ENGINE
// ============================================================================

use std::collections::HashMap;
use std::sync::Mutex;

pub struct LiquifyTile {
    pub orig_rgba: Vec<u8>,
    pub working_rgba: Vec<u8>,
    pub disp: Vec<f32>, // 512 * 512 * 2 (world-space dx, dy)
}

pub struct LiquifySession {
    pub tiles: HashMap<(i64, i32, i32), LiquifyTile>,
    pub scratch_disp: Vec<(usize, f32, f32)>,
    pub scratch_pixels: Vec<(usize, [u8; 4])>,
    pub dirty: Vec<(i64, i32, i32)>,
}

static SESSION: Mutex<Option<LiquifySession>> = Mutex::new(None);

pub fn session_begin() {
    let mut guard = match SESSION.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    *guard = Some(LiquifySession {
        tiles: HashMap::new(),
        scratch_disp: Vec::with_capacity(TILE_SIZE * TILE_SIZE),
        scratch_pixels: Vec::with_capacity(TILE_SIZE * TILE_SIZE),
        dirty: Vec::new(),
    });
}

pub fn session_register_tile(layer_id: i64, tx: i32, ty: i32, orig_rgba: &[u8]) {
    let mut guard = match SESSION.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    if let Some(session) = guard.as_mut() {
        let key = (layer_id, tx, ty);
        if !session.tiles.contains_key(&key) {
            let expected_len = TILE_SIZE * TILE_SIZE * 4;
            let bytes = if orig_rgba.len() >= expected_len {
                orig_rgba[..expected_len].to_vec()
            } else {
                vec![0u8; expected_len]
            };
            session.tiles.insert(
                key,
                LiquifyTile {
                    orig_rgba: bytes.clone(),
                    working_rgba: bytes,
                    disp: vec![0.0f32; TILE_SIZE * TILE_SIZE * 2],
                },
            );
        }
    }
}

#[inline(always)]
fn get_orig_pixel(
    layer_id: i64,
    wx: i32,
    wy: i32,
    tiles: &HashMap<(i64, i32, i32), LiquifyTile>,
) -> [u8; 4] {
    let tx = wx.div_euclid(512);
    let ty = wy.div_euclid(512);
    let lx = wx.rem_euclid(512) as usize;
    let ly = wy.rem_euclid(512) as usize;

    if let Some(tile) = tiles.get(&(layer_id, tx, ty)) {
        let gl_ly = (TILE_SIZE - 1) - ly;
        let idx = (gl_ly * TILE_SIZE + lx) * 4;
        [
            tile.orig_rgba[idx],
            tile.orig_rgba[idx + 1],
            tile.orig_rgba[idx + 2],
            tile.orig_rgba[idx + 3],
        ]
    } else {
        [0, 0, 0, 0]
    }
}

#[inline(always)]
fn sample_world_catmull_rom(
    layer_id: i64,
    u: f32,
    v: f32,
    tiles: &HashMap<(i64, i32, i32), LiquifyTile>,
) -> [u8; 4] {
    let x_floor = u.floor() as i32;
    let y_floor = v.floor() as i32;
    let fx = u - x_floor as f32;
    let fy = v - y_floor as f32;

    let s_tx = x_floor.div_euclid(512);
    let s_ty = y_floor.div_euclid(512);
    let s_ox = s_tx * 512;
    let s_oy = s_ty * 512;

    let (wx_neg1, wx0, wx1, wx2) = catmull_rom_weights(fx);
    let (wy_neg1, wy0, wy1, wy2) = catmull_rom_weights(fy);
    let wx = [wx_neg1, wx0, wx1, wx2];
    let wy = [wy_neg1, wy0, wy1, wy2];

    // Fast path: if all 16 sample coordinates [x_floor - 1 .. x_floor + 2, y_floor - 1 .. y_floor + 2]
    // lie completely within the single tile (s_tx, s_ty)
    if x_floor - 1 >= s_ox && x_floor + 2 < s_ox + 512 && y_floor - 1 >= s_oy && y_floor + 2 < s_oy + 512 {
        if let Some(tile) = tiles.get(&(layer_id, s_tx, s_ty)) {
            let base_lx = (x_floor - 1 - s_ox) as usize;
            let base_ly = (y_floor - 1 - s_oy) as usize;

            let mut accum = [0.0f32; 4];
            for ky in 0..4 {
                let w_y = wy[ky];
                let ly = base_ly + ky;
                let gl_ly = (TILE_SIZE - 1) - ly;
                let row_offset = gl_ly * TILE_SIZE * 4;
                for kx in 0..4 {
                    let w = w_y * wx[kx];
                    let lx = base_lx + kx;
                    let idx = row_offset + lx * 4;
                    accum[0] += tile.orig_rgba[idx] as f32 * w;
                    accum[1] += tile.orig_rgba[idx + 1] as f32 * w;
                    accum[2] += tile.orig_rgba[idx + 2] as f32 * w;
                    accum[3] += tile.orig_rgba[idx + 3] as f32 * w;
                }
            }

            return [
                accum[0].round().clamp(0.0, 255.0) as u8,
                accum[1].round().clamp(0.0, 255.0) as u8,
                accum[2].round().clamp(0.0, 255.0) as u8,
                accum[3].round().clamp(0.0, 255.0) as u8,
            ];
        } else {
            return [0, 0, 0, 0];
        }
    }

    // Boundary fallback (straddles multiple tiles)
    let xs = [x_floor - 1, x_floor, x_floor + 1, x_floor + 2];
    let ys = [y_floor - 1, y_floor, y_floor + 1, y_floor + 2];

    let mut accum = [0.0f32; 4];
    for ky in 0..4 {
        let cur_y = ys[ky];
        let w_y = wy[ky];
        for kx in 0..4 {
            let cur_x = xs[kx];
            let w = w_y * wx[kx];
            let p = get_orig_pixel(layer_id, cur_x, cur_y, tiles);
            accum[0] += p[0] as f32 * w;
            accum[1] += p[1] as f32 * w;
            accum[2] += p[2] as f32 * w;
            accum[3] += p[3] as f32 * w;
        }
    }

    [
        accum[0].round().clamp(0.0, 255.0) as u8,
        accum[1].round().clamp(0.0, 255.0) as u8,
        accum[2].round().clamp(0.0, 255.0) as u8,
        accum[3].round().clamp(0.0, 255.0) as u8,
    ]
}

#[inline(always)]
fn get_disp_point(
    any_layer_id: i64,
    wx: i32,
    wy: i32,
    tiles: &HashMap<(i64, i32, i32), LiquifyTile>,
) -> (f32, f32) {
    let tx = wx.div_euclid(512);
    let ty = wy.div_euclid(512);
    let lx = wx.rem_euclid(512) as usize;
    let ly = wy.rem_euclid(512) as usize;

    if let Some(tile) = tiles.get(&(any_layer_id, tx, ty)) {
        let gl_ly = (TILE_SIZE - 1) - ly;
        let idx = (gl_ly * TILE_SIZE + lx) * 2;
        (tile.disp[idx], tile.disp[idx + 1])
    } else {
        (0.0, 0.0)
    }
}

#[inline(always)]
fn sample_world_displacement(
    any_layer_id: i64,
    u: f32,
    v: f32,
    tiles: &HashMap<(i64, i32, i32), LiquifyTile>,
) -> (f32, f32) {
    let x_floor = u.floor() as i32;
    let y_floor = v.floor() as i32;
    let fx = u - x_floor as f32;
    let fy = v - y_floor as f32;

    let s_tx = x_floor.div_euclid(512);
    let s_ty = y_floor.div_euclid(512);
    let s_ox = s_tx * 512;
    let s_oy = s_ty * 512;

    // Fast path: if the 2x2 bilinear quad lies entirely inside one tile
    if x_floor >= s_ox && x_floor + 1 < s_ox + 512 && y_floor >= s_oy && y_floor + 1 < s_oy + 512 {
        if let Some(tile) = tiles.get(&(any_layer_id, s_tx, s_ty)) {
            let lx0 = (x_floor - s_ox) as usize;
            let lx1 = lx0 + 1;
            let ly0 = (y_floor - s_oy) as usize;
            let ly1 = ly0 + 1;
            let gl_ly0 = (TILE_SIZE - 1) - ly0;
            let gl_ly1 = (TILE_SIZE - 1) - ly1;
            let row0 = gl_ly0 * TILE_SIZE * 2;
            let row1 = gl_ly1 * TILE_SIZE * 2;

            let idx00 = row0 + lx0 * 2;
            let idx10 = row0 + lx1 * 2;
            let idx01 = row1 + lx0 * 2;
            let idx11 = row1 + lx1 * 2;

            let w00 = (1.0 - fx) * (1.0 - fy);
            let w10 = fx * (1.0 - fy);
            let w01 = (1.0 - fx) * fy;
            let w11 = fx * fy;

            return (
                w00 * tile.disp[idx00] + w10 * tile.disp[idx10] + w01 * tile.disp[idx01] + w11 * tile.disp[idx11],
                w00 * tile.disp[idx00 + 1] + w10 * tile.disp[idx10 + 1] + w01 * tile.disp[idx01 + 1] + w11 * tile.disp[idx11 + 1],
            );
        }
    }

    // Boundary fallback
    let p00 = get_disp_point(any_layer_id, x_floor, y_floor, tiles);
    let p10 = get_disp_point(any_layer_id, x_floor + 1, y_floor, tiles);
    let p01 = get_disp_point(any_layer_id, x_floor, y_floor + 1, tiles);
    let p11 = get_disp_point(any_layer_id, x_floor + 1, y_floor + 1, tiles);

    let w00 = (1.0 - fx) * (1.0 - fy);
    let w10 = fx * (1.0 - fy);
    let w01 = (1.0 - fx) * fy;
    let w11 = fx * fy;

    (
        w00 * p00.0 + w10 * p10.0 + w01 * p01.0 + w11 * p11.0,
        w00 * p00.1 + w10 * p10.1 + w01 * p01.1 + w11 * p11.1,
    )
}

pub fn session_apply_dab(
    target_layer_ids: &[i64],
    center_x: f32,
    center_y: f32,
    radius: f32,
    strength: f32,
    mode: LiquifyMode,
    dir_x: f32,
    dir_y: f32,
) -> Vec<(i64, i32, i32)> {
    if radius <= 0.0 || strength <= 0.0 || target_layer_ids.is_empty() {
        return Vec::new();
    }

    let mut guard = match SESSION.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let session = match guard.as_mut() {
        Some(s) => s,
        None => return Vec::new(),
    };

    let r_sq = radius * radius;
    let min_tx = ((center_x - radius).floor() as i32).div_euclid(512);
    let max_tx = ((center_x + radius).ceil() as i32).div_euclid(512);
    let min_ty = ((center_y - radius).floor() as i32).div_euclid(512);
    let max_ty = ((center_y + radius).ceil() as i32).div_euclid(512);

    let ref_layer_id = target_layer_ids[0];
    let mut modified_keys = Vec::new();

    // 1. Pass 1: Update displacement field on all intersecting tiles
    for ty in min_ty..=max_ty {
        for tx in min_tx..=max_tx {
            let key = (ref_layer_id, tx, ty);
            if !session.tiles.contains_key(&key) {
                continue;
            }

            let tile_ox = tx * 512;
            let tile_oy = ty * 512;

            let box_min_wx = tile_ox.max((center_x - radius).floor() as i32);
            let box_max_wx = (tile_ox + 511).min((center_x + radius).ceil() as i32);
            let box_min_wy = tile_oy.max((center_y - radius).floor() as i32);
            let box_max_wy = (tile_oy + 511).min((center_y + radius).ceil() as i32);

            if box_min_wx > box_max_wx || box_min_wy > box_max_wy {
                continue;
            }

            let min_lx = (box_min_wx - tile_ox) as usize;
            let max_lx = (box_max_wx - tile_ox) as usize;
            let min_ly = (box_min_wy - tile_oy) as usize;
            let max_ly = (box_max_wy - tile_oy) as usize;

            session.scratch_disp.clear();

            for ly in min_ly..=max_ly {
                let wy = tile_oy + ly as i32;
                let dy = wy as f32 - center_y;
                let dy_sq = dy * dy;
                let gl_ly = (TILE_SIZE - 1) - ly;

                for lx in min_lx..=max_lx {
                    let wx = tile_ox + lx as i32;
                    let dx = wx as f32 - center_x;
                    let dist_sq = dx * dx + dy_sq;
                    if dist_sq > r_sq {
                        continue;
                    }

                    let cur_idx = (gl_ly * TILE_SIZE + lx) * 2;
                    let tile = session.tiles.get(&key).unwrap();
                    let cur_dx = tile.disp[cur_idx];
                    let cur_dy = tile.disp[cur_idx + 1];

                    let dist = dist_sq.sqrt();
                    let u_dist = (dist / radius).clamp(0.0, 1.0);
                    let t = 1.0 - u_dist * u_dist;
                    let falloff = t * t * t;
                    let eff_s = (strength * falloff).clamp(0.0, 1.0);

                    if mode == LiquifyMode::Reconstruct {
                        session.scratch_disp.push((cur_idx, cur_dx * (1.0 - eff_s * 0.15), cur_dy * (1.0 - eff_s * 0.15)));
                        continue;
                    }

                    // Inverse warp mapping in world coordinates
                    let (u, v) = match mode {
                        LiquifyMode::Push => {
                            (wx as f32 - eff_s * dir_x, wy as f32 - eff_s * dir_y)
                        }
                        LiquifyMode::Expand => {
                            let push = radius * 0.04 * eff_s;
                            if dist > 0.001 {
                                (wx as f32 - (dx / dist) * push, wy as f32 - (dy / dist) * push)
                            } else {
                                (wx as f32, wy as f32)
                            }
                        }
                        LiquifyMode::Pinch => {
                            let pull = radius * 0.04 * eff_s;
                            if dist > 0.001 {
                                (wx as f32 + (dx / dist) * pull, wy as f32 + (dy / dist) * pull)
                            } else {
                                (wx as f32, wy as f32)
                            }
                        }
                        LiquifyMode::TwirlCw => {
                            let theta = dy.atan2(dx) - eff_s * 0.08;
                            (center_x + dist * theta.cos(), center_y + dist * theta.sin())
                        }
                        LiquifyMode::TwirlCcw => {
                            let theta = dy.atan2(dx) + eff_s * 0.08;
                            (center_x + dist * theta.cos(), center_y + dist * theta.sin())
                        }
                        LiquifyMode::Reconstruct => unreachable!(),
                    };

                    let (prev_dx, prev_dy) = sample_world_displacement(ref_layer_id, u, v, &session.tiles);
                    let ndx = (u - wx as f32) + prev_dx;
                    let ndy = (v - wy as f32) + prev_dy;
                    session.scratch_disp.push((cur_idx, ndx, ndy));
                }
            }

            // Write back updated displacements into reference tile
            if !session.scratch_disp.is_empty() {
                let tile = session.tiles.get_mut(&key).unwrap();
                for &(idx, ndx, ndy) in &session.scratch_disp {
                    tile.disp[idx] = ndx;
                    tile.disp[idx + 1] = ndy;
                }

                // Copy displacements to secondary target layers if multiple selected
                for &other_layer in target_layer_ids.iter().skip(1) {
                    let other_key = (other_layer, tx, ty);
                    if let Some(other_tile) = session.tiles.get_mut(&other_key) {
                        for &(idx, ndx, ndy) in &session.scratch_disp {
                            other_tile.disp[idx] = ndx;
                            other_tile.disp[idx + 1] = ndy;
                        }
                    }
                }
            }
        }
    }

    // 2. Pass 2: Resample pristine original pixels with Catmull-Rom across infinite canvas
    for &layer_id in target_layer_ids {
        for ty in min_ty..=max_ty {
            for tx in min_tx..=max_tx {
                let key = (layer_id, tx, ty);
                if !session.tiles.contains_key(&key) {
                    continue;
                }

                let tile_ox = tx * 512;
                let tile_oy = ty * 512;

                let box_min_wx = tile_ox.max((center_x - radius).floor() as i32);
                let box_max_wx = (tile_ox + 511).min((center_x + radius).ceil() as i32);
                let box_min_wy = tile_oy.max((center_y - radius).floor() as i32);
                let box_max_wy = (tile_oy + 511).min((center_y + radius).ceil() as i32);

                if box_min_wx > box_max_wx || box_min_wy > box_max_wy {
                    continue;
                }

                let min_lx = (box_min_wx - tile_ox) as usize;
                let max_lx = (box_max_wx - tile_ox) as usize;
                let min_ly = (box_min_wy - tile_oy) as usize;
                let max_ly = (box_max_wy - tile_oy) as usize;

                session.scratch_pixels.clear();

                for ly in min_ly..=max_ly {
                    let wy = tile_oy + ly as i32;
                    let dy = wy as f32 - center_y;
                    let dy_sq = dy * dy;
                    let gl_ly = (TILE_SIZE - 1) - ly;

                    for lx in min_lx..=max_lx {
                        let wx = tile_ox + lx as i32;
                        let dx = wx as f32 - center_x;
                        let dist_sq = dx * dx + dy_sq;
                        if dist_sq > r_sq {
                            continue;
                        }

                        let tile = session.tiles.get(&key).unwrap();
                        let idx = (gl_ly * TILE_SIZE + lx) * 2;
                        let t_dx = tile.disp[idx];
                        let t_dy = tile.disp[idx + 1];

                        let sx = wx as f32 + t_dx;
                        let sy = wy as f32 + t_dy;
                        let pixel = sample_world_catmull_rom(layer_id, sx, sy, &session.tiles);

                        let p_idx = (gl_ly * TILE_SIZE + lx) * 4;
                        session.scratch_pixels.push((p_idx, pixel));
                    }
                }

                if !session.scratch_pixels.is_empty() {
                    let tile = session.tiles.get_mut(&key).unwrap();
                    for &(p_idx, pixel) in &session.scratch_pixels {
                        tile.working_rgba[p_idx..p_idx + 4].copy_from_slice(&pixel);
                    }
                    modified_keys.push(key);
                }
            }
        }
    }

    session.dirty = modified_keys.clone();
    modified_keys
}

pub fn session_get_tile_pixels(layer_id: i64, tx: i32, ty: i32, out_buf: &mut [u8]) -> bool {
    let guard = match SESSION.lock() {
        Ok(g) => g,
        Err(_) => return false,
    };
    let session = match guard.as_ref() {
        Some(s) => s,
        None => return false,
    };

    if let Some(tile) = session.tiles.get(&(layer_id, tx, ty)) {
        let len = tile.working_rgba.len().min(out_buf.len());
        out_buf[..len].copy_from_slice(&tile.working_rgba[..len]);
        true
    } else {
        false
    }
}

pub fn session_end() {
    let mut guard = SESSION.lock().unwrap();
    *guard = None;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_liquify_push_displaces_pixel() {
        let w = 20;
        let h = 20;
        let mut patch = vec![0u8; w * h * 4];
        // Set white block around (10, 10)
        for y in 9..=11 {
            for x in 9..=11 {
                let idx = (y * w + x) * 4;
                patch[idx..idx + 4].fill(255);
            }
        }

        // Push toward +X by 4px
        liquify_patch(
            &mut patch,
            None,
            None,
            w, h,
            0.0, 0.0,
            10.0, 10.0,
            8.0,
            1.0,
            LiquifyMode::Push,
            4.0, 0.0,
        );

        // Pixel should have pushed past x = 11 to x = 13..14
        let pushed_idx = (10 * w + 13) * 4;
        assert!(patch[pushed_idx + 3] > 0);
    }

    #[test]
    fn test_liquify_with_displacement_preserves_sharpness() {
        let w = 32;
        let h = 32;
        let mut orig = vec![0u8; w * h * 4];
        // High-contrast alternating pixel pattern (black/white) to test blur preservation
        for y in 0..h {
            for x in 0..w {
                let idx = (y * w + x) * 4;
                let val = if (x + y) % 2 == 0 { 255u8 } else { 0u8 };
                orig[idx..idx + 4].fill(val);
            }
        }

        let mut working = orig.clone();
        let mut disp = vec![0.0f32; w * h * 2];

        // Apply 30 repeated small pushes over the same area!
        for _ in 0..30 {
            liquify_patch(
                &mut working,
                Some(&orig),
                Some(&mut disp),
                w, h,
                0.0, 0.0,
                16.0, 16.0,
                12.0,
                0.05,
                LiquifyMode::Push,
                0.5, 0.0,
            );
        }

        // Because of displacement tracking from pristine orig, contrast should NOT degrade to flat 128
        // Check standard deviation or max value in deformed region
        let mut max_val = 0u8;
        let mut min_val = 255u8;
        for y in 12..=20 {
            for x in 12..=20 {
                let v = working[(y * w + x) * 4];
                max_val = max_val.max(v);
                min_val = min_val.min(v);
            }
        }
        // Contrast is retained (should be near 255 and near 0, NOT collapsed to ~128)
        assert!(max_val > 200, "Max value was {}, blurred out!", max_val);
        assert!(min_val < 50, "Min value was {}, blurred out!", min_val);
    }

    #[test]
    fn test_extract_and_blit_overwrite_roundtrip() {
        let mut tile = vec![0u8; 512 * 512 * 4];
        let gl_ty = 511 - 100;
        let t_idx = (gl_ty * 512 + 100) * 4;
        tile[t_idx] = 200;
        tile[t_idx + 3] = 255;

        let mut patch = vec![0u8; 30 * 30 * 4];
        extract_tile_region(
            &tile, 0, 0, &mut patch, 30, 30, 90, 90,
        );

        let p_idx = (10 * 30 + 10) * 4;
        assert_eq!(patch[p_idx], 200);
        assert_eq!(patch[p_idx + 3], 255);

        // Blit back to new tile
        let mut new_tile = vec![0u8; 512 * 512 * 4];
        blit_patch_to_tile_overwrite(
            &mut new_tile, 0, 0, &patch, 30, 30, 90, 90,
        );

        assert_eq!(new_tile[t_idx], 200);
        assert_eq!(new_tile[t_idx + 3], 255);
    }

    #[test]
    fn test_extract_and_blit_displacement_roundtrip() {
        let mut tile_disp = vec![0.0f32; 512 * 512 * 2];
        let gl_ty = 511 - 100;
        let t_idx = (gl_ty * 512 + 100) * 2;
        tile_disp[t_idx] = 12.5f32;
        tile_disp[t_idx + 1] = -8.25f32;

        let mut patch_disp = vec![0.0f32; 30 * 30 * 2];
        extract_tile_displacement(
            &tile_disp, 0, 0, &mut patch_disp, 30, 30, 90, 90,
        );

        let p_idx = (10 * 30 + 10) * 2;
        assert_eq!(patch_disp[p_idx], 12.5f32);
        assert_eq!(patch_disp[p_idx + 1], -8.25f32);

        // Blit back to new tile
        let mut new_tile_disp = vec![0.0f32; 512 * 512 * 2];
        blit_tile_displacement_overwrite(
            &mut new_tile_disp, 0, 0, &patch_disp, 30, 30, 90, 90,
        );

        assert_eq!(new_tile_disp[t_idx], 12.5f32);
        assert_eq!(new_tile_disp[t_idx + 1], -8.25f32);
    }

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn test_liquify_session_lifecycle_and_deformation() {
        let _lock = TEST_LOCK.lock().unwrap();
        session_begin();

        let mut orig_tile = vec![0u8; 512 * 512 * 4];
        // Draw a solid red square in center of tile 0,0 (world x: 200..300, y: 200..300)
        for wy in 200..300 {
            let gl_ly = 511 - wy;
            for wx in 200..300 {
                let idx = (gl_ly * 512 + wx) * 4;
                orig_tile[idx] = 255;
                orig_tile[idx + 3] = 255;
            }
        }

        let layer_id = 42i64;
        session_register_tile(layer_id, 0, 0, &orig_tile);

        // Apply a push dab pushing towards the right (+x) with dir_x = 40.0
        let modified = session_apply_dab(
            &[layer_id],
            250.0,
            250.0,
            80.0,
            1.0,
            LiquifyMode::Push,
            40.0,
            0.0,
        );
        assert_eq!(modified.len(), 1);
        assert_eq!(modified[0], (layer_id, 0, 0));

        let mut out_pixels = vec![0u8; 512 * 512 * 4];
        assert!(session_get_tile_pixels(layer_id, 0, 0, &mut out_pixels));

        // The pixel at wx=302, wy=250 was originally black (0, because square ended at 299),
        // but push displaced it from sx = 302 - ~8 = ~294 (inside the red square)!
        let gl_ly = 511 - 250;
        let test_idx = (gl_ly * 512 + 302) * 4;
        assert!(out_pixels[test_idx] > 100, "Pixel at 302,250 was not pushed: {}", out_pixels[test_idx]);

        session_end();
    }

    #[test]
    fn test_liquify_session_cross_tile_deformation() {
        let _lock = TEST_LOCK.lock().unwrap();
        session_begin();

        let mut tile_left = vec![0u8; 512 * 512 * 4];
        let tile_right = vec![0u8; 512 * 512 * 4];

        // Draw green content near the right edge of tile (0, 0): wx from 500 to 511
        for wy in 240..270 {
            let gl_ly = 511 - wy;
            for wx in 460..512 {
                let idx = (gl_ly * 512 + wx) * 4;
                tile_left[idx + 1] = 255;
                tile_left[idx + 3] = 255;
            }
        }

        let layer_id = 99i64;
        session_register_tile(layer_id, 0, 0, &tile_left);
        session_register_tile(layer_id, 1, 0, &tile_right);

        // Apply a push dab centered right across the tile boundary at wx = 510, pushing into tile (1, 0)
        let modified = session_apply_dab(
            &[layer_id],
            510.0,
            255.0,
            60.0,
            1.0,
            LiquifyMode::Push,
            30.0,
            0.0,
        );

        // Both tiles should be modified seamlessly across the 512 border
        assert!(modified.contains(&(layer_id, 0, 0)));
        assert!(modified.contains(&(layer_id, 1, 0)));

        let mut right_pixels = vec![0u8; 512 * 512 * 4];
        assert!(session_get_tile_pixels(layer_id, 1, 0, &mut right_pixels));

        // Tile (1, 0) was originally empty (black), but green pixels from tile (0, 0) were pushed into it (wx = 515, so lx = 3)
        let gl_ly = 511 - 255;
        let right_idx = (gl_ly * 512 + 3) * 4;
        assert!(
            right_pixels[right_idx + 1] > 100,
            "Pixel in neighbor tile across boundary not displaced: {}",
            right_pixels[right_idx + 1]
        );

        session_end();
    }
}



