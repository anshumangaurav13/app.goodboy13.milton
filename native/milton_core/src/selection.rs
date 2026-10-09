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
}
