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

/// Applies a 2D Liquify warp dab to a contiguous RGBA patch.
pub fn liquify_patch(
    patch_rgba: &mut [u8],
    orig_rgba: Option<&[u8]>,
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

    let src = patch_rgba.to_vec();
    let r_sq = radius * radius;

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
            // Smooth cubic hermite falloff
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

            // Calculate inverse sampling position (u, v)
            let (u, v) = match mode {
                LiquifyMode::Push => {
                    (x as f32 - eff_s * dir_x, y as f32 - eff_s * dir_y)
                }
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

            // Bilinear interpolation from src
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
}
