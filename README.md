# YZ Music

<div align="center">

### Modern, High-Fidelity YouTube Music Client for Android & Desktop

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen?style=for-the-badge&logo=android)](https://github.com/4shil/YZ-Music)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](LICENSE)
[![Target SDK](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-orange?style=for-the-badge&logo=android)](app/build.gradle.kts)
[![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20Linux%20%7C%20Windows%20%7C%20macOS%20%7C%20Wear%20OS-blueviolet?style=for-the-badge)](https://github.com/4shil/YZ-Music)

[**Features**](#features) · [**Architecture**](#architecture) · [**Building from Source**](#building-from-source) · [**Autoplay & Automix Engine**](#autoplay--automix-engine) · [**Privacy & Security**](#privacy--security)

</div>

---

## Overview

**YZ Music** is an open-source, high-fidelity music streaming client built with Compose Multiplatform, AndroidX Media3, and C++ digital signal processing. It provides a native, privacy-first, ad-free listening experience across Android, Desktop (Linux, Windows, macOS), and Wear OS, powered by YouTube Music, multi-source synchronized lyrics, dynamic Material 3 theming, intelligent DJ transitions, and robust offline caching.

> [!NOTE]
> YZ Music is an independent project and is not affiliated with, endorsed by, or connected to YouTube, Google, or Alphabet Inc.

---

## Features

### Multiplatform Support
- **Android**: Phones, tablets, and foldables with responsive layout adaptations and edge-to-edge frosted glass design.
- **Desktop**: Native Compose Multiplatform standalone binaries for Linux (x64), Windows (x64), and macOS with system tray integration and native window glass.
- **Wear OS**: Companion app and tiles for wrist playback control and standalone queue browsing.

### Playback & Audio Intelligence
- **Seamless Streaming**: Full catalogue search, album browsing, artist discography, and playlist playback.
- **DJ Automix & Beat Tracking**: Native C++ 17 DSP engine with Camelot wheel harmonic key matching, tempo tracking, beat alignment, and WSOLA time-stretching.
- **Standalone AutoPlay**: Dynamic queue continuation powered by YouTube Music `/next` radio, candidate classification, and per-artist diversity caps.
- **High-Fidelity Audio**: Lossless audio support (FLAC/ALAC) with fallback to high-bitrate YouTube Music audio streams.
- **True Crossfade**: Adjustable 0–12s gapless crossfade transitions with automated loudness management.
- **Offline Downloads**: High-bitrate audio downloads with embedded ID3/Vorbis tags, synchronized lyrics, and artwork, accompanied by real-time progress indicators.
- **Local Audio Library**: Indexed on-device playback supporting local tracks, albums, folders, and artist tags.

### Visuals & Fluid Experience
- **Magazine Explore Layout**: Visual discovery featuring curated InnerTube charts, trending releases, mood & genre shelves, and new music highlights.
- **Gesture-Driven Player Controls**:
  - Swipe down to dismiss full player to miniplayer
  - Swipe up to expand the playback queue
  - Miniplayer swipe gestures for previous/next track
  - Configurable seek step increments (5s, 10s, 25s)
- **Motion Artwork**: Real-time canvas animation and Spotify canvas integration on the Now Playing screen.
- **Synchronized Lyrics**: Word-level, syllable-level, and line-level lyrics synced from LRCLib, Musixmatch, and embedded tags.
- **Dynamic Theming**: Artwork-driven Material 3 color palettes with AMOLED pure black and light theme support.
- **Frosted Glass UI**: Ultra-smooth frosted acrylic scrims powered by Haze blur.
- **Home Screen Widgets**: Now-playing widgets with interactive media controls and dynamic artwork updates.

### Connectivity & Accounts
- **Google Account Authentication**: Optional session cookie integration for personal playlists, recommendations, and library sync.
- **Scrobbling**: Native scrobbling to Last.fm and ListenBrainz.
- **Discord Rich Presence**: Live playback status, artist, album, and elapsed time on desktop and mobile.
- **Local Backup & Restore**: Full JSON backup and migration for playlists, playback history, and user settings.

---

## Architecture

YZ Music follows modern declarative UI and unidirectional data flow architecture:

```text
┌──────────────────────────────────────────────────────────┐
│              Compose Multiplatform UI                    │
│    (Android Jetpack Compose & Desktop Compose JVM)       │
│  ├── Material 3 Theming & Haze Frosted Glass             │
│  ├── Magazine Explore & Dynamic Carousel Shelves         │
│  └── Fluid Gesture Player & Shared Transitions           │
└────────────────────────────┬─────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────┐
│                 MainViewModel & StateFlow                │
│    (Authoritative player state, queue sync, settings)    │
└────────────────────────────┬─────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────┐
│           Playback Engine (AndroidX Media3 / Desktop)     │
│  ├── MediaSession & AudioAttributes                      │
│  ├── CrossfadeController (Gapless & dual-player fade)    │
│  ├── Autoplay & Queue Manager (Dynamic replenishment)    │
│  └── Persistent Download & AudioCache Subsystem          │
└──────────────┬─────────────────────────────┬─────────────┘
               │                             │
┌──────────────▼─────────────┐ ┌─────────────▼─────────────┐
│    C++ DSP Native Engine   │ │     Data & InnerTube      │
│  - Tempo & beat analysis   │ │  - Watch queue parser     │
│  - Camelot harmonic key    │ │  - Stream URL resolver    │
│  - WSOLA time stretching   │ │  - Multi-provider lyrics  │
└────────────────────────────┘ └───────────────────────────┘
```

---

## Autoplay & Automix Engine

YZ Music features an advanced playback continuation and mixing engine:

1. **Seed Extraction**: Extracts remote video IDs or matches local tracks against YouTube Music catalogue metadata.
2. **Watch Queue Classification**: Evaluates items using structured metadata (`musicVideoType`, non-square thumbnail shape, and album attribution) rather than view count heuristics, preventing unwanted video renderers from polluting the audio queue.
3. **Harmonic Key & Tempo Alignment**: Analyzes audio segments in real-time or from cached acoustic fingerprints to compute Camelot wheel compatibility and beat phase alignment.
4. **Fault-Tolerant Resolution**: Candidate audio resolutions are individually isolated; network or cipher failures on single tracks do not discard the recommendation batch.
5. **Low-Water Replenishment**: Continuously monitors the upcoming playback buffer and proactively requests new recommendations when queued tracks drop below threshold.
6. **Diversity & Deduplication**: Restricts tracks per artist (`PER_ARTIST_LIMIT = 2`) to ensure recommendation variety and prevents repeat plays of recently heard tracks.

---

## Building from Source

### Prerequisites
- JDK 17+ (Microsoft OpenJDK 17 or Eclipse Temurin recommended)
- Android SDK Platform 36 & Build Tools (for Android builds)
- CMake 3.22.1+ & Android NDK (r27 or newer)

### Android Build Commands

```bash
# Assemble production release APK
./gradlew assembleProdRelease

# Assemble production debug APK
./gradlew assembleProdDebug

# Assemble dev debug APK
./gradlew assembleDevDebug
```

Generated APKs are located at `app/build/outputs/apk/`.

### Desktop Build Commands

```bash
# Build and package standalone desktop distributable
./gradlew :desktopApp:createDistributable

# Run desktop app directly from source
./gradlew :desktopApp:run
```

Desktop binaries are located at `desktopApp/build/compose/binaries/main/app/YZ-Music/`.

---

## Privacy & Security

- **No Third-Party Analytics**: Zero tracking SDKs, advertising frameworks, or telemetry beacons.
- **Direct-to-Source**: All requests communicate directly with YouTube Music and selected lyrics providers.
- **Local Storage**: All playback statistics, history, preferences, and downloads reside strictly on your local device.
- **Credential Protection**: Account session cookies and API tokens are encrypted in private storage and never transmitted to third parties.

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
