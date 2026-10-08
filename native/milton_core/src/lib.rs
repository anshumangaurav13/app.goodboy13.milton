pub mod compression;
pub mod archive;
pub mod tile_swap;
pub mod export;
pub mod sub_tile;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JIntArray, JString};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};

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

