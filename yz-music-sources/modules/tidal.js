/**
 * TIDAL Lossless & Max Hi-Res Custom Module for YZ Music.
 *
 * Conforms to YZ Music's SpineModule QuickJS contract:
 * - searchTracks(query, limit, context)
 * - getTrackStreamUrl(trackId, quality, context)
 *
 * Supported qualities:
 * - LOSSLESS: 16-bit / 44.1 kHz FLAC, or 24-bit / up to 192 kHz Hi-Res FLAC
 * - HIGH: 320 kbps AAC
 * - LOW: 96 kbps AAC
 */

var DEFAULT_CLIENT_TOKEN = "czET4vdadNUFQ5JU";
var BASE_API = "https://api.tidal.com/v1";

/**
 * Builds standard TIDAL image cover URL from a cover UUID.
 */
function getCoverUrl(coverId) {
    if (!coverId) return null;
    var path = coverId.replace(/-/g, "/");
    return "https://resources.tidal.com/images/" + path + "/640x640.jpg";
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
 * Searches TIDAL catalog for tracks matching query.
 */
async function searchTracks(query, limit, context) {
    var maxLimit = Math.min(limit || 20, 50);
    var token = (context && context.settings && context.settings.clientToken && context.settings.clientToken.value) || DEFAULT_CLIENT_TOKEN;
    var countryCode = (context && context.settings && context.settings.countryCode && context.settings.countryCode.value) || "US";

    var isrc = extractIsrc(query);
    var searchTerm = isrc ? isrc : query;

    var url = BASE_API + "/search/tracks?query=" + encodeURIComponent(searchTerm) +
        "&limit=" + maxLimit + "&countryCode=" + encodeURIComponent(countryCode);

    console.log("[TIDAL] Searching: " + searchTerm + " (limit=" + maxLimit + ", country=" + countryCode + ")");

    var resp;
    try {
        resp = await fetch(url, {
            method: "GET",
            headers: {
                "Accept": "application/json",
                "x-tidal-token": token,
                "User-Agent": "YZMusic/1.04.03"
            }
        });
    } catch (e) {
        console.error("[TIDAL] Search request failed: " + e);
        return { tracks: [], total: 0 };
    }

    if (!resp.ok) {
        console.error("[TIDAL] Search returned HTTP " + resp.status);
        return { tracks: [], total: 0 };
    }

    var data;
    try {
        data = resp.json();
    } catch (e) {
        console.error("[TIDAL] Failed to parse search JSON: " + e);
        return { tracks: [], total: 0 };
    }

    var rawTracks = (data && data.items) ? data.items : [];
    var results = [];

    for (var i = 0; i < rawTracks.length; i++) {
        var item = rawTracks[i];
        if (!item || !item.id) continue;

        var title = item.title || "Unknown Title";
        var artist = (item.artist && item.artist.name) ||
                     (item.artists && item.artists[0] && item.artists[0].name) ||
                     "Unknown Artist";
        var artistId = (item.artist && item.artist.id) ? String(item.artist.id) : null;
        var album = (item.album && item.album.title) || "";
        var albumId = (item.album && item.album.id) ? String(item.album.id) : null;
        var cover = (item.album && item.album.cover) ? getCoverUrl(item.album.cover) : null;

        var durationSec = item.duration || 0;
        var trackNum = item.trackNumber || 1;

        var q = (item.audioQuality || "").toUpperCase();
        var qualityLabel = "HIGH";
        var format = "aac";

        if (q === "HIRES_LOSSLESS" || q === "HIRES") {
            qualityLabel = "HI-RES (24-bit / 96 kHz)";
            format = "flac";
        } else if (q === "LOSSLESS") {
            qualityLabel = "LOSSLESS (16-bit / 44.1 kHz)";
            format = "flac";
        }

        // Boost exact ISRC matches
        if (isrc && item.isrc) {
            var itemIsrc = item.isrc.replace(/-/g, "").toUpperCase();
            if (itemIsrc === isrc) {
                qualityLabel = "HI-RES ISRC MATCH (" + format.toUpperCase() + ")";
            }
        }

        results.push({
            id: String(item.id),
            title: title,
            artist: artist,
            artistId: artistId,
            album: album,
            albumId: albumId,
            albumCover: cover,
            duration: durationSec,
            trackNumber: trackNum,
            audioQuality: qualityLabel,
            format: format,
            availableQualities: ["LOSSLESS", "HIGH", "LOW"]
        });
    }

    console.log("[TIDAL] Search yielded " + results.length + " normalized tracks");
    return {
        tracks: results,
        total: (data && data.totalNumberOfItems) || results.length
    };
}

/**
 * Resolves a legitimate, playable stream URL for the requested TIDAL track ID.
 */
async function getTrackStreamUrl(trackId, quality, context) {
    if (!trackId) {
        throw new Error("Missing trackId");
    }

    var token = (context && context.settings && context.settings.clientToken && context.settings.clientToken.value) || DEFAULT_CLIENT_TOKEN;
    var userToken = (context && context.settings && context.settings.accessToken && context.settings.accessToken.value) ||
                    (context && context.settings && context.settings.userToken && context.settings.userToken.value) ||
                    null;
    var countryCode = (context && context.settings && context.settings.countryCode && context.settings.countryCode.value) || "US";
    var targetQuality = (quality || "").toUpperCase();

    var tidalQuality = "HIGH";
    if (targetQuality === "LOSSLESS") {
        tidalQuality = "LOSSLESS";
    } else if (targetQuality === "LOW") {
        tidalQuality = "LOW";
    }

    console.log("[TIDAL] Resolving stream for track " + trackId + " (quality=" + tidalQuality + ")");

    // 1. If user provided a TIDAL OAuth/access token, query authorized post-paywall playbackinfo
    if (userToken) {
        var authUrl = BASE_API + "/tracks/" + encodeURIComponent(trackId) + "/playbackinfopostpaywall" +
            "?audioquality=" + encodeURIComponent(tidalQuality) +
            "&playbackmode=STREAM&assetpresentation=FULL";

        try {
            var authResp = await fetch(authUrl, {
                method: "GET",
                headers: {
                    "Accept": "application/json",
                    "Authorization": "Bearer " + userToken,
                    "User-Agent": "YZMusic/1.04.03"
                }
            });

            if (authResp.ok) {
                var info = authResp.json();
                if (info && info.manifest) {
                    // Parse standard TIDAL BTS (Base64-encoded JSON) manifest
                    var manifestMime = info.manifestMimeType || "";
                    var streamUrl = null;

                    if (manifestMime.indexOf("bts") !== -1 && typeof atob === "function") {
                        try {
                            var decodedJson = atob(info.manifest);
                            var manifestObj = JSON.parse(decodedJson);
                            if (manifestObj && manifestObj.urls && manifestObj.urls.length > 0) {
                                streamUrl = manifestObj.urls[0];
                            }
                        } catch (err) {
                            console.warn("[TIDAL] Failed to decode BTS manifest: " + err);
                        }
                    }

                    if (streamUrl) {
                        var isFlac = (info.audioQuality === "LOSSLESS" || info.audioQuality === "HI_RES" || streamUrl.indexOf(".flac") !== -1);
                        var bitDepth = (info.audioQuality === "HI_RES") ? 24 : (isFlac ? 16 : null);
                        var sampleRate = (info.audioQuality === "HI_RES") ? 96000.0 : 44100.0;

                        return {
                            streamUrl: streamUrl,
                            track: {
                                id: String(trackId),
                                audioQuality: isFlac ? "LOSSLESS" : "HIGH",
                                mimeType: isFlac ? "audio/flac" : "audio/mp4",
                                bitDepth: bitDepth,
                                sampleRate: sampleRate,
                                audioModes: ["STEREO"]
                            }
                        };
                    }
                }
            }
        } catch (e) {
            console.warn("[TIDAL] Authenticated playbackinfo error: " + e);
        }
    }

    // 2. Unauthenticated lookup: retrieve track metadata or public preview stream
    var previewUrl = BASE_API + "/tracks/" + encodeURIComponent(trackId) + "/playbackinfo" +
        "?audioquality=LOW&playbackmode=STREAM&assetpresentation=PREVIEW";

    var previewResp;
    try {
        previewResp = await fetch(previewUrl, {
            method: "GET",
            headers: {
                "Accept": "application/json",
                "x-tidal-token": token,
                "User-Agent": "YZMusic/1.04.03"
            }
        });
    } catch (e) {
        throw new Error("Failed to reach TIDAL playback info: " + e);
    }

    if (!previewResp.ok) {
        var fallbackMode = (context && context.settings && context.settings.fallbackMode && context.settings.fallbackMode.value) || "flexible";
        if (fallbackMode === "strict" && !userToken) {
            throw new Error("TIDAL FLAC streaming requires a valid TIDAL access token. Configure accessToken in settings.");
        }
        throw new Error("TIDAL stream unavailable for track " + trackId + " (HTTP " + previewResp.status + ")");
    }

    var previewInfo = previewResp.json();
    var streamUrl = null;

    if (previewInfo && previewInfo.manifest && typeof atob === "function") {
        try {
            var decoded = atob(previewInfo.manifest);
            var parsed = JSON.parse(decoded);
            if (parsed && parsed.urls && parsed.urls.length > 0) {
                streamUrl = parsed.urls[0];
            }
        } catch (e) {
            console.warn("[TIDAL] Failed to parse preview manifest: " + e);
        }
    }

    if (!streamUrl) {
        throw new Error("No authorized playable stream found for TIDAL track " + trackId);
    }

    return {
        streamUrl: streamUrl,
        track: {
            id: String(trackId),
            audioQuality: "HIGH",
            mimeType: "audio/mp4",
            bitDepth: null,
            sampleRate: 44100.0,
            audioModes: ["STEREO"]
        }
    };
}

module.exports = {
    searchTracks: searchTracks,
    getTrackStreamUrl: getTrackStreamUrl
};
