// native/milton_core/src/viewport.rs
//! High-performance 2D infinite camera and viewport transformation engine.
//!
//! Provides zero-allocation, SIMD-friendly coordinate transformations between
//! screen space (physical touch/display coordinates) and infinite world space
//! (canvas canvas coordinates), 2D affine gestures, fling dynamics, and 4x4 MVP
//! projection matrices for OpenGL ES 3.0.

use std::f32::consts::PI;

pub const MIN_ZOOM: f32 = 0.02;
pub const MAX_ZOOM: f32 = 50.0;
pub const MIN_FLING_VELOCITY: f32 = 35.0;

/// 2D Infinite Canvas Camera Viewport.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Viewport {
    pub pan_x: f32,
    pub pan_y: f32,
    pub zoom: f32,
    pub rotation_degrees: f32,
    pub is_flipped_horizontally: bool,
    pub screen_width: f32,
    pub screen_height: f32,
}

impl Default for Viewport {
    fn default() -> Self {
        Self {
            pan_x: 0.0,
            pan_y: 0.0,
            zoom: 0.5,
            rotation_degrees: 0.0,
            is_flipped_horizontally: false,
            screen_width: 1.0,
            screen_height: 1.0,
        }
    }
}

impl Viewport {
    pub fn new(screen_width: f32, screen_height: f32) -> Self {
        let sw = screen_width.max(1.0);
        let sh = screen_height.max(1.0);
        Self {
            pan_x: sw * 0.5,
            pan_y: sh * 0.5,
            zoom: 0.5,
            rotation_degrees: 0.0,
            is_flipped_horizontally: false,
            screen_width: sw,
            screen_height: sh,
        }
    }

    #[inline]
    pub fn update_screen_size(&mut self, w: f32, h: f32) {
        self.screen_width = w.max(1.0);
        self.screen_height = h.max(1.0);
    }

    #[inline]
    pub fn set_zoom(&mut self, z: f32) {
        self.zoom = z.clamp(MIN_ZOOM, MAX_ZOOM);
    }

    #[inline]
    pub fn reset(&mut self) {
        self.pan_x = self.screen_width * 0.5;
        self.pan_y = self.screen_height * 0.5;
        self.zoom = 0.5;
        self.rotation_degrees = 0.0;
        self.is_flipped_horizontally = false;
    }

    /// Converts a screen coordinate `(screen_x, screen_y)` into infinite world space.
    #[inline]
    pub fn screen_to_world(&self, screen_x: f32, screen_y: f32) -> (f32, f32) {
        let sx = if self.is_flipped_horizontally {
            self.screen_width - screen_x
        } else {
            screen_x
        };
        let dx = (sx - self.pan_x) / self.zoom;
        let dy = (screen_y - self.pan_y) / self.zoom;

        if self.rotation_degrees == 0.0 {
            (dx, dy)
        } else {
            let rad = (-self.rotation_degrees) * (PI / 180.0);
            let c = rad.cos();
            let s = rad.sin();
            let wx = dx * c - dy * s;
            let wy = dx * s + dy * c;
            (wx, wy)
        }
    }

    /// Converts an infinite world space coordinate `(world_x, world_y)` into screen coordinate space.
    #[inline]
    pub fn world_to_screen(&self, world_x: f32, world_y: f32) -> (f32, f32) {
        let (sx, sy) = if self.rotation_degrees == 0.0 {
            (world_x * self.zoom + self.pan_x, world_y * self.zoom + self.pan_y)
        } else {
            let rad = self.rotation_degrees * (PI / 180.0);
            let c = rad.cos();
            let s = rad.sin();
            let rx = world_x * c - world_y * s;
            let ry = world_x * s + world_y * c;
            (rx * self.zoom + self.pan_x, ry * self.zoom + self.pan_y)
        };

        let final_sx = if self.is_flipped_horizontally {
            self.screen_width - sx
        } else {
            sx
        };

        (final_sx, sy)
    }

    /// Batch converts screen coordinates `[sx0, sy0, sx1, sy1, ...]` into world coordinates.
    pub fn batch_screen_to_world(&self, in_coords: &[f32], out_coords: &mut [f32]) {
        let len = in_coords.len().min(out_coords.len()) / 2;
        let rot = self.rotation_degrees;
        let rad = (-rot) * (PI / 180.0);
        let c = rad.cos();
        let s = rad.sin();
        let has_rot = rot != 0.0;
        let z = self.zoom;
        let px = self.pan_x;
        let py = self.pan_y;
        let flip = self.is_flipped_horizontally;
        let sw = self.screen_width;

        for i in 0..len {
            let idx = i * 2;
            let raw_sx = in_coords[idx];
            let raw_sy = in_coords[idx + 1];
            let sx = if flip { sw - raw_sx } else { raw_sx };
            let dx = (sx - px) / z;
            let dy = (raw_sy - py) / z;

            if !has_rot {
                out_coords[idx] = dx;
                out_coords[idx + 1] = dy;
            } else {
                out_coords[idx] = dx * c - dy * s;
                out_coords[idx + 1] = dx * s + dy * c;
            }
        }
    }

    /// Batch converts world coordinates `[wx0, wy0, wx1, wy1, ...]` into screen coordinates.
    pub fn batch_world_to_screen(&self, in_coords: &[f32], out_coords: &mut [f32]) {
        let len = in_coords.len().min(out_coords.len()) / 2;
        let rot = self.rotation_degrees;
        let rad = rot * (PI / 180.0);
        let c = rad.cos();
        let s = rad.sin();
        let has_rot = rot != 0.0;
        let z = self.zoom;
        let px = self.pan_x;
        let py = self.pan_y;
        let flip = self.is_flipped_horizontally;
        let sw = self.screen_width;

        for i in 0..len {
            let idx = i * 2;
            let wx = in_coords[idx];
            let wy = in_coords[idx + 1];

            let (sx, sy) = if !has_rot {
                (wx * z + px, wy * z + py)
            } else {
                let rx = wx * c - wy * s;
                let ry = wx * s + wy * c;
                (rx * z + px, ry * z + py)
            };

            out_coords[idx] = if flip { sw - sx } else { sx };
            out_coords[idx + 1] = sy;
        }
    }

    /// Computes the axis-aligned bounding box of world coordinates visible in the current viewport:
    /// Returns `[min_x, min_y, max_x, max_y]`.
    pub fn get_visible_world_bounds(&self) -> [f32; 4] {
        let w = self.screen_width;
        let h = self.screen_height;

        let (x0, y0) = self.screen_to_world(0.0, 0.0);
        let (x1, y1) = self.screen_to_world(w, 0.0);
        let (x2, y2) = self.screen_to_world(w, h);
        let (x3, y3) = self.screen_to_world(0.0, h);

        let min_x = x0.min(x1).min(x2).min(x3);
        let max_x = x0.max(x1).max(x2).max(x3);
        let min_y = y0.min(y1).min(y2).min(y3);
        let max_y = y0.max(y1).max(y2).max(y3);

        [min_x, min_y, max_x, max_y]
    }

    /// Applies pan, zoom, and rotate gestures anchored at a focal screen point.
    pub fn apply_gesture(
        &mut self,
        prev_focal_x: f32,
        prev_focal_y: f32,
        cur_focal_x: f32,
        cur_focal_y: f32,
        zoom_factor: f32,
        angle_delta: f32,
    ) {
        let pfx = if self.is_flipped_horizontally {
            self.screen_width - prev_focal_x
        } else {
            prev_focal_x
        };
        let cfx = if self.is_flipped_horizontally {
            self.screen_width - cur_focal_x
        } else {
            cur_focal_x
        };
        let eff_angle = if self.is_flipped_horizontally {
            -angle_delta
        } else {
            angle_delta
        };

        let old_zoom = self.zoom;
        let new_zoom = (old_zoom * zoom_factor).clamp(MIN_ZOOM, MAX_ZOOM);
        let factor = new_zoom / old_zoom;

        let mut new_rot = self.rotation_degrees + eff_angle;
        while new_rot > 180.0 {
            new_rot -= 360.0;
        }
        while new_rot < -180.0 {
            new_rot += 360.0;
        }

        let mut delta_angle = new_rot - self.rotation_degrees;
        while delta_angle > 180.0 {
            delta_angle -= 360.0;
        }
        while delta_angle < -180.0 {
            delta_angle += 360.0;
        }

        let delta_rad = delta_angle * (PI / 180.0);
        let c = delta_rad.cos();
        let s = delta_rad.sin();

        let vx = pfx - self.pan_x;
        let vy = prev_focal_y - self.pan_y;

        let rx = factor * (vx * c - vy * s);
        let ry = factor * (vx * s + vy * c);

        self.pan_x = cfx - rx;
        self.pan_y = cur_focal_y - ry;
        self.zoom = new_zoom;
        self.rotation_degrees = new_rot;
    }

    /// Advances fling momentum physics by `dt` seconds with exponential decay.
    /// Updates velocities in-place. Returns `(dx, dy, is_stopped)`.
    pub fn step_fling(dt: f32, vx: &mut f32, vy: &mut f32) -> (f32, f32, bool) {
        let clamped_dt = dt.clamp(0.001, 0.05);
        let decay = 0.0001f32.powf(clamped_dt);
        *vx *= decay;
        *vy *= decay;

        if vx.abs() < MIN_FLING_VELOCITY && vy.abs() < MIN_FLING_VELOCITY {
            (0.0, 0.0, true)
        } else {
            let dx = *vx * clamped_dt;
            let dy = *vy * clamped_dt;
            (dx, dy, false)
        }
    }

    /// Computes column-major 4x4 matrix for canvas world to view space:
    /// `world_to_view = Flip * Translate(panX, panY) * Scale(zoom) * Rotate(rotationDegrees)`
    pub fn compute_world_to_view_matrix(&self, out_mat: &mut [f32; 16]) {
        // Identity
        mat4_identity(out_mat);

        if self.is_flipped_horizontally {
            let cx = self.screen_width * 0.5;
            let mut t1 = [0.0f32; 16];
            mat4_translation(cx, 0.0, 0.0, &mut t1);
            let mut s1 = [0.0f32; 16];
            mat4_scale(-1.0, 1.0, 1.0, &mut s1);
            let mut t2 = [0.0f32; 16];
            mat4_translation(-cx, 0.0, 0.0, &mut t2);

            let mut tmp = [0.0f32; 16];
            mat4_multiply(&t1, &s1, &mut tmp);
            let mut flip_mat = [0.0f32; 16];
            mat4_multiply(&tmp, &t2, &mut flip_mat);

            mat4_copy(&flip_mat, out_mat);
        }

        // Translate(pan_x, pan_y)
        let mut trans = [0.0f32; 16];
        mat4_translation(self.pan_x, self.pan_y, 0.0, &mut trans);
        let mut tmp = [0.0f32; 16];
        mat4_multiply(out_mat, &trans, &mut tmp);
        mat4_copy(&tmp, out_mat);

        // Scale(zoom, zoom, 1)
        let mut scale = [0.0f32; 16];
        mat4_scale(self.zoom, self.zoom, 1.0, &mut scale);
        mat4_multiply(out_mat, &scale, &mut tmp);
        mat4_copy(&tmp, out_mat);

        // Rotate(rotation_degrees, 0, 0, 1)
        if self.rotation_degrees != 0.0 {
            let mut rot = [0.0f32; 16];
            mat4_rotation_z(self.rotation_degrees, &mut rot);
            mat4_multiply(out_mat, &rot, &mut tmp);
            mat4_copy(&tmp, out_mat);
        }
    }

    /// Computes full MVP matrix mapping world coordinates directly to target buffer NDC:
    /// `mvp = screen_ortho * transform * world_to_view`
    pub fn compute_final_mvp_matrix(
        &self,
        buffer_width: f32,
        buffer_height: f32,
        transform: &[f32; 16],
        out_mvp: &mut [f32; 16],
    ) {
        let mut ortho = [0.0f32; 16];
        mat4_ortho(0.0, buffer_width, 0.0, buffer_height, -1.0, 1.0, &mut ortho);

        let mut screen_proj = [0.0f32; 16];
        mat4_multiply(&ortho, transform, &mut screen_proj);

        let mut world_to_view = [0.0f32; 16];
        self.compute_world_to_view_matrix(&mut world_to_view);

        mat4_multiply(&screen_proj, &world_to_view, out_mvp);
    }
}

/// Result of 2-finger gesture calculation.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TwoFingerGestureResult {
    pub focal_x: f32,
    pub focal_y: f32,
    pub span: f32,
    pub angle_deg: f32,
    pub zoom_factor: f32,
    pub applied_angle_deg: f32,
    pub zoom_delta: f32,
    pub angle_delta_deg: f32,
}

/// Pure mathematical 2-finger gesture arbitration (focal point, pinch span, rotation angle delta).
pub fn compute_two_finger_gesture(
    p0x: f32,
    p0y: f32,
    p1x: f32,
    p1y: f32,
    prev_span: f32,
    prev_angle: f32,
    is_zoom_locked: bool,
    is_rotation_locked: bool,
) -> TwoFingerGestureResult {
    let focal_x = (p0x + p1x) * 0.5;
    let focal_y = (p0y + p1y) * 0.5;
    let dx = p1x - p0x;
    let dy = p1y - p0y;
    let span = (dx * dx + dy * dy).sqrt();
    let angle_deg = dy.atan2(dx).to_degrees();

    let zoom_delta = if prev_span > 10.0 { (span - prev_span).abs() } else { 0.0 };
    let mut angle_delta = angle_deg - prev_angle;
    while angle_delta > 180.0 {
        angle_delta -= 360.0;
    }
    while angle_delta < -180.0 {
        angle_delta += 360.0;
    }

    let zoom_factor = if !is_zoom_locked && prev_span > 10.0 && span > 10.0 {
        span / prev_span
    } else {
        1.0
    };

    let applied_angle_deg = if !is_rotation_locked {
        angle_delta
    } else {
        0.0
    };

    TwoFingerGestureResult {
        focal_x,
        focal_y,
        span,
        angle_deg,
        zoom_factor,
        applied_angle_deg,
        zoom_delta,
        angle_delta_deg: angle_delta,
    }
}

// ---------------------------------------------------------------------------
// Matrix Utilities (Column-Major 4x4)
// ---------------------------------------------------------------------------

#[inline]
fn mat4_identity(m: &mut [f32; 16]) {
    m.fill(0.0);
    m[0] = 1.0;
    m[5] = 1.0;
    m[10] = 1.0;
    m[15] = 1.0;
}

#[inline]
fn mat4_copy(src: &[f32; 16], dst: &mut [f32; 16]) {
    dst.copy_from_slice(src);
}

#[inline]
fn mat4_translation(tx: f32, ty: f32, tz: f32, m: &mut [f32; 16]) {
    mat4_identity(m);
    m[12] = tx;
    m[13] = ty;
    m[14] = tz;
}

#[inline]
fn mat4_scale(sx: f32, sy: f32, sz: f32, m: &mut [f32; 16]) {
    mat4_identity(m);
    m[0] = sx;
    m[5] = sy;
    m[10] = sz;
}

#[inline]
fn mat4_rotation_z(degrees: f32, m: &mut [f32; 16]) {
    mat4_identity(m);
    let rad = degrees * (PI / 180.0);
    let c = rad.cos();
    let s = rad.sin();
    m[0] = c;
    m[1] = s;
    m[4] = -s;
    m[5] = c;
}

#[inline]
fn mat4_ortho(
    left: f32,
    right: f32,
    bottom: f32,
    top: f32,
    near: f32,
    far: f32,
    m: &mut [f32; 16],
) {
    m.fill(0.0);
    m[0] = 2.0 / (right - left);
    m[5] = 2.0 / (top - bottom);
    m[10] = -2.0 / (far - near);
    m[12] = -(right + left) / (right - left);
    m[13] = -(top + bottom) / (top - bottom);
    m[14] = -(far + near) / (far - near);
    m[15] = 1.0;
}

#[inline]
pub fn mat4_multiply(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
    for col in 0..4 {
        for row in 0..4 {
            out[row + col * 4] = a[row] * b[col * 4]
                + a[row + 4] * b[col * 4 + 1]
                + a[row + 8] * b[col * 4 + 2]
                + a[row + 12] * b[col * 4 + 3];
        }
    }
}

// ---------------------------------------------------------------------------
// Unit Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_screen_to_world_and_back() {
        let mut vp = Viewport::new(1920.0, 1080.0);
        vp.pan_x = 450.0;
        vp.pan_y = 300.0;
        vp.zoom = 1.5;
        vp.rotation_degrees = 45.0;

        let screen_coords = [(100.0, 200.0), (960.0, 540.0), (0.0, 0.0), (1920.0, 1080.0)];

        for (sx, sy) in screen_coords {
            let (wx, wy) = vp.screen_to_world(sx, sy);
            let (back_sx, back_sy) = vp.world_to_screen(wx, wy);

            assert!((sx - back_sx).abs() < 1e-4, "Mismatch in SX: {} vs {}", sx, back_sx);
            assert!((sy - back_sy).abs() < 1e-4, "Mismatch in SY: {} vs {}", sy, back_sy);
        }
    }

    #[test]
    fn test_flipped_screen_to_world_and_back() {
        let mut vp = Viewport::new(1920.0, 1080.0);
        vp.pan_x = 200.0;
        vp.pan_y = -150.0;
        vp.zoom = 0.75;
        vp.rotation_degrees = -30.0;
        vp.is_flipped_horizontally = true;

        let (wx, wy) = vp.screen_to_world(500.0, 600.0);
        let (sx, sy) = vp.world_to_screen(wx, wy);

        println!("Diff sx: {}, sy: {}", (sx - 500.0).abs(), (sy - 600.0).abs());
        assert!((sx - 500.0).abs() < 1e-3);
        assert!((sy - 600.0).abs() < 1e-3);
    }

    #[test]
    fn test_batch_transforms() {
        let mut vp = Viewport::new(1920.0, 1080.0);
        vp.pan_x = 100.0;
        vp.pan_y = 100.0;
        vp.zoom = 2.0;
        vp.rotation_degrees = 90.0;

        let screen_in = [100.0, 200.0, 300.0, 400.0, 500.0, 600.0];
        let mut world_out = [0.0; 6];
        vp.batch_screen_to_world(&screen_in, &mut world_out);

        let mut screen_back = [0.0; 6];
        vp.batch_world_to_screen(&world_out, &mut screen_back);

        for i in 0..6 {
            assert!((screen_in[i] - screen_back[i]).abs() < 1e-4);
        }
    }

    #[test]
    fn test_visible_world_bounds() {
        let mut vp = Viewport::new(1000.0, 1000.0);
        vp.pan_x = 0.0;
        vp.pan_y = 0.0;
        vp.zoom = 1.0;
        vp.rotation_degrees = 0.0;

        let bounds = vp.get_visible_world_bounds();
        assert_eq!(bounds, [0.0, 0.0, 1000.0, 1000.0]);
    }

    #[test]
    fn test_apply_gesture_anchoring() {
        let mut vp = Viewport::new(1000.0, 1000.0);
        vp.pan_x = 500.0;
        vp.pan_y = 500.0;
        vp.zoom = 1.0;

        let focal_x = 500.0;
        let focal_y = 500.0;
        let (world_before_x, world_before_y) = vp.screen_to_world(focal_x, focal_y);

        // Zoom 2x anchored at focal point
        vp.apply_gesture(focal_x, focal_y, focal_x, focal_y, 2.0, 0.0);

        let (world_after_x, world_after_y) = vp.screen_to_world(focal_x, focal_y);
        assert!((world_before_x - world_after_x).abs() < 1e-4);
        assert!((world_before_y - world_after_y).abs() < 1e-4);
    }

    #[test]
    fn test_fling_momentum_decay() {
        let mut vx = 1000.0;
        let mut vy = 500.0;
        let (dx, dy, stopped) = Viewport::step_fling(0.016, &mut vx, &mut vy);
        assert!(!stopped);
        assert!(dx > 0.0 && dy > 0.0);
        assert!(vx < 1000.0 && vy < 500.0);

        // Decay until stopped
        let mut count = 0;
        let mut is_stop = false;
        while !is_stop && count < 1000 {
            let (_, _, stop) = Viewport::step_fling(0.016, &mut vx, &mut vy);
            is_stop = stop;
            count += 1;
        }
        assert!(is_stop);
        assert!(vx.abs() < MIN_FLING_VELOCITY);
    }

    #[test]
    fn test_world_to_view_matrix_matches_world_to_screen() {
        let mut vp = Viewport::new(1920.0, 1080.0);
        vp.pan_x = 350.0;
        vp.pan_y = 250.0;
        vp.zoom = 1.75;
        vp.rotation_degrees = 33.0;

        let mut mat = [0.0; 16];
        vp.compute_world_to_view_matrix(&mut mat);

        let wx = 123.0f32;
        let wy = 456.0f32;
        let (expected_sx, expected_sy) = vp.world_to_screen(wx, wy);

        // Vector multiply (col-major): v' = mat * [wx, wy, 0, 1]
        let mat_sx = mat[0] * wx + mat[4] * wy + mat[12];
        let mat_sy = mat[1] * wx + mat[5] * wy + mat[13];

        assert!((mat_sx - expected_sx).abs() < 1e-3, "mat_sx: {} vs exp: {}", mat_sx, expected_sx);
        assert!((mat_sy - expected_sy).abs() < 1e-3, "mat_sy: {} vs exp: {}", mat_sy, expected_sy);
    }

    #[test]
    fn test_compute_two_finger_gesture() {
        let p0x = 100.0;
        let p0y = 100.0;
        let p1x = 200.0;
        let p1y = 100.0;
        let prev_span = 100.0;
        let prev_angle = 0.0;

        let res = compute_two_finger_gesture(p0x, p0y, p1x, p1y, prev_span, prev_angle, false, false);
        assert!((res.focal_x - 150.0).abs() < 1e-4);
        assert!((res.focal_y - 100.0).abs() < 1e-4);
        assert!((res.span - 100.0).abs() < 1e-4);
        assert!((res.zoom_factor - 1.0).abs() < 1e-4);
        assert!((res.applied_angle_deg - 0.0).abs() < 1e-4);

        // Now test 2x zoom and 90 deg rotation
        let p1x_rot = 100.0;
        let p1y_rot = 300.0; // span = 200, angle = 90
        let res2 = compute_two_finger_gesture(p0x, p0y, p1x_rot, p1y_rot, prev_span, prev_angle, false, false);
        assert!((res2.span - 200.0).abs() < 1e-4);
        assert!((res2.zoom_factor - 2.0).abs() < 1e-4);
        assert!((res2.applied_angle_deg - 90.0).abs() < 1e-4);
    }
}
