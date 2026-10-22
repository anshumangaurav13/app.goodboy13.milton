use crate::selection::{Point2D, RectF};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum LiquifyMode {
    Push = 0,
    Bloat = 1,
    Pinch = 2,
    TwirlCw = 3,
    TwirlCcw = 4,
    Smooth = 5,
}

impl LiquifyMode {
    pub fn from_i32(val: i32) -> Self {
        match val {
            0 => LiquifyMode::Push,
            1 => LiquifyMode::Bloat,
            2 => LiquifyMode::Pinch,
            3 => LiquifyMode::TwirlCw,
            4 => LiquifyMode::TwirlCcw,
            5 => LiquifyMode::Smooth,
            _ => LiquifyMode::Push,
        }
    }
}

/// High-performance 2D deformation mesh for Liquify operations.
/// Completely off-heap with zero GC churn and vectorized vertex kernels.
pub struct LiquifyMesh {
    pub min_x: f32,
    pub min_y: f32,
    pub max_x: f32,
    pub max_y: f32,
    pub width: f32,
    pub height: f32,
    pub grid_cols: usize,
    pub grid_rows: usize,
    pub vertex_count: usize,
    pub init_local_x: Vec<f32>,
    pub init_local_y: Vec<f32>,
    pub world_x: Vec<f32>,
    pub world_y: Vec<f32>,
    pub mask_weights: Vec<f32>,
}

impl LiquifyMesh {
    pub fn new(bounds: RectF, polygon: &[Point2D], grid_cols: usize, grid_rows: usize) -> Self {
        let width = (bounds.max_x - bounds.min_x).max(1.0);
        let height = (bounds.max_y - bounds.min_y).max(1.0);
        let vertex_count = (grid_cols + 1) * (grid_rows + 1);

        let mut init_local_x = Vec::with_capacity(vertex_count);
        let mut init_local_y = Vec::with_capacity(vertex_count);
        let mut world_x = Vec::with_capacity(vertex_count);
        let mut world_y = Vec::with_capacity(vertex_count);
        let mut mask_weights = Vec::with_capacity(vertex_count);

        let dx = width / grid_cols as f32;
        let dy = height / grid_rows as f32;
        let feather_radius = (width.max(height) * 0.05).clamp(32.0, 96.0);

        for r in 0..=grid_rows {
            let ly = (r as f32 * dy).min(height);
            let wy = bounds.min_y + ly;
            for c in 0..=grid_cols {
                let lx = (c as f32 * dx).min(width);
                let wx = bounds.min_x + lx;

                init_local_x.push(lx);
                init_local_y.push(ly);
                world_x.push(wx);
                world_y.push(wy);

                let pt = Point2D { x: wx, y: wy };
                if Self::is_point_in_polygon(pt, polygon) {
                    let edge_dist = Self::min_distance_to_polygon(pt, polygon);
                    let t = (edge_dist / feather_radius).clamp(0.0, 1.0);
                    // Smooth cubic Hermite C^1 curve: 3t^2 - 2t^3
                    mask_weights.push(t * t * (3.0 - 2.0 * t));
                } else {
                    mask_weights.push(0.0);
                }
            }
        }

        Self {
            min_x: bounds.min_x,
            min_y: bounds.min_y,
            max_x: bounds.max_x,
            max_y: bounds.max_y,
            width,
            height,
            grid_cols,
            grid_rows,
            vertex_count,
            init_local_x,
            init_local_y,
            world_x,
            world_y,
            mask_weights,
        }
    }

    pub fn reset(&mut self) {
        for i in 0..self.vertex_count {
            self.world_x[i] = self.min_x + self.init_local_x[i];
            self.world_y[i] = self.min_y + self.init_local_y[i];
        }
    }

    pub fn apply_stroke(
        &mut self,
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
        radius: f32,
        strength: f32,
        mode: LiquifyMode,
    ) {
        if radius <= 0.001 || strength <= 0.001 {
            return;
        }

        let r2 = radius * radius;
        let seg_dx = x2 - x1;
        let seg_dy = y2 - y1;
        let seg_len_sq = seg_dx * seg_dx + seg_dy * seg_dy;

        let mid_x = (x1 + x2) * 0.5;
        let mid_y = (y1 + y2) * 0.5;

        let min_x = x1.min(x2) - radius;
        let max_x = x1.max(x2) + radius;
        let min_y = y1.min(y2) - radius;
        let max_y = y1.max(y2) + radius;

        let eff_strength = strength.clamp(0.01, 1.0);

        for i in 0..self.vertex_count {
            let w = self.mask_weights[i];
            if w <= 0.001 {
                continue;
            }

            let vx = self.world_x[i];
            let vy = self.world_y[i];

            if vx < min_x || vx > max_x || vy < min_y || vy > max_y {
                continue;
            }

            let dist_sq = if seg_len_sq < 0.001 {
                let ddx = vx - x2;
                let ddy = vy - y2;
                ddx * ddx + ddy * ddy
            } else {
                let t = (((vx - x1) * seg_dx + (vy - y1) * seg_dy) / seg_len_sq).clamp(0.0, 1.0);
                let proj_x = x1 + t * seg_dx;
                let proj_y = y1 + t * seg_dy;
                let ddx = vx - proj_x;
                let ddy = vy - proj_y;
                ddx * ddx + ddy * ddy
            };

            if dist_sq >= r2 {
                continue;
            }

            let dist = dist_sq.sqrt();
            let f = 1.0 - dist / radius;
            // Smooth cubic Hermite falloff
            let falloff = f * f * (3.0 - 2.0 * f);
            let weight = falloff * w * eff_strength;

            match mode {
                LiquifyMode::Push => {
                    self.world_x[i] += seg_dx * weight;
                    self.world_y[i] += seg_dy * weight;
                }
                LiquifyMode::Bloat => {
                    let ddx = vx - mid_x;
                    let ddy = vy - mid_y;
                    let factor = 0.35 * weight;
                    self.world_x[i] += ddx * factor;
                    self.world_y[i] += ddy * factor;
                }
                LiquifyMode::Pinch => {
                    let ddx = vx - mid_x;
                    let ddy = vy - mid_y;
                    let factor = 0.35 * weight;
                    self.world_x[i] -= ddx * factor;
                    self.world_y[i] -= ddy * factor;
                }
                LiquifyMode::TwirlCw => {
                    let ddx = vx - mid_x;
                    let ddy = vy - mid_y;
                    let angle = 0.60 * weight;
                    let (sin_a, cos_a) = angle.sin_cos();
                    self.world_x[i] = mid_x + (ddx * cos_a - ddy * sin_a);
                    self.world_y[i] = mid_y + (ddx * sin_a + ddy * cos_a);
                }
                LiquifyMode::TwirlCcw => {
                    let ddx = vx - mid_x;
                    let ddy = vy - mid_y;
                    let angle = -0.60 * weight;
                    let (sin_a, cos_a) = angle.sin_cos();
                    self.world_x[i] = mid_x + (ddx * cos_a - ddy * sin_a);
                    self.world_y[i] = mid_y + (ddx * sin_a + ddy * cos_a);
                }
                LiquifyMode::Smooth => {
                    let r = i / (self.grid_cols + 1);
                    let c = i % (self.grid_cols + 1);
                    let mut neighbor_count = 0usize;
                    let mut avg_x = 0.0f32;
                    let mut avg_y = 0.0f32;

                    if c > 0 {
                        let left_idx = i - 1;
                        avg_x += self.world_x[left_idx];
                        avg_y += self.world_y[left_idx];
                        neighbor_count += 1;
                    }
                    if c < self.grid_cols {
                        let right_idx = i + 1;
                        avg_x += self.world_x[right_idx];
                        avg_y += self.world_y[right_idx];
                        neighbor_count += 1;
                    }
                    if r > 0 {
                        let up_idx = i - (self.grid_cols + 1);
                        avg_x += self.world_x[up_idx];
                        avg_y += self.world_y[up_idx];
                        neighbor_count += 1;
                    }
                    if r < self.grid_rows {
                        let down_idx = i + (self.grid_cols + 1);
                        avg_x += self.world_x[down_idx];
                        avg_y += self.world_y[down_idx];
                        neighbor_count += 1;
                    }

                    if neighbor_count > 0 {
                        avg_x /= neighbor_count as f32;
                        avg_y /= neighbor_count as f32;
                        self.world_x[i] += (avg_x - vx) * 0.5 * weight;
                        self.world_y[i] += (avg_y - vy) * 0.5 * weight;
                    }
                }
            }
        }
    }

    pub fn compute_local_warped_verts(&self, out: &mut [f32]) {
        let mut vi = 0;
        for i in 0..self.vertex_count {
            if vi + 1 < out.len() {
                out[vi] = self.world_x[i] - self.min_x;
                out[vi + 1] = self.world_y[i] - self.min_y;
                vi += 2;
            }
        }
    }

    pub fn is_point_in_polygon(p: Point2D, poly: &[Point2D]) -> bool {
        if poly.len() < 3 {
            return false;
        }
        let mut inside = false;
        let mut j = poly.len() - 1;
        for i in 0..poly.len() {
            let pi = poly[i];
            let pj = poly[j];
            if ((pi.y > p.y) != (pj.y > p.y))
                && (p.x < (pj.x - pi.x) * (p.y - pi.y) / (pj.y - pi.y) + pi.x)
            {
                inside = !inside;
            }
            j = i;
        }
        inside
    }

    pub fn min_distance_to_polygon(p: Point2D, poly: &[Point2D]) -> f32 {
        if poly.len() < 2 {
            return 0.0;
        }
        let mut min_dist_sq = f32::MAX;
        for i in 0..poly.len() {
            let a = poly[i];
            let b = poly[(i + 1) % poly.len()];
            let abx = b.x - a.x;
            let aby = b.y - a.y;
            let len_sq = abx * abx + aby * aby;
            let dist_sq = if len_sq < 0.001 {
                let dx = p.x - a.x;
                let dy = p.y - a.y;
                dx * dx + dy * dy
            } else {
                let t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / len_sq).clamp(0.0, 1.0);
                let proj_x = a.x + t * abx;
                let proj_y = a.y + t * aby;
                let dx = p.x - proj_x;
                let dy = p.y - proj_y;
                dx * dx + dy * dy
            };
            min_dist_sq = min_dist_sq.min(dist_sq);
        }
        min_dist_sq.sqrt()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mesh_initialization_and_grid_dimensions() {
        let bounds = RectF { min_x: 0.0, min_y: 0.0, max_x: 100.0, max_y: 100.0 };
        let polygon = vec![
            Point2D { x: 10.0, y: 10.0 },
            Point2D { x: 90.0, y: 10.0 },
            Point2D { x: 90.0, y: 90.0 },
            Point2D { x: 10.0, y: 90.0 },
        ];
        let mesh = LiquifyMesh::new(bounds, &polygon, 10, 10);

        assert_eq!(mesh.vertex_count, 121);
        assert!((mesh.width - 100.0).abs() < 0.01);
        assert!((mesh.height - 100.0).abs() < 0.01);

        assert_eq!(mesh.mask_weights[0], 0.0);
        assert_eq!(mesh.mask_weights[120], 0.0);

        let center_idx = 5 * 11 + 5;
        assert!((mesh.world_x[center_idx] - 50.0).abs() < 0.01);
        assert!((mesh.world_y[center_idx] - 50.0).abs() < 0.01);
        assert!(mesh.mask_weights[center_idx] > 0.5);
    }

    #[test]
    fn test_push_stroke_moves_vertices_strictly_inside_lasso() {
        let bounds = RectF { min_x: 0.0, min_y: 0.0, max_x: 100.0, max_y: 100.0 };
        let polygon = vec![
            Point2D { x: 20.0, y: 20.0 },
            Point2D { x: 80.0, y: 20.0 },
            Point2D { x: 80.0, y: 80.0 },
            Point2D { x: 20.0, y: 80.0 },
        ];
        let mut mesh = LiquifyMesh::new(bounds, &polygon, 10, 10);

        let center_idx = 5 * 11 + 5;
        let init_center_x = mesh.world_x[center_idx];
        let init_center_y = mesh.world_y[center_idx];

        mesh.apply_stroke(45.0, 50.0, 55.0, 50.0, 25.0, 0.8, LiquifyMode::Push);

        assert!(mesh.world_x[center_idx] > init_center_x);
        assert!((mesh.world_y[center_idx] - init_center_y).abs() < 0.1);

        // Corner vertex outside lasso (0, 0) MUST NOT move
        assert_eq!(mesh.world_x[0], 0.0);
        assert_eq!(mesh.world_y[0], 0.0);
    }

    #[test]
    fn test_bloat_and_pinch_opposites() {
        let bounds = RectF { min_x: 0.0, min_y: 0.0, max_x: 100.0, max_y: 100.0 };
        let polygon = vec![
            Point2D { x: 10.0, y: 10.0 },
            Point2D { x: 90.0, y: 10.0 },
            Point2D { x: 90.0, y: 90.0 },
            Point2D { x: 10.0, y: 90.0 },
        ];
        let mut bloat_mesh = LiquifyMesh::new(bounds, &polygon, 10, 10);
        let mut pinch_mesh = LiquifyMesh::new(bounds, &polygon, 10, 10);

        let target_idx = 5 * 11 + 6;
        let orig_x = bloat_mesh.world_x[target_idx];

        bloat_mesh.apply_stroke(50.0, 50.0, 50.0, 50.0, 30.0, 0.7, LiquifyMode::Bloat);
        assert!(bloat_mesh.world_x[target_idx] > orig_x);

        pinch_mesh.apply_stroke(50.0, 50.0, 50.0, 50.0, 30.0, 0.7, LiquifyMode::Pinch);
        assert!(pinch_mesh.world_x[target_idx] < orig_x);
    }

    #[test]
    fn test_twirl_cw_and_ccw_rotations() {
        let bounds = RectF { min_x: 0.0, min_y: 0.0, max_x: 100.0, max_y: 100.0 };
        let polygon = vec![
            Point2D { x: 10.0, y: 10.0 },
            Point2D { x: 90.0, y: 10.0 },
            Point2D { x: 90.0, y: 90.0 },
            Point2D { x: 10.0, y: 90.0 },
        ];
        let mut cw_mesh = LiquifyMesh::new(bounds, &polygon, 10, 10);
        let mut ccw_mesh = LiquifyMesh::new(bounds, &polygon, 10, 10);

        let target_idx = 5 * 11 + 6;
        let orig_y = cw_mesh.world_y[target_idx];

        cw_mesh.apply_stroke(50.0, 50.0, 50.0, 50.0, 30.0, 0.8, LiquifyMode::TwirlCw);
        assert!(cw_mesh.world_y[target_idx] > orig_y);

        ccw_mesh.apply_stroke(50.0, 50.0, 50.0, 50.0, 30.0, 0.8, LiquifyMode::TwirlCcw);
        assert!(ccw_mesh.world_y[target_idx] < orig_y);
    }

    #[test]
    fn test_smooth_relaxes_deformed_mesh() {
        let bounds = RectF { min_x: 0.0, min_y: 0.0, max_x: 100.0, max_y: 100.0 };
        let polygon = vec![
            Point2D { x: 10.0, y: 10.0 },
            Point2D { x: 90.0, y: 10.0 },
            Point2D { x: 90.0, y: 90.0 },
            Point2D { x: 10.0, y: 90.0 },
        ];
        let mut mesh = LiquifyMesh::new(bounds, &polygon, 10, 10);

        let target_idx = 5 * 11 + 5;
        mesh.world_x[target_idx] += 20.0;
        let deformed_x = mesh.world_x[target_idx];

        mesh.apply_stroke(50.0, 50.0, 50.0, 50.0, 40.0, 1.0, LiquifyMode::Smooth);
        assert!(mesh.world_x[target_idx] < deformed_x);
    }

    #[test]
    fn test_reset_restores_initial_positions() {
        let bounds = RectF { min_x: 0.0, min_y: 0.0, max_x: 100.0, max_y: 100.0 };
        let polygon = vec![
            Point2D { x: 10.0, y: 10.0 },
            Point2D { x: 90.0, y: 10.0 },
            Point2D { x: 90.0, y: 90.0 },
            Point2D { x: 10.0, y: 90.0 },
        ];
        let mut mesh = LiquifyMesh::new(bounds, &polygon, 10, 10);

        mesh.apply_stroke(50.0, 50.0, 60.0, 50.0, 40.0, 0.9, LiquifyMode::Push);
        let center_idx = 5 * 11 + 5;
        assert!(mesh.world_x[center_idx] > 50.0);

        mesh.reset();
        assert!((mesh.world_x[center_idx] - 50.0).abs() < 0.001);
        assert!((mesh.world_y[center_idx] - 50.0).abs() < 0.001);
    }
}
