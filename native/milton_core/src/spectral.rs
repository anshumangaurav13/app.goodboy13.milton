//! High-performance Kubelka-Munk optical absorption spectral color mixing engine.
//!
//! Subtractive pigment mixing simulates physical oil and acrylic paint absorption:
//! Yellow + Blue subtractively absorbs red and blue wavelengths to produce vibrant Green,
//! completely eliminating digital RGB muddy gray desaturation.

#[inline]
pub fn rgb_to_absorption(val: u8) -> f32 {
    let normalized = (val as f32 / 255.0).max(0.008);
    -normalized.ln()
}

#[inline]
pub fn absorption_to_rgb(absorption: f32) -> u8 {
    let reflectance = (-absorption).exp().clamp(0.0, 1.0);
    (reflectance * 255.0 + 0.5) as u8
}

/// Subtractive Kubelka-Munk optical absorption mixing for individual RGBA colors.
/// Operates on byte arrays of [R, G, B, A].
#[inline]
pub fn mix_spectral_rgba(src: [u8; 4], dst: [u8; 4], weight: f32) -> [u8; 4] {
    let w = weight.clamp(0.0, 1.0);
    let src_a = src[3] as f32 / 255.0;
    let dst_a = dst[3] as f32 / 255.0;

    if dst_a <= 0.002 {
        let out_a = (src_a * w * 255.0 + 0.5) as u8;
        return [src[0], src[1], src[2], out_a];
    }
    if src_a * w <= 0.002 {
        return dst;
    }

    // Convert destination and source RGB to optical absorption densities
    let abs_dst_r = rgb_to_absorption(dst[0]);
    let abs_dst_g = rgb_to_absorption(dst[1]);
    let abs_dst_b = rgb_to_absorption(dst[2]);

    let abs_src_r = rgb_to_absorption(src[0]);
    let abs_src_g = rgb_to_absorption(src[1]);
    let abs_src_b = rgb_to_absorption(src[2]);

    // Subtractive absorption blend
    let mix_weight = w * 0.70;
    let mixed_abs_r = abs_dst_r * (1.0 - mix_weight) + abs_src_r * mix_weight;
    let mixed_abs_g = abs_dst_g * (1.0 - mix_weight) + abs_src_g * mix_weight;
    let mixed_abs_b = abs_dst_b * (1.0 - mix_weight) + abs_src_b * mix_weight;

    let out_r = absorption_to_rgb(mixed_abs_r);
    let out_g = absorption_to_rgb(mixed_abs_g);
    let out_b = absorption_to_rgb(mixed_abs_b);

    let out_a_f = dst_a + (src_a * w) * (1.0 - dst_a);
    let out_a = (out_a_f.clamp(0.0, 1.0) * 255.0 + 0.5) as u8;

    [out_r, out_g, out_b, out_a]
}

/// Mixes an entire slice of RGBA pixels with subtractive spectral absorption.
pub fn mix_spectral_pixel_buffer(src: &[u8], dst: &mut [u8], weight: f32) {
    let min_len = src.len().min(dst.len());
    let len = min_len / 4;
    for i in 0..len {
        let off = i * 4;
        let s = [src[off], src[off + 1], src[off + 2], src[off + 3]];
        let d = [dst[off], dst[off + 1], dst[off + 2], dst[off + 3]];
        let mixed = mix_spectral_rgba(s, d, weight);
        dst[off] = mixed[0];
        dst[off + 1] = mixed[1];
        dst[off + 2] = mixed[2];
        dst[off + 3] = mixed[3];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_yellow_and_blue_mixes_into_green() {
        // Pure Yellow (RGB: 255, 230, 0, A: 255)
        let yellow = [255, 230, 0, 255];
        // Rich Blue (RGB: 0, 70, 255, A: 255)
        let blue = [0, 70, 255, 255];

        let mixed = mix_spectral_rgba(yellow, blue, 0.5);

        // In subtractive spectral mixing, yellow (absorbs blue) + blue (absorbs red) -> Green
        // Therefore, Green channel must be significantly greater than Red and Blue!
        assert!(mixed[1] > mixed[0], "Green ({}) must be greater than Red ({})", mixed[1], mixed[0]);
        assert!(mixed[1] > mixed[2], "Green ({}) must be greater than Blue ({})", mixed[1], mixed[2]);
        assert!(mixed[1] > 100, "Green channel should be vibrant, got {}", mixed[1]);
    }

    #[test]
    fn test_identical_colors_mix_to_same_color() {
        let orange = [255, 128, 30, 255];
        let mixed = mix_spectral_rgba(orange, orange, 0.5);
        assert!((mixed[0] as i32 - orange[0] as i32).abs() <= 2);
        assert!((mixed[1] as i32 - orange[1] as i32).abs() <= 2);
        assert!((mixed[2] as i32 - orange[2] as i32).abs() <= 2);
    }
}
