# YZ Music — Qobuz & TIDAL Custom Modules

This repository serves as a **Custom Module Source Index** for [YZ Music](https://github.com/4shil/YZ-Music), hosting custom source modules for **Qobuz** and **TIDAL**.

---

## 🚀 Quick Setup & Deployment

### Step 1: Deploy to Vercel

You can deploy this repository to Vercel in seconds:

1. Fork or clone this repository to your GitHub account.
2. Go to [Vercel](https://vercel.com) and click **Add New Project**.
3. Import your repository and click **Deploy** (no build command required, it's a static repository configured with `vercel.json`).
4. Once deployed, Vercel gives you a public URL, for example:
   ```text
   https://yz-music-sources.vercel.app
   ```

### Live Endpoints

The official live production endpoints are:
- **Module Index:** `https://yz-music-sources.vercel.app/index.json`
- **Qobuz Module:** `https://yz-music-sources.vercel.app/modules/qobuz.js`
- **TIDAL Module:** `https://yz-music-sources.vercel.app/modules/tidal.js`

### Adding to YZ Music

In YZ Music on your Android device:

```text
Settings
   ↓
Sources
   ↓
Custom Modules
   ↓
Paste Module Index URL: https://yz-music-sources.vercel.app/index.json
   ↓
Save / Refresh
```

YZ Music will automatically download and register both **Qobuz** and **TIDAL** as available lossless/Hi-Res sources!

---

## 🎵 Modules Overview

### 1. Qobuz Module (`modules/qobuz.js`)

- **Author:** YZ Music
- **Version:** 1.0.0
- **Supported Qualities:**
  - `LOSSLESS`: 16-bit / 44.1 kHz FLAC (CD quality), or 24-bit / up to 192 kHz FLAC (Studio Hi-Res)
  - `HIGH`: 320 kbps MP3
  - `LOW`: 128 kbps MP3
- **Tags & Labels:** `["LOSSLESS", "HI-RES", "FLAC", "STUDIO"]`

#### Search & Matching Strategy:
- **ISRC Query Matching:** If the search query starts with `isrc:` or contains an ISRC pattern (e.g. `USUM71703861`), the module queries the Qobuz catalog directly by ISRC.
- **Title / Artist / Album Normalization:** Punctuation, extraneous brackets (`[Remastered]`, `(Live)`), and whitespace are sanitized for maximum match accuracy against Spotify / YouTube metadata.
- **Quality Extraction:** Reads `maximum_bit_depth` (16 / 24 bit) and `maximum_sampling_rate` (44.1 / 96 / 192 kHz) directly from Qobuz item records.

#### Authentication & Streaming:
- **Authorized Full Stream:** Supports optional `context.settings.userToken` (or `authToken`) configured in custom module settings. When present, retrieves full-length Studio FLAC streams via `/track/getFileUrl`.
- **Public Catalog Preview:** If no user subscription token is provided, returns authorized 30-second catalog sample streams.
- **Strict Mode Handling:** When YZ is configured in `strict` fallback mode without a valid user token, the module cleanly throws an informative error explaining that full FLAC streaming requires a Qobuz account token, allowing YZ's `SourceResolver` to seamlessly cascade to the next configured lossless source.

---

### 2. TIDAL Module (`modules/tidal.js`)

- **Author:** YZ Music
- **Version:** 1.0.0
- **Supported Qualities:**
  - `LOSSLESS`: 16-bit / 44.1 kHz FLAC, or 24-bit / up to 96 kHz Hi-Res FLAC
  - `HIGH`: 320 kbps AAC
  - `LOW`: 96 kbps AAC
- **Tags & Labels:** `["LOSSLESS", "HI-RES", "FLAC", "TIDAL"]`

#### Search & Matching Strategy:
- **ISRC Matching:** Automatically detects ISRC strings in search queries and flags exact ISRC matches in results.
- **Cover Image Generation:** Translates TIDAL UUID cover IDs into high-resolution 640x640 CDN URLs (`https://resources.tidal.com/images/<uuid>/640x640.jpg`).
- **Quality Extraction:** Maps TIDAL catalog quality flags (`HIRES_LOSSLESS` -> 24-bit / 96 kHz FLAC; `LOSSLESS` -> 16-bit / 44.1 kHz FLAC).

#### Authentication & Streaming:
- **Authorized Full Stream:** Supports optional `context.settings.accessToken` (TIDAL OAuth Bearer token). When provided, retrieves full-length lossless streams via `/tracks/{id}/playbackinfopostpaywall` and decodes the BTS manifest via QuickJS's built-in `atob()`.
- **Public Catalog Preview:** If unauthenticated, retrieves authorized 30-second AAC preview streams via `/tracks/{id}/playbackinfo`.
- **Strict Mode Handling:** When configured with `strict` fallback and without an access token, gracefully reports the requirement, allowing YZ to cleanly fall back to other available sources.

---

## 🔒 Security, Compliance & DRM Policy

1. **No DRM or Protection Bypasses:** Neither module attempts to circumvent Widevine DRM, decrypt encrypted streams, or extract private cryptographic keys.
2. **No Hardcoded Secrets:** No personal credentials, private tokens, or proprietary signing keys are stored in this repository or committed to git.
3. **QuickJS Native Sandbox:** Both modules operate strictly within YZ Music's `QuickJsExecutor` sandbox using only supported primitives (`fetch`, `atob`, `URL`, `console`, `setTimeout`). No Node.js or browser DOM APIs are assumed.

---

## 🛠️ Testing & Validation

This repository is validated against the YZ Music Android test suite:
- `CustomModuleValidationTest.kt` verifies:
  - `ModuleIndex` JSON schema compatibility (`category:music` parsing).
  - Module metadata validity (`id`, `name`, `download`, `tags`).
  - Lossless and Hi-Res label detection.
  - Complete syntax correctness of `qobuz.js` and `tidal.js` for QuickJS execution.

---

## 📄 License

MIT License. Designed specifically for YZ Music custom source integration.
