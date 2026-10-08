use std::fs::File;
use std::io::BufWriter;
use std::path::Path;

/// High-performance vertical pixel flip for OpenGL coordinate system (bottom-to-top -> top-to-bottom).
pub fn flip_pixels_vertically(src: &[u8], width: usize, height: usize, dst: &mut [u8]) {
    let stride = width * 4;
    assert!(src.len() >= stride * height);
    assert!(dst.len() >= stride * height);

    for row in 0..height {
        let src_offset = (height - 1 - row) * stride;
        let dst_offset = row * stride;
        dst[dst_offset..dst_offset + stride].copy_from_slice(&src[src_offset..src_offset + stride]);
    }
}

/// Blends a 512x512 tile directly into an off-heap canvas buffer using standard Porter-Duff Over.
pub fn composite_tile_into_canvas(
    canvas_buf: &mut [u8],
    canvas_w: usize,
    canvas_h: usize,
    tile_buf: &[u8],
    tile_dest_x: i32,
    tile_dest_y: i32,
    layer_opacity: f32,
) {
    let tile_size = 512usize;
    let opacity_factor = (layer_opacity.clamp(0.0, 1.0) * 255.0) as u32;
    if opacity_factor == 0 {
        return;
    }

    let min_x = tile_dest_x.max(0) as usize;
    let max_x = (tile_dest_x + tile_size as i32).min(canvas_w as i32).max(0) as usize;
    let min_y = tile_dest_y.max(0) as usize;
    let max_y = (tile_dest_y + tile_size as i32).min(canvas_h as i32).max(0) as usize;

    if min_x >= max_x || min_y >= max_y {
        return;
    }

    for cy in min_y..max_y {
        let ty = (cy as i32 - tile_dest_y) as usize;
        let canvas_row_start = (cy * canvas_w + min_x) * 4;
        let tile_row_start = (ty * tile_size + (min_x as i32 - tile_dest_x) as usize) * 4;
        let row_pixel_count = max_x - min_x;

        for p in 0..row_pixel_count {
            let c_idx = canvas_row_start + p * 4;
            let t_idx = tile_row_start + p * 4;

            let sa = (tile_buf[t_idx + 3] as u32 * opacity_factor) / 255;
            if sa == 0 {
                continue;
            }

            if sa >= 255 {
                canvas_buf[c_idx] = tile_buf[t_idx];
                canvas_buf[c_idx + 1] = tile_buf[t_idx + 1];
                canvas_buf[c_idx + 2] = tile_buf[t_idx + 2];
                canvas_buf[c_idx + 3] = 255;
            } else {
                let da = canvas_buf[c_idx + 3] as u32;
                let inv_sa = 255 - sa;
                let out_a = sa + (da * inv_sa) / 255;

                if out_a > 0 {
                    let sr = tile_buf[t_idx] as u32;
                    let sg = tile_buf[t_idx + 1] as u32;
                    let sb = tile_buf[t_idx + 2] as u32;

                    let dr = canvas_buf[c_idx] as u32;
                    let dg = canvas_buf[c_idx + 1] as u32;
                    let db = canvas_buf[c_idx + 2] as u32;

                    canvas_buf[c_idx] = ((sr * sa + dr * da * inv_sa / 255) / out_a).min(255) as u8;
                    canvas_buf[c_idx + 1] = ((sg * sa + dg * da * inv_sa / 255) / out_a).min(255) as u8;
                    canvas_buf[c_idx + 2] = ((sb * sa + db * da * inv_sa / 255) / out_a).min(255) as u8;
                    canvas_buf[c_idx + 3] = out_a.min(255) as u8;
                }
            }
        }
    }
}

/// Encodes an RGBA pixel buffer directly to a PNG file using streaming output.
pub fn write_png_file(
    width: u32,
    height: u32,
    rgba_pixels: &[u8],
    out_file_path: &str,
) -> Result<(), String> {
    let path = Path::new(out_file_path);
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }

    let file = File::create(path).map_err(|e| format!("Cannot create output PNG file: {e}"))?;
    let w = BufWriter::with_capacity(128 * 1024, file);

    let mut encoder = png::Encoder::new(w, width, height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    encoder.set_compression(png::Compression::Fast);

    let mut writer = encoder.write_header().map_err(|e| format!("PNG header write error: {e}"))?;
    writer.write_image_data(rgba_pixels).map_err(|e| format!("PNG image data write error: {e}"))?;

    Ok(())
}
