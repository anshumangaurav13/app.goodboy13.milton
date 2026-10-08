use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::Path;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

/// Packages manifest and tiles directory into a high-speed .milton ZIP archive.
pub fn export_milton_archive(
    manifest_json: &str,
    tiles_dir: &str,
    destination_zip: &str,
) -> Result<(), String> {
    let dest_path = Path::new(destination_zip);
    let temp_zip_path = dest_path.with_extension("tmp_zip");

    let file = File::create(&temp_zip_path).map_err(|e| format!("Cannot create temp zip: {e}"))?;
    let mut zip = ZipWriter::new(BufWriter::new(file));

    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .unix_permissions(0o644);

    // 1. Write manifest.json
    zip.start_file("manifest.json", options)
        .map_err(|e| format!("Cannot start manifest in zip: {e}"))?;
    zip.write_all(manifest_json.as_bytes())
        .map_err(|e| format!("Cannot write manifest to zip: {e}"))?;

    // 2. Iterate and write all tile files from tiles directory
    let tiles_path = Path::new(tiles_dir);
    if tiles_path.is_dir() {
        if let Ok(entries) = fs::read_dir(tiles_path) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_file() && path.extension().and_then(|s| s.to_str()) == Some("bin") {
                    let file_name = entry.file_name();
                    let relative_name = format!("tiles/{}", file_name.to_string_lossy());

                    zip.start_file(relative_name, options)
                        .map_err(|e| format!("Cannot start tile in zip: {e}"))?;

                    let mut tile_file = File::open(&path)
                        .map_err(|e| format!("Cannot read tile {}: {e}", path.display()))?;
                    std::io::copy(&mut tile_file, &mut zip)
                        .map_err(|e| format!("Cannot copy tile {}: {e}", path.display()))?;
                }
            }
        }
    }

    zip.finish().map_err(|e| format!("Failed to finalize zip: {e}"))?;

    // Atomic rename
    fs::rename(&temp_zip_path, dest_path)
        .map_err(|e| format!("Failed to rename temp zip to target: {e}"))?;

    Ok(())
}

/// Unpacks a .milton ZIP archive directly into target directory and returns the manifest JSON string.
pub fn import_milton_archive(
    source_zip: &str,
    destination_tiles_dir: &str,
) -> Result<String, String> {
    let zip_file = File::open(source_zip)
        .map_err(|e| format!("Cannot open zip {source_zip}: {e}"))?;
    let mut archive = ZipArchive::new(BufReader::new(zip_file))
        .map_err(|e| format!("Cannot read zip archive: {e}"))?;

    let dest_tiles_path = Path::new(destination_tiles_dir);
    fs::create_dir_all(dest_tiles_path)
        .map_err(|e| format!("Cannot create tiles directory: {e}"))?;

    let mut manifest_content = None;

    for i in 0..archive.len() {
        let mut file = archive.by_index(i)
            .map_err(|e| format!("Cannot access zip entry {i}: {e}"))?;
        let name = file.name().to_string();

        if name == "manifest.json" {
            let mut s = String::new();
            file.read_to_string(&mut s)
                .map_err(|e| format!("Failed to read manifest.json: {e}"))?;
            manifest_content = Some(s);
        } else if (name.starts_with("tiles/") || name.starts_with("tiles\\")) && name.ends_with(".bin") {
            let file_name = Path::new(&name).file_name()
                .ok_or_else(|| "Invalid tile file name in zip".to_string())?;
            let out_path = dest_tiles_path.join(file_name);

            let mut out_file = File::create(&out_path)
                .map_err(|e| format!("Cannot create out file {}: {e}", out_path.display()))?;
            std::io::copy(&mut file, &mut out_file)
                .map_err(|e| format!("Failed to extract tile {}: {e}", out_path.display()))?;
        }
    }

    manifest_content.ok_or_else(|| "Missing manifest.json in archive".to_string())
}
