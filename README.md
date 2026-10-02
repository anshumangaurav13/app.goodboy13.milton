# Milton for Android

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" height="128" alt="Milton Logo" />
</p>

<p align="center">
  <strong>Infinite Canvas Painting & Sketching for Android Tablets</strong>
</p>

<p align="center">
  <a href="#features"><img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen.svg" alt="Platform" /></a>
  <a href="#architecture"><img src="https://img.shields.io/badge/Renderer-OpenGL%20ES%203.0-blue.svg" alt="Renderer" /></a>
  <a href="#architecture"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-purple.svg" alt="UI" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-orange.svg" alt="License" /></a>
</p>

---

## Overview

**Milton for Android** is an open-source infinite canvas sketching and digital painting application tailored for Android tablets and active pressure-sensitive styluses (Samsung S-Pen, Xiaomi Pen, Lenovo Precision Pen, universal active styluses).

Inspired by the desktop painting program [Milton by Sergio Gonzalez](https://github.com/serge-rgb/milton), this implementation provides a virtually boundless workspace where artists can zoom in and out without loss of clarity, sketch without running out of borders, and paint with natural, pressure-sensitive dabs.

---

## Features

- **Infinite Canvas Navigation**:
  - Boundless pan, zoom, and continuous canvas rotation via fluid multi-touch gestures.
  - Horizontal canvas flip to check artistic composition and proportions.
  - Quick-reset canvas transforms and orientation snapping.

- **Ultra-Low Latency Stylus Pipeline**:
  - Powered by AndroidX `GLFrontBufferedRenderer` with front-buffer composition for near-zero ink-to-glass latency.
  - Sub-frame motion event history unpacking for ultra-smooth Bezier curve interpolation.
  - Hardware stylus recognition (differentiates stylus nibs, hardware barrel buttons, and eraser ends).

- **Hardware Palm Rejection**:
  - Strict input partitioning: fingers navigate the viewport (pan, zoom, rotate), while the active stylus exclusively draws.

- **Dynamic Brush Engine**:
  - Presets for **Pencil**, **Pen**, **Paintbrush**, and **Eraser**.
  - Interactive Cubic Bézier Curve Graph for custom stylus pressure response curves.
  - Configurable dab size, opacity, hardness, and stroke stabilization.

- **Multi-Layer System**:
  - Up to 16 discrete raster layers.
  - Per-layer opacity sliders and visibility toggles.
  - Smooth drag-and-drop layer reordering, duplicate, clear, and merge down operations.
  - Real-time asynchronous thumbnail generation.

- **Modular Persistence & Export**:
  - Debounced background autosave tracking dirty tile modifications.
  - Portable `.milton` archive packaging (ZIP format with JSON manifests and compressed raw raster tiles).
  - High-resolution offscreen raster export to PNG and JPEG matching visible screen bounds and canvas orientation.

- **Infinite History (Undo / Redo)**:
  - Multi-level undo/redo stack.
  - Gesture shortcuts (two-finger tap for undo, three-finger tap for redo).
  - Compressed tile delta snapshotting for minimal memory overhead.

---

## Architecture

Milton follows a decoupled, modular architecture designed for high graphics throughput, strict memory limits, and clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────┐
│                 Jetpack Compose UI Layer                    │
│   (TabletTopBar, TabletToolRail, LayersFloatingWindow, etc.) │
└──────────────────────────────┬──────────────────────────────┘
                               │ StateFlow / CanvasUiActions
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                      CanvasViewModel                        │
│         (Unidirectional Data Flow & Session Coordinator)    │
└──────────────────────────────┬──────────────────────────────┘
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
┌──────────────────────────────┐    ┌──────────────────────────────┐
│        CanvasEngine          │    │    DocumentStorageManager    │
│  (Domain Coordinator: Layers,│    │  (AutosaveCoordinator,       │
│   UndoManager, Viewport)     │    │   ProjectCatalogRepository,  │
└──────────────┬───────────────┘    │   MiltonArchiveCodec,        │
               │ GlRenderCommand    │   CanvasExportEngine)        │
               ▼                    └──────────────────────────────┘
┌──────────────────────────────┐
│       GlSceneRenderer        │
│  (OpenGL ES 3.0 Pipeline:    │
│   FBOs, Blit, Dab Shaders)   │
└──────────────┬───────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────────┐
│                 VRAM & Native Memory Pools                  │
│       - GlTexturePool (fixed pool of pre-allocated FBOs)     │
│       - DirectBufferPool (recycled off-heap ByteBuffers)    │
│       - TileMap (sparse tile grid with disk-swap eviction)  │
└─────────────────────────────────────────────────────────────┘
```

### Key Modules

- **`core/gl`**:
  - [`GlSceneRenderer`](app/src/main/java/com/antigrav/milton/core/gl/GlSceneRenderer.kt): Pure graphics pipeline executing dab shaders, FBO ping-pong compositing, tile blitting, and offscreen exports.
  - [`CanvasEngine`](app/src/main/java/com/antigrav/milton/core/gl/CanvasEngine.kt): Canvas domain coordinator managing tile diff generation, thumbnail rendering, and budget trimming.
  - [`GlRenderCommand`](app/src/main/java/com/antigrav/milton/core/gl/GlRenderCommand.kt): Thread-safe command queue decoupling the UI thread from the OpenGL thread.
- **`core/tile` & `core/memory`**:
  - [`TileMap`](app/src/main/java/com/antigrav/milton/core/tile/TileMap.kt): Sparse coordinate hash map managing 512×512 raster tiles.
  - [`GlTexturePool`](app/src/main/java/com/antigrav/milton/core/gl/GlTexturePool.kt) & [`DirectBufferPool`](app/src/main/java/com/antigrav/milton/core/memory/DirectBufferPool.kt): Pre-allocated VRAM texture and direct byte buffer recycling pools preventing GC heap churn.
- **`core/storage`**:
  - [`AutosaveCoordinator`](app/src/main/java/com/antigrav/milton/core/storage/AutosaveCoordinator.kt): Debounced background persistence service.
  - [`ProjectCatalogRepository`](app/src/main/java/com/antigrav/milton/core/storage/ProjectCatalogRepository.kt): Saved project catalog repository.
  - [`MiltonArchiveCodec`](app/src/main/java/com/antigrav/milton/core/storage/MiltonArchiveCodec.kt): Zip archive packaging for `.milton` files.
  - [`CanvasExportEngine`](app/src/main/java/com/antigrav/milton/core/storage/CanvasExportEngine.kt): High-resolution bitmap renderer.
- **`ui`**:
  - [`CanvasViewModel`](app/src/main/java/com/antigrav/milton/ui/CanvasViewModel.kt): MVI/UDF state container exposing `StateFlow<CanvasUiState>`.
  - Feature-decomposed Compose components under `ui/topbar`, `ui/rail`, `ui/palette`, and `ui/components`.

---

## Building from Source

### Prerequisites

- **Android Studio**: Android Studio Ladybug / Meerkat or newer
- **Android SDK**: Compile SDK 35, Minimum SDK 26 (Android 8.0)
- **JDK**: Java 21

### Clone & Build

```bash
# Clone the repository
git clone https://github.com/<YOUR_USERNAME>/Milton.git
cd Milton

# Build the debug APK
./gradlew assembleDebug

# Run all unit test suites
./gradlew test
```

The resulting debug APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Release Signing Setup (Optional)

By default, running `./gradlew assembleRelease` automatically falls back to debug signing if no release keystore is provided. To sign with your own key:

1. Copy `keystore.properties.example` to `keystore.properties` (this file is excluded by `.gitignore`):
   ```bash
   cp keystore.properties.example keystore.properties
   ```
2. Edit `keystore.properties` with your signing credentials:
   ```properties
   storeFile=/path/to/your/release-key.jks
   storePassword=your_keystore_password
   keyAlias=your_key_alias
   keyPassword=your_key_password
   ```
3. Run `./gradlew assembleRelease`. The signed APK will be at `app/build/outputs/apk/release/app-release.apk`.

---

## License

This project is licensed under the [MIT License](LICENSE).
