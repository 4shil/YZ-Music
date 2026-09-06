/**
 * Qobuz Lossless & Studio Hi-Res Custom Module for YZ Music.
 *
 * Conforms to YZ Music's SpineModule QuickJS contract:
 * - searchTracks(query, limit, context)
 * - getTrackStreamUrl(trackId, quality, context)
 *
 * Supported qualities:
 * - LOSSLESS: 16-bit / 44.1 kHz FLAC, or 24-bit / up to 192 kHz Studio Hi-Res FLAC
 * - HIGH: 320 kbps MP3
 * - LOW: 128 kbps MP3
 */

var DEFAULT_APP_ID = "712109809";
var BASE_API = "https://www.qobuz.com/api.json/0.2";

/**
 * Normalizes strings for robust matching between Spotify/YouTube and Qobuz.
 */
function normalizeText(str) {
    if (!str) return "";
    return str
        .toLowerCase()
        .replace(/[\(\[\{].*?[\)\]\}]/g, "") // Remove (Remastered), [Live], etc.
        .replace(/[^\w\s]/g, " ")             // Strip punctuation
        .replace(/\s+/g, " ")
        .trim();
}

/**
 * Extracts ISRC if present in query.
 */
function extractIsrc(query) {
    if (!query) return null;
    var m = query.match(/\bisrc:?\s*([A-Za-z]{2}-?[A-Za-z0-9]{3}-?[0-9]{2}-?[0-9]{5})\b/i);
    if (m) return m[1].replace(/-/g, "").toUpperCase();
    var bare = query.trim().replace(/-/g, "").toUpperCase();
    if (/^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$/.test(bare)) return bare;
    return null;
}

/**
 * Maps Qobuz bit depth and sampling rate into human-readable YZ quality tier.
 */
function determineQuality(bitDepth, sampleRateKhz) {
    if (bitDepth && bitDepth >= 24) {
        return "HI-RES (" + bitDepth + "-bit / " + sampleRateKhz + " kHz)";
    }
    if (bitDepth && bitDepth >= 16) {
        return "LOSSLESS (16-bit / 44.1 kHz)";
    }
    return "LOSSLESS";
}

/**
 * Searches Qobuz tracks matching the query.
 */
async function searchTracks(query, limit, context) {
    var maxLimit = Math.min(limit || 20, 50);
    var appId = (context && context.settings && context.settings.appId && context.settings.appId.value) || DEFAULT_APP_ID;
    var isrc = extractIsrc(query);
    var searchTerm = isrc ? isrc : query;

    var url = BASE_API + "/catalog/search?query=" + encodeURIComponent(searchTerm) +
        "&limit=" + maxLimit + "&type=tracks&app_id=" + appId;

    console.log("[Qobuz] Searching: " + searchTerm + " (limit=" + maxLimit + ")");

    var resp;
    try {
        resp = await fetch(url, {
            method: "GET",
            headers: {
                "Accept": "application/json",
                "User-Agent": "YZMusic/1.04.03"
            }
        });
    } catch (e) {
        console.error("[Qobuz] Search request failed: " + e);
        return { tracks: [], total: 0 };
    }

    if (!resp.ok) {
        console.error("[Qobuz] Search returned HTTP " + resp.status);
        return { tracks: [], total: 0 };
    }

    var data;
    try {
        data = resp.json();
    } catch (e) {
        console.error("[Qobuz] Failed to parse search JSON: " + e);
        return { tracks: [], total: 0 };
    }

    var rawTracks = (data && data.tracks && data.tracks.items) ? data.tracks.items : [];
    var results = [];

    for (var i = 0; i < rawTracks.length; i++) {
        var item = rawTracks[i];
        if (!item || !item.id) continue;

        var title = item.title || "Unknown Title";
        var artist = (item.performer && item.performer.name) ||
                     (item.artist && item.artist.name) ||
                     "Unknown Artist";
        var album = (item.album && item.album.title) || "";
        var albumId = (item.album && item.album.id) ? String(item.album.id) : null;
        var cover = (item.album && item.album.image) ?
            (item.album.image.large || item.album.image.small || null) : null;

        var durationSec = item.duration || 0;
        var trackNum = item.track_number || 1;
        var bitDepth = item.maximum_bit_depth || (item.hires ? 24 : 16);
        var sampleRate = item.maximum_sampling_rate || 44.1;
        var qualityLabel = determineQuality(bitDepth, sampleRate);

        // If an exact ISRC was queried, filter to exact match if available
        if (isrc && item.isrc) {
            var itemIsrc = item.isrc.replace(/-/g, "").toUpperCase();
            if (itemIsrc === isrc) {
                qualityLabel = "HI-RES ISRC MATCH (" + bitDepth + "-bit / " + sampleRate + " kHz)";
            }
        }

        results.push({
            id: String(item.id),
            title: title,
            artist: artist,
            artistId: (item.performer && item.performer.id) ? String(item.performer.id) : null,
            album: album,
            albumId: albumId,
            albumCover: cover,
            duration: durationSec,
            trackNumber: trackNum,
            audioQuality: qualityLabel,
            format: "flac",
            availableQualities: ["LOSSLESS", "HIGH", "LOW"]
        });
    }

    console.log("[Qobuz] Search yielded " + results.length + " normalized tracks");
    return {
        tracks: results,
        total: (data && data.tracks && data.tracks.total) || results.length
    };
}

/**
 * Resolves a legitimate, playable stream URL for the requested Qobuz track ID.
 */
async function getTrackStreamUrl(trackId, quality, context) {
    if (!trackId) {
        throw new Error("Missing trackId");
    }

    var appId = (context && context.settings && context.settings.appId && context.settings.appId.value) || DEFAULT_APP_ID;
    var userToken = (context && context.settings && context.settings.userToken && context.settings.userToken.value) ||
                    (context && context.settings && context.settings.authToken && context.settings.authToken.value) ||
                    null;
    var targetQuality = (quality || "").toUpperCase();

    // Map quality tier to Qobuz format_id:
    // 27: 24-bit / up to 192 kHz FLAC
    // 7:  24-bit / up to 96 kHz FLAC
    // 6:  16-bit / 44.1 kHz FLAC (CD Lossless)
    // 5:  320 kbps MP3 (High)
    var formatId = 6;
    if (targetQuality === "LOSSLESS") {
        formatId = 7; // Request Studio 24-bit / 96kHz, auto-degrades to 16/44.1
    } else if (targetQuality === "LOW") {
        formatId = 5;
    } else {
        formatId = 5; // HIGH (320kbps MP3)
    }

    console.log("[Qobuz] Resolving stream for track " + trackId + " (format=" + formatId + ", quality=" + quality + ")");

    // 1. If user has provided a Qobuz subscription token, fetch full stream
    if (userToken) {
        var fileUrlEndpoint = BASE_API + "/track/getFileUrl?track_id=" + encodeURIComponent(trackId) +
            "&format_id=" + formatId +
            "&user_auth_token=" + encodeURIComponent(userToken) +
            "&app_id=" + appId;

        try {
            var resp = await fetch(fileUrlEndpoint, {
                method: "GET",
                headers: {
                    "Accept": "application/json",
                    "User-Agent": "YZMusic/1.04.03"
                }
            });

            if (resp.ok) {
                var fileData = resp.json();
                if (fileData && fileData.url) {
                    var actualBitDepth = fileData.bit_depth || (formatId >= 6 ? 24 : 16);
                    var actualSampleRate = (fileData.sampling_rate ? fileData.sampling_rate * 1000 : (actualBitDepth >= 24 ? 96000.0 : 44100.0));
                    var isLossless = (fileData.mime_type === "audio/flac" || formatId >= 6);

                    return {
                        streamUrl: fileData.url,
                        track: {
                            id: String(trackId),
                            audioQuality: isLossless ? "LOSSLESS" : "HIGH",
                            mimeType: fileData.mime_type || (isLossless ? "audio/flac" : "audio/mpeg"),
                            bitDepth: isLossless ? actualBitDepth : null,
                            sampleRate: actualSampleRate,
                            audioModes: ["STEREO"]
                        }
                    };
                }
            }
        } catch (e) {
            console.warn("[Qobuz] Authenticated stream request error: " + e);
        }
    }

    // 2. Unauthenticated lookup: retrieve track metadata and authorized sample stream
    var trackInfoUrl = BASE_API + "/track/get?track_id=" + encodeURIComponent(trackId) + "&app_id=" + appId;
    var trackResp;
    try {
        trackResp = await fetch(trackInfoUrl, {
            method: "GET",
            headers: {
                "Accept": "application/json",
                "User-Agent": "YZMusic/1.04.03"
            }
        });
    } catch (e) {
        throw new Error("Failed to reach Qobuz track metadata: " + e);
    }

    if (!trackResp.ok) {
        throw new Error("Qobuz track " + trackId + " not found (HTTP " + trackResp.status + ")");
    }

    var trackInfo = trackResp.json();
    if (!trackInfo) {
        throw new Error("Invalid response from Qobuz for track " + trackId);
    }

    var bitDepth = trackInfo.maximum_bit_depth || (trackInfo.hires ? 24 : 16);
    var sampleRateKhz = trackInfo.maximum_sampling_rate || 44.1;
    var sampleRateHz = sampleRateKhz * 1000;

    // Check for sample / preview stream URL provided by the catalog API
    var streamUrl = trackInfo.sample_url;
    if (!streamUrl) {
        // If strict lossless is demanded and no sample/token is available, report requirement cleanly
        var fallbackMode = (context && context.settings && context.settings.fallbackMode && context.settings.fallbackMode.value) || "flexible";
        if (fallbackMode === "strict" && !userToken) {
            throw new Error("Qobuz full-length FLAC streaming requires a Qobuz user token. Please configure userToken in settings.");
        }
        throw new Error("No authorized playable stream available for Qobuz track " + trackId);
    }

    var isFlacSample = streamUrl.toLowerCase().indexOf(".flac") !== -1;
    return {
        streamUrl: streamUrl,
        track: {
            id: String(trackId),
            audioQuality: isFlacSample ? "LOSSLESS" : "HIGH",
            mimeType: isFlacSample ? "audio/flac" : "audio/mpeg",
            bitDepth: isFlacSample ? bitDepth : null,
            sampleRate: isFlacSample ? sampleRateHz : 44100.0,
            audioModes: ["STEREO"]
        }
    };
}

module.exports = {
    searchTracks: searchTracks,
    getTrackStreamUrl: getTrackStreamUrl
};
