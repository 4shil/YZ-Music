# YZ Music

<div align="center">

### Modern, High-Fidelity YouTube Music Client for Android

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen?style=for-the-badge&logo=android)](https://github.com/4shil/YZ-Music)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](LICENSE)
[![Target SDK](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-orange?style=for-the-badge&logo=android)](app/build.gradle.kts)

[**Features**](#features) · [**Architecture**](#architecture) · [**Building from Source**](#building-from-source) · [**Autoplay Engine**](#autoplay-recommendation-engine) · [**Privacy & Security**](#privacy--security)

</div>

---

## Overview

**YZ Music** is an open-source, modern Android audio streaming client built with Jetpack Compose, AndroidX Media3, and C++ digital signal processing. It provides a native, privacy-first, ad-free listening experience powered by YouTube Music, multi-source lyrics synchronization, dynamic material theming, and an intelligent recommendation queue.

> [!NOTE]
> YZ Music is an independent project and is not affiliated with, endorsed by, or connected to YouTube, Google, or Alphabet Inc.

---

## Features

### Playback & Audio
- **Seamless Streaming**: Full catalogue search, album browsing, artist discography, and playlist playback.
- **Standalone AutoPlay**: Dynamic queue continuation powered by YouTube Music `/next` radio, candidate classification, and per-artist diversity caps.
- **High-Fidelity Audio**: Lossless audio support (FLAC/ALAC) with fallback to high-bitrate YouTube Music audio streams.
- **True Crossfade**: Adjustable 0–12s gapless crossfade transitions with automated loudness management.
- **Automix DSP Analyzer**: Native C++ 17 beat and tempo tracking (`native/analyzer`) for musical transitions.
- **Offline Downloads**: High-bitrate audio downloads with embedded ID3/Vorbis comment tags, synchronized lyrics, and artwork.
- **Local Audio Library**: Indexed on-device playback supporting local tracks, albums, and artist tags.

### Visuals & User Experience
- **Motion Artwork**: Real-time canvas animation and Spotify canvas integration on the Now Playing screen.
- **Synchronized Lyrics**: Word-level, syllable-level, and line-level lyrics synced from LRCLib, Musixmatch, and embedded tags.
- **Dynamic Theming**: Artwork-driven Material 3 color palettes with AMOLED black and light theme support.
- **Frosted Glass UI**: Ultra-smooth frosted acrylic scrims and fluid gestures.
- **Home Screen Widgets**: Now-playing widgets with interactive media controls and dynamic artwork updates.

### Connectivity & Accounts
- **Google Account Authentication**: Optional session cookie integration for personal playlists, recommendations, and library sync.
- **Scrobbling**: Native scrobbling to Last.fm and ListenBrainz.
- **Local Backup & Restore**: Full JSON backup and migration for playlists, playback history, and user settings.

---

## Architecture

YZ Music follows modern Android architectural guidelines:

```text
┌──────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                     │
│  (Material 3, Haze Frosted Glass, Navigation, Replay)    │
└────────────────────────────┬─────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────┐
│                 MainViewModel & StateFlow                │
│    (Authoritative player state, queue sync, settings)    │
└────────────────────────────┬─────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────┐
│           AndroidX Media3 Service (PlaybackService)      │
│  ├── ExoPlayer Engine (MediaSession, AudioAttributes)    │
│  ├── CrossfadeController (Gapless & dual-player fade)    │
│  ├── Autoplay Manager (Dynamic replenishment)            │
│  └── AudioCache (Disk caching & stream resolution)       │
└──────────────┬─────────────────────────────┬─────────────┘
               │                             │
┌──────────────▼─────────────┐ ┌─────────────▼─────────────┐
│    C++ DSP Native Engine   │ │     Data & InnerTube      │
│  - Tempo & beat analysis   │ │  - Watch queue parser     │
│  - Mel spectrograms        │ │  - Stream URL resolver    │
│  - Vocal separation        │ │  - Multi-provider lyrics  │
└────────────────────────────┘ └───────────────────────────┘
```

---

## Autoplay & Recommendation Engine

YZ Music features an advanced autoplay engine designed for standalone single-song playback:

1. **Seed Extraction**: Extracts remote video IDs or matches local tracks against YouTube Music catalogue metadata.
2. **Watch Queue Classification**: Evaluates items using structured metadata (`musicVideoType`, non-square thumbnail shape, and album attribution) rather than view count heuristics, preventing unwanted video renderers from polluting the audio queue.
3. **Fault-Tolerant Resolution**: Candidate audio resolutions are individually isolated; network or cipher failures on single tracks do not discard the recommendation batch.
4. **Low-Water Replenishment**: Continuously monitors the upcoming playback buffer and proactively requests new recommendations when queued tracks drop below threshold.
5. **Diversity & Deduplication**: Restricts tracks per artist (`PER_ARTIST_LIMIT = 2`) to ensure recommendation variety and prevents repeat plays of recently heard tracks.

---

## Building from Source

### Prerequisites
- Android Studio Ladybug (or newer) / IntelliJ IDEA
- Java Development Kit (JDK) 17+ (Microsoft OpenJDK 17 recommended)
- Android SDK Platform 36 (Build Tools 36.0.0 or 35.0.0)
- CMake 3.22.1+ & Android NDK (r27 or newer)

### Build Commands

```powershell
# Run all unit tests
./gradlew testProdDebugUnitTest

# Assemble production debug APK
./gradlew assembleProdDebug

# Assemble signed release APK (requires keystore configuration)
./gradlew assembleProdRelease
```

Generated APKs are located at:
- Debug: `app/build/outputs/apk/prod/debug/app-prod-debug.apk`
- Release: `app/build/outputs/apk/prod/release/app-prod-release.apk`

---

## Privacy & Security

- **No Third-Party Analytics**: Zero tracking SDKs or telemetry beacons.
- **Direct-to-Source**: All requests communicate directly with YouTube Music / lyrics providers.
- **Local Storage**: All playback statistics, history, and downloads reside strictly on your local device.
- **Credential Protection**: Account cookies and API keys are stored in encrypted private shared preferences and are never exported.

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
