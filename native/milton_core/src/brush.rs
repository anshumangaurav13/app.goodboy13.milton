//! High-performance native brush engine and stroke interpolation for Milton.
//!
//! Provides sub-pixel equidistant dab spacing, rate-invariant lazy brush stabilization,
//! and cubic Bezier pressure/opacity response curves with Newton-Raphson solvers.

use std::collections::HashMap;

pub const STRIDE_FLOATS: usize = 10;
pub const STRIDE_BYTES: usize = STRIDE_FLOATS * 4;

/// Cubic Bezier response curve for pressure, opacity, and tilt dynamics.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BezierCurve {
    pub cp1x: f32,
    pub cp1y: f32,
    pub cp2x: f32,
    pub cp2y: f32,
    pub min_percent: f32,
    pub max_percent: f32,
}

impl Default for BezierCurve {
    fn default() -> Self {
        Self {
            cp1x: 0.85,
            cp1y: 0.52,
            cp2x: 0.58,
            cp2y: 0.77,
            min_percent: 0.0,
            max_percent: 1.0,
        }
    }
}

impl BezierCurve {
    pub fn new(cp1x: f32, cp1y: f32, cp2x: f32, cp2y: f32, min_percent: f32, max_percent: f32) -> Self {
        Self {
            cp1x,
            cp1y,
            cp2x,
            cp2y,
            min_percent: min_percent.clamp(0.0, 1.0),
            max_percent: max_percent.clamp(0.0, 1.0),
        }
    }

    /// Maps raw pressure [0.0, 1.0] through the cubic Bezier curve defined by (0,0), cp1, cp2, (1,1)
    /// into a normalized [0.0, 1.0] range without applying min_percent / max_percent scaling.
    pub fn solve_normalized_y(&self, raw_pressure: f32) -> f32 {
        let clamped = raw_pressure.clamp(0.0, 1.0);
        if clamped <= 0.0 {
            return 0.0;
        }
        if clamped >= 1.0 {
            return 1.0;
        }

        let mut t = clamped;
        for _ in 0..8 {
            let one_minus_t = 1.0 - t;
            let current_x = 3.0 * one_minus_t * one_minus_t * t * self.cp1x
                + 3.0 * one_minus_t * t * t * self.cp2x
                + t * t * t;
            let error = current_x - clamped;
            if error.abs() < 1e-4 {
                break;
            }
            let dxdt = 3.0 * one_minus_t * one_minus_t * self.cp1x
                + 6.0 * one_minus_t * t * (self.cp2x - self.cp1x)
                + 3.0 * t * t * (1.0 - self.cp2x);
            if dxdt.abs() < 1e-5 {
                break;
            }
            t -= error / dxdt;
            t = t.clamp(0.0, 1.0);
        }

        let one_minus_t = 1.0 - t;
        let normalized_y = 3.0 * one_minus_t * one_minus_t * t * self.cp1y
            + 3.0 * one_minus_t * t * t * self.cp2y
            + t * t * t;
        normalized_y.clamp(0.0, 1.0)
    }

    /// Maps raw pressure [0.0, 1.0] through the cubic Bezier curve, scaled between [min_percent, max_percent].
    pub fn solve_y(&self, raw_pressure: f32) -> f32 {
        let min_p = self.min_percent.clamp(0.0, 1.0);
        let max_p = self.max_percent.clamp(0.0, 1.0);
        let clamped_y = self.solve_normalized_y(raw_pressure);
        (min_p + (max_p - min_p) * clamped_y).clamp(0.0, 1.0)
    }
}

#[derive(Clone, Debug)]
pub struct BrushProperties {
    pub size: f32,
    pub opacity: f32,
    pub color_rgb: i32,
    pub spacing: f32,
    pub hardness: f32,
    pub tilt: f32,
    pub min_radius: f32,
    pub is_eraser: bool,
    pub brush_mode: i32,
    pub stabilizer: f32,
    pub size_curve: BezierCurve,
    pub opacity_curve: BezierCurve,
    pub tilt_curve: BezierCurve,
}

impl Default for BrushProperties {
    fn default() -> Self {
        Self {
            size: 6.0,
            opacity: 1.0,
            color_rgb: 0xFF1A1A1A_u32 as i32,
            spacing: 0.12,
            hardness: 0.85,
            tilt: 0.0,
            min_radius: 1.2,
            is_eraser: false,
            brush_mode: 0,
            stabilizer: 0.0,
            size_curve: BezierCurve::default(),
            opacity_curve: BezierCurve::default(),
            tilt_curve: BezierCurve::default(),
        }
    }
}

pub struct BrushEngine {
    pub properties: BrushProperties,
    last_x: f32,
    last_y: f32,
    raw_last_x: f32,
    raw_last_y: f32,
    last_event_time_millis: i64,
    last_size_p: f32,
    last_norm_opacity_y: f32,
    last_raw_p: f32,
    last_tilt: f32,
    last_tilt_scale: f32,
    distance_from_last_dab: f32,
    is_stroke_active: bool,
    current_stroke_id: i64,
}

impl BrushEngine {
    pub fn new(properties: BrushProperties) -> Self {
        Self {
            properties,
            last_x: 0.0,
            last_y: 0.0,
            raw_last_x: 0.0,
            raw_last_y: 0.0,
            last_event_time_millis: -1,
            last_size_p: 0.5,
            last_norm_opacity_y: 1.0,
            last_raw_p: 0.5,
            last_tilt: 0.0,
            last_tilt_scale: 1.0,
            distance_from_last_dab: 0.0,
            is_stroke_active: false,
            current_stroke_id: 0,
        }
    }

    #[inline]
    fn compute_alpha(&self, norm_y: f32) -> f32 {
        let max_alpha = self.properties.opacity.clamp(0.001, 1.0);
        let min_alpha = self.properties.opacity_curve.min_percent.clamp(0.0, 1.0).min(max_alpha);
        (min_alpha + (max_alpha - min_alpha) * norm_y).clamp(0.001, 1.0)
    }

    #[inline]
    pub fn compute_tilt_scale(&self, tilt: f32) -> f32 {
        if self.properties.brush_mode_is_tilt() && self.properties.tilt > 0.001 {
            let tilt_response = self.properties.tilt_curve.solve_y(tilt.clamp(0.0, 1.0));
            1.0 + tilt_response * (self.properties.tilt * 3.5)
        } else {
            1.0
        }
    }

    #[inline]
    fn push_dab_to_slice(
        out_slice: &mut [f32],
        offset: usize,
        x: f32,
        y: f32,
        radius: f32,
        alpha: f32,
        color_rgb: i32,
        hardness: f32,
        brush_mode: i32,
        pressure: f32,
        tilt: f32,
        is_eraser: bool,
    ) {
        out_slice[offset] = x;
        out_slice[offset + 1] = y;
        out_slice[offset + 2] = radius;
        out_slice[offset + 3] = alpha;
        out_slice[offset + 4] = f32::from_bits(color_rgb as u32);
        out_slice[offset + 5] = hardness;
        out_slice[offset + 6] = f32::from_bits(brush_mode as u32);
        out_slice[offset + 7] = pressure;
        out_slice[offset + 8] = tilt;
        out_slice[offset + 9] = f32::from_bits(if is_eraser { 1u32 } else { 0u32 });
    }

    /// Begins a new stroke and outputs the initial anchor dab into the circular ring buffer.
    pub fn start_stroke_ring(
        &mut self,
        world_x: f32,
        world_y: f32,
        pressure: f32,
        event_time_millis: i64,
        tilt: f32,
        base_slice: &mut [f32],
        head_index: u64,
        capacity: usize,
    ) -> usize {
        self.is_stroke_active = true;
        self.current_stroke_id += 1;
        self.last_x = world_x;
        self.last_y = world_y;
        self.raw_last_x = world_x;
        self.raw_last_y = world_y;
        self.last_event_time_millis = event_time_millis;

        let clamped_p = if pressure <= 0.001 { 0.05 } else { pressure.clamp(0.01, 1.0) };
        self.last_raw_p = clamped_p;
        self.last_size_p = self.properties.size_curve.solve_y(clamped_p);
        self.last_norm_opacity_y = self.properties.opacity_curve.solve_normalized_y(clamped_p);
        self.distance_from_last_dab = 0.0;

        let clamped_tilt = tilt.clamp(0.0, 1.0);
        self.last_tilt = clamped_tilt;
        self.last_tilt_scale = self.compute_tilt_scale(clamped_tilt);

        let radius = (self.properties.min_radius).max((self.properties.size * 0.5) * self.last_size_p * self.last_tilt_scale);
        let dab_alpha = self.compute_alpha(self.last_norm_opacity_y);

        if capacity == 0 {
            return 0;
        }

        let slot = (head_index as usize) % capacity;
        let offset = slot * STRIDE_FLOATS;
        if offset + STRIDE_FLOATS <= base_slice.len() {
            Self::push_dab_to_slice(
                base_slice,
                offset,
                world_x,
                world_y,
                radius,
                dab_alpha,
                self.properties.color_rgb,
                self.properties.hardness,
                self.properties.brush_mode,
                self.last_size_p,
                clamped_tilt,
                self.properties.is_eraser,
            );
            1
        } else {
            0
        }
    }

    /// Begins a new stroke and outputs the initial anchor dab into a linear slice.
    pub fn start_stroke(
        &mut self,
        world_x: f32,
        world_y: f32,
        pressure: f32,
        event_time_millis: i64,
        tilt: f32,
        out_slice: &mut [f32],
    ) -> usize {
        let cap = out_slice.len() / STRIDE_FLOATS;
        self.start_stroke_ring(world_x, world_y, pressure, event_time_millis, tilt, out_slice, 0, cap)
    }

    /// Adds a continuous stylus motion point with rate-invariant stabilization
    /// and equidistant sub-pixel dab interpolation into the circular ring buffer.
    pub fn add_point_ring(
        &mut self,
        world_x: f32,
        world_y: f32,
        pressure: f32,
        event_time_millis: i64,
        tilt: f32,
        base_slice: &mut [f32],
        head_index: u64,
        capacity: usize,
    ) -> usize {
        if !self.is_stroke_active {
            return self.start_stroke_ring(world_x, world_y, pressure, event_time_millis, tilt, base_slice, head_index, capacity);
        }
        if capacity == 0 {
            return 0;
        }

        self.raw_last_x = world_x;
        self.raw_last_y = world_y;

        let (target_x, target_y) = if self.properties.stabilizer > 0.001 {
            let stab = self.properties.stabilizer.clamp(0.001, 1.0);
            let tau = (stab as f64).powf(1.25) * 0.18;

            let dt_seconds = if self.last_event_time_millis >= 0 && event_time_millis > self.last_event_time_millis {
                let dt = (event_time_millis - self.last_event_time_millis) as f64 / 1000.0;
                dt.clamp(0.001, 0.1)
            } else if self.last_event_time_millis >= 0 && event_time_millis == self.last_event_time_millis {
                0.004
            } else {
                0.0083
            };

            if event_time_millis >= 0 {
                self.last_event_time_millis = event_time_millis;
            }

            let weight = (1.0 - (-dt_seconds / tau).exp()).clamp(0.005, 1.0) as f32;
            (
                self.last_x + (world_x - self.last_x) * weight,
                self.last_y + (world_y - self.last_y) * weight,
            )
        } else {
            if event_time_millis >= 0 {
                self.last_event_time_millis = event_time_millis;
            }
            (world_x, world_y)
        };

        let raw_p = if pressure <= 0.001 { self.last_raw_p } else { pressure.clamp(0.01, 1.0) };
        let current_size_p = self.properties.size_curve.solve_y(raw_p);
        let current_norm_opacity_y = self.properties.opacity_curve.solve_normalized_y(raw_p);
        let prev_size_p = self.last_size_p;
        let prev_norm_opacity_y = self.last_norm_opacity_y;
        self.last_raw_p = raw_p;
        self.last_size_p = current_size_p;
        self.last_norm_opacity_y = current_norm_opacity_y;

        let dx = target_x - self.last_x;
        let dy = target_y - self.last_y;
        let segment_dist = dx.hypot(dy);

        if segment_dist < 0.001 {
            return 0;
        }

        let clamped_tilt = tilt.clamp(0.0, 1.0);
        let current_tilt_scale = self.compute_tilt_scale(clamped_tilt);
        let prev_tilt_scale = self.last_tilt_scale;
        self.last_tilt = clamped_tilt;
        self.last_tilt_scale = current_tilt_scale;

        let avg_size_p = (prev_size_p + current_size_p) * 0.5;
        let avg_tilt_scale = (prev_tilt_scale + current_tilt_scale) * 0.5;
        let radius = (self.properties.min_radius).max((self.properties.size * 0.5) * avg_size_p * avg_tilt_scale);
        let step_size = 0.8_f32.max(radius * self.properties.spacing * 2.0);

        let mut d = step_size - self.distance_from_last_dab;
        let mut last_placed_d = -1.0_f32;
        let mut dabs_written = 0;

        while d <= segment_dist && dabs_written < capacity {
            let t = (d / segment_dist).clamp(0.0, 1.0);
            let ix = self.last_x + dx * t;
            let iy = self.last_y + dy * t;
            let ip_size = prev_size_p + (current_size_p - prev_size_p) * t;
            let ip_norm_opacity_y = prev_norm_opacity_y + (current_norm_opacity_y - prev_norm_opacity_y) * t;
            let ip_tilt_scale = prev_tilt_scale + (current_tilt_scale - prev_tilt_scale) * t;
            let ir = (self.properties.min_radius).max((self.properties.size * 0.5) * ip_size * ip_tilt_scale);
            let dab_alpha = self.compute_alpha(ip_norm_opacity_y);

            if self.properties.brush_mode == 2 && ir >= 3.0 && segment_dist > 0.001 {
                let inv_seg = 1.0 / segment_dist;
                let tan_x = dx * inv_seg;
                let tan_y = dy * inv_seg;
                let norm_x = -tan_y;
                let norm_y = tan_x;

                let bristle_spread = ir * 0.45;
                let filament_r = ir * 0.65;
                let fil_alpha = dab_alpha * 0.75;

                // Center core dab
                let slot0 = (head_index as usize + dabs_written) % capacity;
                let off0 = slot0 * STRIDE_FLOATS;
                if off0 + STRIDE_FLOATS <= base_slice.len() {
                    Self::push_dab_to_slice(
                        base_slice,
                        off0,
                        ix,
                        iy,
                        ir * 0.85,
                        dab_alpha * 0.85,
                        self.properties.color_rgb,
                        self.properties.hardness,
                        self.properties.brush_mode,
                        ip_size,
                        clamped_tilt,
                        self.properties.is_eraser,
                    );
                    dabs_written += 1;
                }

                // Left bristle filament
                if dabs_written < capacity {
                    let slot1 = (head_index as usize + dabs_written) % capacity;
                    let off1 = slot1 * STRIDE_FLOATS;
                    if off1 + STRIDE_FLOATS <= base_slice.len() {
                        let lx = ix + norm_x * bristle_spread;
                        let ly = iy + norm_y * bristle_spread;
                        Self::push_dab_to_slice(
                            base_slice,
                            off1,
                            lx,
                            ly,
                            filament_r,
                            fil_alpha,
                            self.properties.color_rgb,
                            self.properties.hardness,
                            self.properties.brush_mode,
                            ip_size,
                            clamped_tilt,
                            self.properties.is_eraser,
                        );
                        dabs_written += 1;
                    }
                }

                // Right bristle filament
                if dabs_written < capacity {
                    let slot2 = (head_index as usize + dabs_written) % capacity;
                    let off2 = slot2 * STRIDE_FLOATS;
                    if off2 + STRIDE_FLOATS <= base_slice.len() {
                        let rx = ix - norm_x * bristle_spread;
                        let ry = iy - norm_y * bristle_spread;
                        Self::push_dab_to_slice(
                            base_slice,
                            off2,
                            rx,
                            ry,
                            filament_r,
                            fil_alpha,
                            self.properties.color_rgb,
                            self.properties.hardness,
                            self.properties.brush_mode,
                            ip_size,
                            clamped_tilt,
                            self.properties.is_eraser,
                        );
                        dabs_written += 1;
                    }
                }

                last_placed_d = d;
            } else {
                let slot = (head_index as usize + dabs_written) % capacity;
                let offset = slot * STRIDE_FLOATS;
                if offset + STRIDE_FLOATS <= base_slice.len() {
                    Self::push_dab_to_slice(
                        base_slice,
                        offset,
                        ix,
                        iy,
                        ir,
                        dab_alpha,
                        self.properties.color_rgb,
                        self.properties.hardness,
                        self.properties.brush_mode,
                        ip_size,
                        clamped_tilt,
                        self.properties.is_eraser,
                    );
                    dabs_written += 1;
                    last_placed_d = d;
                } else {
                    break;
                }
            }
            d += step_size;
        }

        if last_placed_d >= 0.0 {
            self.distance_from_last_dab = segment_dist - last_placed_d;
        } else {
            self.distance_from_last_dab += segment_dist;
        }

        self.last_x = target_x;
        self.last_y = target_y;

        dabs_written
    }

    /// Adds a continuous stylus motion point with rate-invariant stabilization
    /// and equidistant sub-pixel dab interpolation into a linear slice.
    pub fn add_point(
        &mut self,
        world_x: f32,
        world_y: f32,
        pressure: f32,
        event_time_millis: i64,
        tilt: f32,
        out_slice: &mut [f32],
    ) -> usize {
        let cap = out_slice.len() / STRIDE_FLOATS;
        self.add_point_ring(world_x, world_y, pressure, event_time_millis, tilt, out_slice, 0, cap)
    }

    /// Flushes lazy brush trailing distance when stylus lifts into the circular ring buffer.
    pub fn end_stroke_ring(
        &mut self,
        base_slice: &mut [f32],
        head_index: u64,
        capacity: usize,
    ) -> usize {
        if !self.is_stroke_active {
            return 0;
        }
        self.is_stroke_active = false;
        if capacity == 0 {
            return 0;
        }

        let mut dabs_written = 0;
        if self.properties.stabilizer > 0.001 {
            let dx = self.raw_last_x - self.last_x;
            let dy = self.raw_last_y - self.last_y;
            let segment_dist = dx.hypot(dy);
            if segment_dist >= 0.8 {
                let radius = (self.properties.min_radius).max((self.properties.size * 0.5) * self.last_size_p);
                let step_size = 0.8_f32.max(radius * self.properties.spacing * 2.0);
                let mut d = step_size - self.distance_from_last_dab;
                while d <= segment_dist && dabs_written < capacity {
                    let t = (d / segment_dist).clamp(0.0, 1.0);
                    let ix = self.last_x + dx * t;
                    let iy = self.last_y + dy * t;
                    let ir = (self.properties.min_radius).max((self.properties.size * 0.5) * self.last_size_p);
                    let dab_alpha = self.compute_alpha(self.last_norm_opacity_y);

                    let slot = (head_index as usize + dabs_written) % capacity;
                    let offset = slot * STRIDE_FLOATS;
                    if offset + STRIDE_FLOATS <= base_slice.len() {
                        Self::push_dab_to_slice(
                            base_slice,
                            offset,
                            ix,
                            iy,
                            ir,
                            dab_alpha,
                            self.properties.color_rgb,
                            self.properties.hardness,
                            self.properties.brush_mode,
                            self.last_size_p,
                            self.last_tilt,
                            self.properties.is_eraser,
                        );
                        dabs_written += 1;
                    } else {
                        break;
                    }
                    d += step_size;
                }
            }
        }

        self.distance_from_last_dab = 0.0;
        self.last_event_time_millis = 0;
        dabs_written
    }

    /// Flushes lazy brush trailing distance when stylus lifts into a linear slice.
    pub fn end_stroke(&mut self, out_slice: &mut [f32]) -> usize {
        let cap = out_slice.len() / STRIDE_FLOATS;
        self.end_stroke_ring(out_slice, 0, cap)
    }
}

impl BrushProperties {
    #[inline]
    pub fn brush_mode_is_tilt(&self) -> bool {
        self.brush_mode == 1
    }
}

/// Groups ring buffer dabs in range [start_head, start_head + count) into spatial tile buckets.
/// Writes bucket headers [tx, ty, eraser_count, brush_count, eraser_start_index, brush_start_index]
/// into `out_headers` and corresponding dab slot indices into `out_indices`.
/// Returns the number of unique tile buckets generated.
pub fn bin_ring_dabs_to_tiles(
    ring_buf: &[f32],
    capacity: usize,
    start_head: u64,
    count: usize,
    tile_size: f32,
    out_headers: &mut [i32],
    out_indices: &mut [i32],
) -> usize {
    if count == 0 || capacity == 0 || tile_size <= 0.0 {
        return 0;
    }
    let is_pow2 = (capacity & (capacity - 1)) == 0;
    let mask = (capacity - 1) as u64;

    let mut buckets: HashMap<u64, (i32, i32, Vec<i32>, Vec<i32>)> = HashMap::new();

    for i in 0..count {
        let seq = start_head + i as u64;
        let slot = if is_pow2 {
            (seq & mask) as usize
        } else {
            (seq % capacity as u64) as usize
        };

        let offset = slot * STRIDE_FLOATS;
        if offset + STRIDE_FLOATS > ring_buf.len() {
            continue;
        }

        let x = ring_buf[offset + 0];
        let y = ring_buf[offset + 1];
        let radius = ring_buf[offset + 2];
        let is_eraser = ring_buf[offset + 9].to_bits() != 0;

        let reach = radius + 2.0;
        let min_tx = ((x - reach) / tile_size).floor() as i32;
        let max_tx = ((x + reach) / tile_size).floor() as i32;
        let min_ty = ((y - reach) / tile_size).floor() as i32;
        let max_ty = ((y + reach) / tile_size).floor() as i32;

        for ty in min_ty..=max_ty {
            for tx in min_tx..=max_tx {
                let key = ((tx as u32 as u64) << 32) | (ty as u32 as u64);
                let entry = buckets.entry(key).or_insert_with(|| (tx, ty, Vec::new(), Vec::new()));
                if is_eraser {
                    entry.2.push(slot as i32);
                } else {
                    entry.3.push(slot as i32);
                }
            }
        }
    }

    let max_buckets = out_headers.len() / 6;
    let mut bucket_idx = 0;
    let mut current_index_pos = 0;

    for (_, (tx, ty, erasers, brushes)) in buckets {
        if bucket_idx >= max_buckets {
            break;
        }
        if current_index_pos + erasers.len() + brushes.len() > out_indices.len() {
            break;
        }

        let eraser_start = current_index_pos;
        let eraser_count = erasers.len();
        out_indices[eraser_start..eraser_start + eraser_count].copy_from_slice(&erasers);
        current_index_pos += eraser_count;

        let brush_start = current_index_pos;
        let brush_count = brushes.len();
        out_indices[brush_start..brush_start + brush_count].copy_from_slice(&brushes);
        current_index_pos += brush_count;

        let h_offset = bucket_idx * 6;
        out_headers[h_offset + 0] = tx;
        out_headers[h_offset + 1] = ty;
        out_headers[h_offset + 2] = eraser_count as i32;
        out_headers[h_offset + 3] = brush_count as i32;
        out_headers[h_offset + 4] = eraser_start as i32;
        out_headers[h_offset + 5] = brush_start as i32;

        bucket_idx += 1;
    }

    bucket_idx
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_bezier_curve_identity() {
        let curve = BezierCurve::new(0.0, 0.0, 1.0, 1.0, 0.0, 1.0);
        let mid = curve.solve_y(0.5);
        assert!((mid - 0.5).abs() < 1e-3);
    }

    #[test]
    fn test_bezier_curve_min_max_scaling() {
        let curve = BezierCurve::new(0.0, 0.0, 1.0, 1.0, 0.2, 0.8);
        assert!((curve.solve_y(0.0) - 0.2).abs() < 1e-4);
        assert!((curve.solve_y(1.0) - 0.8).abs() < 1e-4);
    }

    #[test]
    fn test_brush_engine_start_stroke() {
        let mut engine = BrushEngine::new(BrushProperties::default());
        let mut out = [0.0f32; STRIDE_FLOATS * 10];
        let count = engine.start_stroke(100.0, 200.0, 0.5, 1000, 0.0, &mut out);
        assert_eq!(count, 1);
        assert_eq!(out[0], 100.0);
        assert_eq!(out[1], 200.0);
        assert!(out[2] > 0.0); // radius
        assert!(out[3] > 0.0); // alpha
    }

    #[test]
    fn test_brush_engine_spacing_interpolation() {
        let mut props = BrushProperties::default();
        props.size = 20.0;
        props.spacing = 0.5;
        let mut engine = BrushEngine::new(props);

        let mut out = [0.0f32; STRIDE_FLOATS * 100];
        let c1 = engine.start_stroke(0.0, 0.0, 0.5, 1000, 0.0, &mut out);
        assert_eq!(c1, 1);

        // Move 50 pixels along X
        let c2 = engine.add_point(50.0, 0.0, 0.5, 1016, 0.0, &mut out);
        assert_eq!(c2, 12, "Expected exactly 12 dabs along 50px segment, got {}", c2);
    }

    #[test]
    fn test_brush_engine_ring_buffer_wrap() {
        let mut props = BrushProperties::default();
        props.size = 20.0;
        props.spacing = 0.5;
        let mut engine = BrushEngine::new(props);

        // Ring buffer with small capacity of 8 slots
        const CAP: usize = 8;
        let mut ring = [0.0f32; STRIDE_FLOATS * CAP];

        // Start stroke at head 6 (slot 6)
        let c1 = engine.start_stroke_ring(0.0, 0.0, 0.5, 1000, 0.0, &mut ring, 6, CAP);
        assert_eq!(c1, 1);
        assert_eq!(ring[6 * STRIDE_FLOATS], 0.0); // placed at slot 6

        // Add point with head 7 (should place dabs wrapping from slot 7 to 0, 1, 2...)
        let c2 = engine.add_point_ring(50.0, 0.0, 0.5, 1016, 0.0, &mut ring, 7, CAP);
        assert!(c2 > 0);
        // Dab at slot 7 (head 7)
        assert!(ring[7 * STRIDE_FLOATS] > 0.0);
        // Dab wrapped to slot 0 (head 8 % 8 = 0)
        assert!(ring[0 * STRIDE_FLOATS] > 0.0);
    }

    #[test]
    fn test_brush_engine_integer_bit_encoding() {
        let mut props = BrushProperties::default();
        props.brush_mode = 1; // Pencil
        props.is_eraser = true; // Eraser
        props.color_rgb = 0x123456;
        let mut engine = BrushEngine::new(props);

        let mut out = [0.0f32; STRIDE_FLOATS * 10];
        let count = engine.start_stroke(10.0, 20.0, 0.5, 1000, 0.0, &mut out);
        assert_eq!(count, 1);

        // Offset 4: color_rgb as u32 bits
        assert_eq!(out[4].to_bits(), 0x123456);
        // Offset 6: brush_mode as u32 bits
        assert_eq!(out[6].to_bits(), 1);
        // Offset 9: is_eraser as u32 bits
        assert_eq!(out[9].to_bits(), 1);
    }

    #[test]
    fn test_bin_ring_dabs_to_tiles() {
        let mut ring = [0.0f32; STRIDE_FLOATS * 10];
        // Slot 0: dab at (10, 10), radius 5 (inside tile 0,0)
        ring[0] = 10.0;
        ring[1] = 10.0;
        ring[2] = 5.0;
        ring[9] = f32::from_bits(0); // brush

        // Slot 1: dab at (600, 10), radius 5 (inside tile 1,0)
        ring[10] = 600.0;
        ring[11] = 10.0;
        ring[12] = 5.0;
        ring[19] = f32::from_bits(1); // eraser

        let mut headers = [0i32; 60];
        let mut indices = [0i32; 60];
        let count = bin_ring_dabs_to_tiles(&ring, 10, 0, 2, 512.0, &mut headers, &mut indices);
        assert_eq!(count, 2);
    }
    #[test]
    fn test_paintbrush_bristle_filament_generation() {
        let mut props = BrushProperties::default();
        props.brush_mode = 2; // Paintbrush
        props.size = 20.0;
        props.spacing = 0.5;
        let mut engine = BrushEngine::new(props);

        let mut out = [0.0f32; STRIDE_FLOATS * 100];
        let c1 = engine.start_stroke(0.0, 0.0, 0.5, 1000, 0.0, &mut out);
        assert_eq!(c1, 1);

        let c2 = engine.add_point(50.0, 0.0, 0.5, 1016, 0.0, &mut out);
        assert_eq!(c2, 12 * 3, "Expected 3 filaments per dab step for paintbrush");
    }
}
