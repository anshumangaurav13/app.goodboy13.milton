package app.goodboy13.milton

import app.goodboy13.milton.core.history.UndoManager
import app.goodboy13.milton.core.storage.AutosaveCoordinator
import app.goodboy13.milton.core.storage.DocumentMetadata
import app.goodboy13.milton.core.storage.LayerDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocumentStorageModularTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testFormatFileSizeUnits() {
        fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
                else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }

        assertEquals("500 B", formatFileSize(500L))
        assertEquals("1.0 KB", formatFileSize(1024L))
        assertEquals("15.5 KB", formatFileSize(15872L))
        assertEquals("2.0 MB", formatFileSize(2097152L))
    }

    @Test
    fun testStagedTileCompressionDecompression() {
        val rawTile = ByteArray(512 * 512 * 4) { (it % 256).toByte() }
        val compressed = UndoManager.compress(rawTile)
        assertTrue(compressed.size < rawTile.size)

        val decompressed = UndoManager.decompress(compressed)
        assertArrayEquals(rawTile, decompressed)
    }

    @Test
    fun testMiltonZipPackagingAndExtractionCycle() {
        val rootDir = tempFolder.newFolder("milton_test")
        val tilesDir = File(rootDir, "tiles").apply { mkdirs() }
        val manifestFile = File(rootDir, "manifest.json")

        // 1. Create mock metadata
        val metadata = DocumentMetadata(
            title = "Test Artwork",
            backgroundColorRgb = 0xFFFFFFFF.toInt(),
            viewportPanX = 100f,
            viewportPanY = 200f,
            viewportZoom = 1.0f
        )
        metadata.layers.add(
            LayerDescriptor(
                id = 1L,
                name = "Layer 1",
                opacity = 1.0f,
                isVisible = true,
                tiles = listOf(0 to 0)
            )
        )
        manifestFile.writeText(metadata.toJson())

        // 2. Create mock tile binary
        val mockTileData = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val tileFile = File(tilesDir, "tile_1_0_0.bin")
        tileFile.writeBytes(mockTileData)

        // 3. Package into .milton ZIP
        val zipFile = File(tempFolder.root, "test_artwork.milton")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val manifestEntry = ZipEntry("manifest.json")
            zos.putNextEntry(manifestEntry)
            manifestFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            val tileEntry = ZipEntry("tiles/${tileFile.name}")
            zos.putNextEntry(tileEntry)
            tileFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }

        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() > 0)

        // 4. Unpack and verify
        val unpackDir = tempFolder.newFolder("unpacked")
        val unpackTilesDir = File(unpackDir, "tiles").apply { mkdirs() }
        var extractedManifestText: String? = null

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") {
                    extractedManifestText = zis.bufferedReader().readText()
                } else if (entry.name.startsWith("tiles/")) {
                    val fileName = entry.name.substringAfterLast("/")
                    val outFile = File(unpackTilesDir, fileName)
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertNotNull(extractedManifestText)
        val restoredMeta = DocumentMetadata.fromJson(extractedManifestText!!)
        assertEquals("Test Artwork", restoredMeta.title)
        assertEquals(1, restoredMeta.layers.size)
        assertEquals("Layer 1", restoredMeta.layers[0].name)

        val extractedTile = File(unpackTilesDir, "tile_1_0_0.bin")
        assertTrue(extractedTile.exists())
        assertArrayEquals(mockTileData, extractedTile.readBytes())
    }

    @Test
    fun testDocumentMetadataProjectIdAndDirtySerialization() {
        val meta = DocumentMetadata(
            title = "Graphite Sketch",
            projectId = "proj_test_42",
            hasUnsavedChanges = true
        )
        val json = meta.toJson()
        val parsed = DocumentMetadata.fromJson(json)

        assertEquals("Graphite Sketch", parsed.title)
        assertEquals("proj_test_42", parsed.projectId)
        assertTrue(parsed.hasUnsavedChanges)

        parsed.hasUnsavedChanges = false
        val updatedParsed = DocumentMetadata.fromJson(parsed.toJson())
        assertFalse(updatedParsed.hasUnsavedChanges)
    }
}
