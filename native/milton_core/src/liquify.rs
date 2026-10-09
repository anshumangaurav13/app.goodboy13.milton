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
}
