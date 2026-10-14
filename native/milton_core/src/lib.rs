pub mod compression;
pub mod archive;
pub mod tile_swap;
pub mod export;
pub mod sub_tile;
pub mod selection;
pub mod constants;

use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray, JIntArray, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, JNI_FALSE, JNI_TRUE};

// ============================================================================
// #1 COMPRESSION & ARCHIVE CODEC JNI BINDINGS
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_compressTile<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JByteArray<'local>,
) -> JByteArray<'local> {
    let input_bytes = match env.convert_byte_array(&input) {
        Ok(b) => b,
        Err(_) => return JByteArray::default(),
    };

    match compression::compress_tile(&input_bytes) {
        Ok(compressed) => env.byte_array_from_slice(&compressed).unwrap_or_default(),
        Err(_) => JByteArray::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_decompressTile<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JByteArray<'local>,
) -> JByteArray<'local> {
    let input_bytes = match env.convert_byte_array(&input) {
        Ok(b) => b,
        Err(_) => return JByteArray::default(),
    };

    match compression::decompress_tile(&input_bytes) {
        Ok(decompressed) => env.byte_array_from_slice(&decompressed).unwrap_or_default(),
        Err(_) => JByteArray::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_compressTileDirect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    direct_buf: JByteBuffer<'local>,
) -> JByteArray<'local> {
    let ptr = match env.get_direct_buffer_address(&direct_buf) {
        Ok(p) => p,
        Err(_) => return JByteArray::default(),
    };
    let cap = match env.get_direct_buffer_capacity(&direct_buf) {
        Ok(c) => c,
        Err(_) => return JByteArray::default(),
    };
    if ptr.is_null() || cap < constants::TILE_BYTES {
        return JByteArray::default();
    }
    let slice = unsafe { std::slice::from_raw_parts(ptr, constants::TILE_BYTES) };
    match compression::compress_tile(slice) {
        Ok(compressed) => env.byte_array_from_slice(&compressed).unwrap_or_default(),
        Err(_) => JByteArray::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_decompressTileDirect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    compressed_bytes: JByteArray<'local>,
    out_direct_buf: JByteBuffer<'local>,
) -> jboolean {
    let compressed = match env.convert_byte_array(&compressed_bytes) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };
    let ptr = match env.get_direct_buffer_address(&out_direct_buf) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };
    let cap = match env.get_direct_buffer_capacity(&out_direct_buf) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };
    if ptr.is_null() || cap < constants::TILE_BYTES {
        return JNI_FALSE;
    }
    let out_slice = unsafe { std::slice::from_raw_parts_mut(ptr, constants::TILE_BYTES) };
    match compression::decompress_tile_to_slice(&compressed, out_slice) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_exportArchive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    manifest_json: JString<'local>,
    tiles_dir: JString<'local>,
    destination_zip: JString<'local>,
) -> jboolean {
    let manifest: String = match env.get_string(&manifest_json) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let tiles: String = match env.get_string(&tiles_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let dest: String = match env.get_string(&destination_zip) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    match archive::export_milton_archive(&manifest, &tiles, &dest) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_importArchive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source_zip: JString<'local>,
    destination_tiles_dir: JString<'local>,
) -> JString<'local> {
    let src: String = match env.get_string(&source_zip) {
        Ok(s) => s.into(),
        Err(_) => return JString::default(),
    };
    let dest: String = match env.get_string(&destination_tiles_dir) {
        Ok(s) => s.into(),
        Err(_) => return JString::default(),
    };

    match archive::import_milton_archive(&src, &dest) {
        Ok(manifest) => env.new_string(manifest).unwrap_or_default(),
        Err(_) => JString::default(),
    }
}

// ============================================================================
// #2 TILE SWAP STORE JNI BINDINGS
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_swapWriteTile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    swap_dir: JString<'local>,
    layer_id: jlong,
    tx: jint,
    ty: jint,
    raw_bytes: JByteArray<'local>,
) -> jboolean {
    let dir: String = match env.get_string(&swap_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let bytes = match env.convert_byte_array(&raw_bytes) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };

    match tile_swap::swap_write(&dir, layer_id, tx, ty, &bytes) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_swapReadTile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    swap_dir: JString<'local>,
    layer_id: jlong,
    tx: jint,
    ty: jint,
    out_bytes: JByteArray<'local>,
) -> jboolean {
    let dir: String = match env.get_string(&swap_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let len = match env.get_array_length(&out_bytes) {
        Ok(l) => l as usize,
        Err(_) => return JNI_FALSE,
    };

    let mut buf = vec![0u8; len];
    match tile_swap::swap_read(&dir, layer_id, tx, ty, &mut buf) {
        Ok(true) => {
            let slice: &[i8] = unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const i8, len) };
            if env.set_byte_array_region(&out_bytes, 0, slice).is_ok() {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        }
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_swapWriteTileDirect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    swap_dir: JString<'local>,
    layer_id: jlong,
    tx: jint,
    ty: jint,
    direct_buf: JByteBuffer<'local>,
) -> jboolean {
    let dir: String = match env.get_string(&swap_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let ptr = match env.get_direct_buffer_address(&direct_buf) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };
    let cap = match env.get_direct_buffer_capacity(&direct_buf) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };
    if ptr.is_null() || cap < constants::TILE_BYTES {
        return JNI_FALSE;
    }
    let slice = unsafe { std::slice::from_raw_parts(ptr, constants::TILE_BYTES) };
    match tile_swap::swap_write(&dir, layer_id, tx, ty, slice) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_swapReadTileDirect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    swap_dir: JString<'local>,
    layer_id: jlong,
    tx: jint,
    ty: jint,
    direct_buf: JByteBuffer<'local>,
) -> jboolean {
    let dir: String = match env.get_string(&swap_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let ptr = match env.get_direct_buffer_address(&direct_buf) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };
    let cap = match env.get_direct_buffer_capacity(&direct_buf) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };
    if ptr.is_null() || cap < constants::TILE_BYTES {
        return JNI_FALSE;
    }
    let slice = unsafe { std::slice::from_raw_parts_mut(ptr, constants::TILE_BYTES) };
    match tile_swap::swap_read(&dir, layer_id, tx, ty, slice) {
        Ok(true) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_swapDeleteTile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    swap_dir: JString<'local>,
    layer_id: jlong,
    tx: jint,
    ty: jint,
) -> jboolean {
    let dir: String = match env.get_string(&swap_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    match tile_swap::swap_delete(&dir, layer_id, tx, ty) {
        Ok(deleted) => if deleted { JNI_TRUE } else { JNI_FALSE },
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_swapClearAll<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    swap_dir: JString<'local>,
) -> jboolean {
    let dir: String = match env.get_string(&swap_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    match tile_swap::swap_clear_all(&dir) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

// ============================================================================
// #3 HIGH-RESOLUTION EXPORT & PIXEL TRANSFORMS JNI BINDINGS
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_flipPixelsVertically<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    src: JByteArray<'local>,
    width: jint,
    height: jint,
    dst: JByteArray<'local>,
) -> jboolean {
    let w = width as usize;
    let h = height as usize;
    let src_bytes = match env.convert_byte_array(&src) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };

    let mut dst_buf = vec![0u8; w * h * 4];
    export::flip_pixels_vertically(&src_bytes, w, h, &mut dst_buf);

    let slice: &[i8] = unsafe { std::slice::from_raw_parts(dst_buf.as_ptr() as *const i8, dst_buf.len()) };
    if env.set_byte_array_region(&dst, 0, slice).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_exportCanvasPng<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    width: jint,
    height: jint,
    rgba_bytes: JByteArray<'local>,
    output_path: JString<'local>,
) -> jboolean {
    let out_file: String = match env.get_string(&output_path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let pixels = match env.convert_byte_array(&rgba_bytes) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };

    match export::write_png_file(width as u32, height as u32, &pixels, &out_file) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

// ============================================================================
// SUB-TILE DIRTY-RECT UNDO/REDO JNI BINDINGS
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_computeDirtyRect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    old_bytes: JByteArray<'local>,
    new_bytes: JByteArray<'local>,
) -> JIntArray<'local> {
    let old_buf = match env.convert_byte_array(&old_bytes) {
        Ok(b) => b,
        Err(_) => return JIntArray::default(),
    };
    let new_buf = match env.convert_byte_array(&new_bytes) {
        Ok(b) => b,
        Err(_) => return JIntArray::default(),
    };

    let rect = match sub_tile::compute_dirty_rect(&old_buf, &new_buf) {
        Some(r) => r,
        None => return JIntArray::default(),
    };

    let int_array = match env.new_int_array(4) {
        Ok(a) => a,
        Err(_) => return JIntArray::default(),
    };

    let rect_slice = [
        rect.min_x as jint,
        rect.min_y as jint,
        rect.width as jint,
        rect.height as jint,
    ];
    let _ = env.set_int_array_region(&int_array, 0, &rect_slice);
    int_array
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_createSubTilePatch<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    tile_buf: JByteArray<'local>,
    min_x: jint,
    min_y: jint,
    width: jint,
    height: jint,
) -> JByteArray<'local> {
    let buf = match env.convert_byte_array(&tile_buf) {
        Ok(b) => b,
        Err(_) => return JByteArray::default(),
    };

    let rect = sub_tile::Rect {
        min_x: min_x as usize,
        min_y: min_y as usize,
        width: width as usize,
        height: height as usize,
    };

    match sub_tile::create_sub_tile_patch(&buf, rect) {
        Ok(compressed) => env.byte_array_from_slice(&compressed).unwrap_or_default(),
        Err(_) => JByteArray::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_applySubTilePatch<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    tile_buf: JByteArray<'local>,
    patch_compressed: JByteArray<'local>,
    min_x: jint,
    min_y: jint,
    width: jint,
    height: jint,
) -> jboolean {
    let mut buf = match env.convert_byte_array(&tile_buf) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };
    let patch = match env.convert_byte_array(&patch_compressed) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };

    let rect = sub_tile::Rect {
        min_x: min_x as usize,
        min_y: min_y as usize,
        width: width as usize,
        height: height as usize,
    };

    match sub_tile::apply_sub_tile_patch(&mut buf, &patch, rect) {
        Ok(_) => {
            let slice: &[i8] = unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const i8, buf.len()) };
            if env.set_byte_array_region(&tile_buf, 0, slice).is_ok() {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_computeDirtyRectDirect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    old_buf_direct: JByteBuffer<'local>,
    new_buf_direct: JByteBuffer<'local>,
) -> JIntArray<'local> {
    let old_ptr = match env.get_direct_buffer_address(&old_buf_direct) {
        Ok(p) => p,
        Err(_) => return JIntArray::default(),
    };
    let old_cap = match env.get_direct_buffer_capacity(&old_buf_direct) {
        Ok(c) => c,
        Err(_) => return JIntArray::default(),
    };
    let new_ptr = match env.get_direct_buffer_address(&new_buf_direct) {
        Ok(p) => p,
        Err(_) => return JIntArray::default(),
    };
    let new_cap = match env.get_direct_buffer_capacity(&new_buf_direct) {
        Ok(c) => c,
        Err(_) => return JIntArray::default(),
    };
    if old_ptr.is_null() || old_cap < constants::TILE_BYTES || new_ptr.is_null() || new_cap < constants::TILE_BYTES {
        return JIntArray::default();
    }
    let old_slice = unsafe { std::slice::from_raw_parts(old_ptr, constants::TILE_BYTES) };
    let new_slice = unsafe { std::slice::from_raw_parts(new_ptr, constants::TILE_BYTES) };

    let rect = match sub_tile::compute_dirty_rect(old_slice, new_slice) {
        Some(r) => r,
        None => return JIntArray::default(),
    };

    let int_array = match env.new_int_array(4) {
        Ok(a) => a,
        Err(_) => return JIntArray::default(),
    };

    let rect_slice = [
        rect.min_x as jint,
        rect.min_y as jint,
        rect.width as jint,
        rect.height as jint,
    ];
    let _ = env.set_int_array_region(&int_array, 0, &rect_slice);
    int_array
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_createSubTilePatchDirect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    tile_buf_direct: JByteBuffer<'local>,
    min_x: jint,
    min_y: jint,
    width: jint,
    height: jint,
) -> JByteArray<'local> {
    let ptr = match env.get_direct_buffer_address(&tile_buf_direct) {
        Ok(p) => p,
        Err(_) => return JByteArray::default(),
    };
    let cap = match env.get_direct_buffer_capacity(&tile_buf_direct) {
        Ok(c) => c,
        Err(_) => return JByteArray::default(),
    };
    if ptr.is_null() || cap < constants::TILE_BYTES {
        return JByteArray::default();
    }
    let slice = unsafe { std::slice::from_raw_parts(ptr, constants::TILE_BYTES) };

    let rect = sub_tile::Rect {
        min_x: min_x as usize,
        min_y: min_y as usize,
        width: width as usize,
        height: height as usize,
    };

    match sub_tile::create_sub_tile_patch(slice, rect) {
        Ok(compressed) => env.byte_array_from_slice(&compressed).unwrap_or_default(),
        Err(_) => JByteArray::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_applySubTilePatchDirect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    tile_buf_direct: JByteBuffer<'local>,
    patch_compressed: JByteArray<'local>,
    min_x: jint,
    min_y: jint,
    width: jint,
    height: jint,
) -> jboolean {
    let ptr = match env.get_direct_buffer_address(&tile_buf_direct) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };
    let cap = match env.get_direct_buffer_capacity(&tile_buf_direct) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };
    if ptr.is_null() || cap < constants::TILE_BYTES {
        return JNI_FALSE;
    }
    let slice = unsafe { std::slice::from_raw_parts_mut(ptr, constants::TILE_BYTES) };

    let patch = match env.convert_byte_array(&patch_compressed) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };

    let rect = sub_tile::Rect {
        min_x: min_x as usize,
        min_y: min_y as usize,
        width: width as usize,
        height: height as usize,
    };

    match sub_tile::apply_sub_tile_patch(slice, &patch, rect) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

// ============================================================================
// #4 LASSO SELECTION & FREE TRANSFORM JNI BINDINGS
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_rasterizePolygonMask<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    points_x: JFloatArray<'local>,
    points_y: JFloatArray<'local>,
    tile_left: jfloat,
    tile_top: jfloat,
) -> JByteArray<'local> {
    let len_x = match env.get_array_length(&points_x) {
        Ok(l) => l as usize,
        Err(_) => return JByteArray::default(),
    };
    let len_y = match env.get_array_length(&points_y) {
        Ok(l) => l as usize,
        Err(_) => return JByteArray::default(),
    };

    if len_x != len_y || len_x < 3 {
        return JByteArray::default();
    }

    let mut xs = vec![0.0f32; len_x];
    let mut ys = vec![0.0f32; len_y];
    if env.get_float_array_region(&points_x, 0, &mut xs).is_err()
        || env.get_float_array_region(&points_y, 0, &mut ys).is_err()
    {
        return JByteArray::default();
    }

    let points: Vec<selection::Point2D> = xs
        .into_iter()
        .zip(ys.into_iter())
        .map(|(x, y)| selection::Point2D { x, y })
        .collect();

    let poly = selection::Polygon::new(points);
    match poly.rasterize_tile_mask(tile_left as f32, tile_top as f32) {
        Some(mask) => env.byte_array_from_slice(&mask).unwrap_or_default(),
        None => JByteArray::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_transformPatchPixels<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    src_rgba: JByteArray<'local>,
    src_w: jint,
    src_h: jint,
    scale_x: jfloat,
    scale_y: jfloat,
    rotation_rad: jfloat,
    pivot_x: jfloat,
    pivot_y: jfloat,
    flip_h: jboolean,
    flip_v: jboolean,
) -> JByteArray<'local> {
    let src = match env.convert_byte_array(&src_rgba) {
        Ok(b) => b,
        Err(_) => return JByteArray::default(),
    };

    match selection::transform_patch(
        &src,
        src_w as usize,
        src_h as usize,
        scale_x as f32,
        scale_y as f32,
        rotation_rad as f32,
        pivot_x as f32,
        pivot_y as f32,
        flip_h == JNI_TRUE,
        flip_v == JNI_TRUE,
    ) {
        Ok((dst_rgba, dst_w, dst_h, min_x, min_y)) => {
            let mut packed = Vec::with_capacity(16 + dst_rgba.len());
            packed.extend_from_slice(&(dst_w as u32).to_be_bytes());
            packed.extend_from_slice(&(dst_h as u32).to_be_bytes());
            packed.extend_from_slice(&min_x.to_bits().to_be_bytes());
            packed.extend_from_slice(&min_y.to_bits().to_be_bytes());
            packed.extend_from_slice(&dst_rgba);
            env.byte_array_from_slice(&packed).unwrap_or_default()
        }
        Err(_) => JByteArray::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_extractAndClearTileSelection<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    tile_rgba: JByteArray<'local>,
    tile_origin_x: jint,
    tile_origin_y: jint,
    tile_mask: JByteArray<'local>,
    patch_rgba: JByteArray<'local>,
    patch_w: jint,
    patch_h: jint,
    patch_origin_x: jint,
    patch_origin_y: jint,
) -> jboolean {
    let mut tile = match env.convert_byte_array(&tile_rgba) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };
    let mask = match env.convert_byte_array(&tile_mask) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };
    let mut patch = match env.convert_byte_array(&patch_rgba) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };

    if tile.len() < constants::TILE_BYTES
        || mask.len() < constants::TILE_MASK_BYTES
        || patch.len() < (patch_w as usize) * (patch_h as usize) * 4
    {
        return JNI_FALSE;
    }

    selection::extract_and_clear_tile_selection(
        &mut tile,
        tile_origin_x,
        tile_origin_y,
        &mask,
        &mut patch,
        patch_w as usize,
        patch_h as usize,
        patch_origin_x,
        patch_origin_y,
    );

    let tile_slice: &[i8] =
        unsafe { std::slice::from_raw_parts(tile.as_ptr() as *const i8, tile.len()) };
    let patch_slice: &[i8] =
        unsafe { std::slice::from_raw_parts(patch.as_ptr() as *const i8, patch.len()) };

    if env.set_byte_array_region(&tile_rgba, 0, tile_slice).is_err()
        || env.set_byte_array_region(&patch_rgba, 0, patch_slice).is_err()
    {
        return JNI_FALSE;
    }

    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_blitPatchToTile<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    tile_rgba: JByteArray<'local>,
    tile_origin_x: jint,
    tile_origin_y: jint,
    patch_rgba: JByteArray<'local>,
    patch_w: jint,
    patch_h: jint,
    patch_origin_x: jint,
    patch_origin_y: jint,
) -> jboolean {
    let mut tile = match env.convert_byte_array(&tile_rgba) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };
    let patch = match env.convert_byte_array(&patch_rgba) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };

    if tile.len() < constants::TILE_BYTES || patch.len() < (patch_w as usize) * (patch_h as usize) * 4 {
        return JNI_FALSE;
    }

    selection::blit_patch_to_tile(
        &mut tile,
        tile_origin_x,
        tile_origin_y,
        &patch,
        patch_w as usize,
        patch_h as usize,
        patch_origin_x,
        patch_origin_y,
    );

    let tile_slice: &[i8] =
        unsafe { std::slice::from_raw_parts(tile.as_ptr() as *const i8, tile.len()) };

    if env.set_byte_array_region(&tile_rgba, 0, tile_slice).is_err() {
        return JNI_FALSE;
    }

    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_selectionSessionBegin<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    points_x: JFloatArray<'local>,
    points_y: JFloatArray<'local>,
    bounds_left: jfloat,
    bounds_top: jfloat,
    bounds_right: jfloat,
    bounds_bottom: jfloat,
) -> jboolean {
    let len_x = match env.get_array_length(&points_x) {
        Ok(l) => l as usize,
        Err(_) => return JNI_FALSE,
    };
    let len_y = match env.get_array_length(&points_y) {
        Ok(l) => l as usize,
        Err(_) => return JNI_FALSE,
    };

    if len_x != len_y || len_x < 3 {
        return JNI_FALSE;
    }

    let mut xs = vec![0.0f32; len_x];
    let mut ys = vec![0.0f32; len_y];
    if env.get_float_array_region(&points_x, 0, &mut xs).is_err()
        || env.get_float_array_region(&points_y, 0, &mut ys).is_err()
    {
        return JNI_FALSE;
    }

    let points: Vec<selection::Point2D> = xs
        .into_iter()
        .zip(ys.into_iter())
        .map(|(x, y)| selection::Point2D { x, y })
        .collect();

    let custom_bounds = if bounds_right > bounds_left && bounds_bottom > bounds_top {
        Some(selection::RectF {
            min_x: bounds_left as f32,
            min_y: bounds_top as f32,
            max_x: bounds_right as f32,
            max_y: bounds_bottom as f32,
        })
    } else {
        None
    };

    selection::session_begin(points, custom_bounds);
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_selectionSessionCutTile<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    layer_id: jlong,
    tx: jint,
    ty: jint,
    tile_rgba: JByteArray<'local>,
    for_liquify: jboolean,
) -> jboolean {
    let mut tile = match env.convert_byte_array(&tile_rgba) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };

    let is_liquify = for_liquify == JNI_TRUE;
    if selection::session_cut_tile(layer_id as i64, tx as i32, ty as i32, &mut tile, is_liquify) {
        if !is_liquify {
            let tile_slice: &[i8] =
                unsafe { std::slice::from_raw_parts(tile.as_ptr() as *const i8, tile.len()) };
            if env.set_byte_array_region(&tile_rgba, 0, tile_slice).is_err() {
                return JNI_FALSE;
            }
        }
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_selectionSessionGetPreview<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    layer_id: jlong,
    max_dim: jint,
) -> JByteArray<'local> {
    match selection::session_get_preview(layer_id as i64, max_dim as usize) {
        Some((pw, ph, rgba)) => {
            let mut packed = Vec::with_capacity(8 + rgba.len());
            packed.extend_from_slice(&(pw as u32).to_be_bytes());
            packed.extend_from_slice(&(ph as u32).to_be_bytes());
            packed.extend_from_slice(&rgba);
            env.byte_array_from_slice(&packed).unwrap_or_default()
        }
        None => JByteArray::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_selectionSessionGetAffectedDestTiles<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    layer_id: jlong,
    scale_x: jfloat,
    scale_y: jfloat,
    rotation_rad: jfloat,
    trans_x: jfloat,
    trans_y: jfloat,
    pivot_x: jfloat,
    pivot_y: jfloat,
    flip_h: jboolean,
    flip_v: jboolean,
) -> JIntArray<'local> {
    let tiles = selection::session_get_affected_dest_tiles(
        layer_id as i64,
        scale_x as f32,
        scale_y as f32,
        rotation_rad as f32,
        trans_x as f32,
        trans_y as f32,
        pivot_x as f32,
        pivot_y as f32,
        flip_h == JNI_TRUE,
        flip_v == JNI_TRUE,
    );

    let mut flat = Vec::with_capacity(tiles.len() * 2);
    for (tx, ty) in tiles {
        flat.push(tx as jint);
        flat.push(ty as jint);
    }

    let array = match env.new_int_array(flat.len() as jint) {
        Ok(a) => a,
        Err(_) => return JIntArray::default(),
    };

    if env.set_int_array_region(&array, 0, &flat).is_ok() {
        array
    } else {
        JIntArray::default()
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_selectionSessionBlitToTile<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    layer_id: jlong,
    tx: jint,
    ty: jint,
    tile_rgba: JByteArray<'local>,
    scale_x: jfloat,
    scale_y: jfloat,
    rotation_rad: jfloat,
    trans_x: jfloat,
    trans_y: jfloat,
    pivot_x: jfloat,
    pivot_y: jfloat,
    flip_h: jboolean,
    flip_v: jboolean,
) -> jboolean {
    let mut tile = match env.convert_byte_array(&tile_rgba) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };

    let modified = selection::session_blit_to_tile(
        layer_id as i64,
        tx as i32,
        ty as i32,
        &mut tile,
        scale_x as f32,
        scale_y as f32,
        rotation_rad as f32,
        trans_x as f32,
        trans_y as f32,
        pivot_x as f32,
        pivot_y as f32,
        flip_h == JNI_TRUE,
        flip_v == JNI_TRUE,
    );

    if modified {
        let tile_slice: &[i8] =
            unsafe { std::slice::from_raw_parts(tile.as_ptr() as *const i8, tile.len()) };
        if env.set_byte_array_region(&tile_rgba, 0, tile_slice).is_ok() {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_selectionSessionLiquifyBlitPatch<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    tx: jint,
    ty: jint,
    tile_rgba: JByteArray<'local>,
    patch_left: jfloat,
    patch_top: jfloat,
    patch_w: jint,
    patch_h: jint,
    patch_rgba: JByteArray<'local>,
) -> jboolean {
    let mut tile = match env.convert_byte_array(&tile_rgba) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };
    let patch = match env.convert_byte_array(&patch_rgba) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };

    let modified = selection::session_liquify_blit_patch(
        tx as i32,
        ty as i32,
        &mut tile,
        patch_left as f32,
        patch_top as f32,
        patch_w as usize,
        patch_h as usize,
        &patch,
    );

    if modified {
        let tile_slice: &[i8] =
            unsafe { std::slice::from_raw_parts(tile.as_ptr() as *const i8, tile.len()) };
        if env.set_byte_array_region(&tile_rgba, 0, tile_slice).is_ok() {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_app_goodboy13_milton_core_native_MiltonNative_selectionSessionEnd<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    selection::session_end();
}


