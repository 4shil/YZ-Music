# ===========================================================================
# YZ MUSIC — R8 / PROGUARD OPTIMIZATION & PINPOINT KEEP RULES
# ===========================================================================

# Preserve line numbers and source files for readable stacktraces and debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve runtime annotations, generic signatures, inner class attributes and exceptions
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ---------------------------------------------------------------------------
# 1. Native Methods & JNI
# ---------------------------------------------------------------------------
-keepclasseswithmembers class * {
    native <methods>;
}

# Automix DSP Analyzer JNI front-end
-keep class com.music.yzmusic.playback.smart.AudioAnalysis$* { *; }
-keep class com.music.yzmusic.playback.smart.MelSpectrogram$* { *; }
-keep class com.music.yzmusic.playback.smart.VocalSpectrogram$* { *; }
-keep class com.music.yzmusic.playback.smart.TrackFeatures$* { *; }

# ---------------------------------------------------------------------------
# 2. Mozilla Rhino Engine (used by NewPipeExtractor for YouTube JS deciphering)
# ---------------------------------------------------------------------------
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-dontwarn org.mozilla.javascript.**

# ---------------------------------------------------------------------------
# 3. NewPipeExtractor, NanoJSON & Jsoup
# ---------------------------------------------------------------------------
-keep class org.schabi.newpipe.extractor.** { *; }
-keep interface org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ---------------------------------------------------------------------------
# 4. Ktor & kotlinx.serialization
# ---------------------------------------------------------------------------
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$$serializer {
    *;
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    *;
}
-keep class com.music.yzmusic.innertube.models.** { *; }
-keep class com.music.yzmusic.data.models.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------------------
# 5. QuickJS (JavaScript execution engine for style source plugins)
# ---------------------------------------------------------------------------
-keep class io.github.dokar3.quickjs.** { *; }
-dontwarn io.github.dokar3.quickjs.**

# ---------------------------------------------------------------------------
# 6. ONNX Runtime & JNI bindings
# ---------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ---------------------------------------------------------------------------
# 7. Media3 / ExoPlayer
# ---------------------------------------------------------------------------
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.datasource.** { *; }
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**

# ---------------------------------------------------------------------------
# 8. Coil 3 Image Loading & Palette
# ---------------------------------------------------------------------------
-keep class coil3.** { *; }
-dontwarn coil3.**
-keep class androidx.palette.** { *; }


# ---------------------------------------------------------------------------
# 9. ZXing (party invite QR codes)
# ---------------------------------------------------------------------------
# The encode path is all direct calls (QRCodeWriter → Encoder →
# DefaultGridPlacement), so R8 would in fact trace it correctly on its own. The
# rule is here to make that a guarantee rather than a property of R8's
# whole-program analysis today.
#
# The QR *decoder* rides along under this rule, which is not what it is for —
# nothing in the app reads a code; the other phone's camera does that, by
# pointing a lens at a link. It is left in deliberately. Pruning it to a
# hand-picked list of the ~20 classes the encoder actually reaches saves a few
# hundred KB against a ~53 MB APK, and buys a release-only NoClassDefFoundError
# in the encoder if that list is ever one class short — a failure where a
# minified build and a debug build disagree and no local test would catch it.
-keep class com.google.zxing.qrcode.** { *; }
-keep class com.google.zxing.common.** { *; }
-keep interface com.google.zxing.Writer { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
# The rest of the artifact is unused, and the AWT/imageio-based helpers in it
# have no Android equivalent — R8 would warn about every one of them.
-dontwarn com.google.zxing.**

# ---------------------------------------------------------------------------
# 10. UI Frameworks & Utilities
# ---------------------------------------------------------------------------
-dontwarn dev.chrisbanes.haze.**
-dontwarn com.halilibo.richtext.**