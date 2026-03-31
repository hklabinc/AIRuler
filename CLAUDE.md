# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug

# Install on connected device
./gradlew installDebug
```

No tests or CI/CD are configured.

## Project Overview

**AIRuler** is an industrial defect inspection Android app (`com.hklab.airuler`). It uses YOLOv8/11 models via TensorFlow Lite to detect and measure defects on films through the device camera, with Samsung camera integration for high-resolution (50MP/200MP) captures.

- **Kotlin 2.0.21**, **Java 17**, compileSdk/targetSdk **34**, minSdk **26**
- **XML layouts + View Binding** (no Jetpack Compose)
- No dependency injection framework — manual initialization throughout
- Code comments are frequently in **Korean**

## Architecture

The app uses a **pipeline-based architecture** rather than MVVM/MVI. Three main pipelines in `pipeline/` orchestrate the core flow:

1. **CameraPipeline** — CameraX binding, preview/analysis resolution, frame delivery, flicker mitigation
2. **InferencePipeline** — YOLO detection on frames, motion/hand detection, direction checking, overlay rendering to `DetectionOverlayView`
3. **MeasurementPipeline** — Samsung capture image processing, film measurement (grid or ruler method), EXIF metadata, result saving/uploading

State is managed via two objects in `pipeline/state/`:
- **AppRuntimeState** — transient UI state (preview pause, alerts, overlays)
- **AppSessionSettings** — in-memory session settings snapshot, resets on app kill

## Key Packages

- `film/` — Film detection and measurement logic (~1600 LOC). `FilmTotalMeasureGridProcessor` (grid-based, default) and `FilmTotalMeasureProcessor` (ruler-based). Sub-package `ruler/` has tick detection, calibration, and distance math (ported from Python).
- `grid/` — Grid detection, piecewise affine warping (`GridWarpPiecewiseAffine`), warp caching
- `calibration/` — Online offset calibration with alpha blending
- `inspection/` — Good/bad quality decision engine with threshold counting, film tracking, motion/hand detection, sound feedback
- `yolo/` — TFLite YOLO inference (`YoloDetector`) and class mapping (film/good/bad)
- `model/` — Model registry, download from YesunAI server, file storage, selection/update UI
- `samsungcapture/` — Samsung Camera Pro and Expert RAW proxy activities, capture contracts, auto-return
- `autoreturn/` — Foreground service + accessibility service for auto-returning from Samsung camera
- `net/` — OkHttp-based upload clients for results and files to YesunAI server
- `gallery/` — Result browsing, image viewer with zoom/pan, EXIF measurement values display

## Key Configuration

`GlobalParams.kt` contains all tunable constants: YOLO thresholds (SCORE_THRESH=0.9, IOU_THRESH=0.7), server URL, capture MP scaling factors, storage warning threshold (80%), and feature defaults.

## Entry Points

- **ModelSelectActivity** is the launcher activity (model selection/download)
- **MainActivity** is the main camera + YOLO inference screen
- All activities are locked to **landscape orientation**

## Key Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| CameraX | 1.3.4 | Camera preview and capture |
| TensorFlow Lite | 2.12.0 | YOLO model inference |
| OpenCV | 4.12.0 | Image processing, grid detection |
| OkHttp3 | 4.12.0 | Network requests |
| Coil | 2.6.0 | Image loading |
| Coroutines | 1.7.3 | Async operations |

## Measurement Methods

Two measurement approaches, selectable via settings (`MeasurementMethod` enum in `PipelineModes.kt`):
- **GRID** (default) — Uses `Grid.json` asset + piecewise affine warp to convert pixel coordinates to mm
- **RULER** — Detects physical ruler ticks via `TickDetector`, calculates distance with `DistanceUtils`/`PyMath`

Capture MP (50MP vs 200MP) affects pixel scaling factors — 200MP uses 2x scale throughout.
