/*
 * Vendored verbatim from NewPipeExtractor v0.26.3 (GPL-3.0-or-later), the version
 * pinned in app/build.gradle.kts, and then changed in exactly two places:
 * [encodeUrlUtf8] and [decodeUrlUtf8]. Nothing else in this file differs from
 * upstream, deliberately — see the note on those two methods for why the copy
 * exists at all, and keep any future edits confined to them so the file stays
 * diffable against the tag it came from.
 *
 * This copy does not merely sit alongside the jar's: app/build.gradle.kts strips
 * Utils.class out of the NewPipeExtractor artifact, because two definitions of one
 * class cannot both reach dex merging in a release build. Removing that strip task
 * breaks prodRelease, not just this file.
 *
 * Upstream:
 * https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.3/extractor/src/main/java/org/schabi/newpipe/extractor/utils/Utils.java
 */
package org.schabi.newpipe.extractor.utils;

import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class Utils {
    public static final String HTTP = "http://";
    public static final String HTTPS = "https://";
    private static final Pattern M_PATTERN = Pattern.compile("(https?)?://m\\.");
    private static final Pattern WWW_PATTERN = Pattern.compile("(https?)?://www\\.");

    private Utils() {
        // no instance
    }

    /**
     * Encodes a string to URL format using the UTF-8 character set.
     *
     * <p>
     * Upstream calls {@code URLEncoder.encode(String, Charset)}. That overload is Java 10 and
     * reached Android only in API 33, so on anything older the call fails to link and the VM
     * throws {@link NoSuchMethodError} the first time it runs — which killed the process rather
     * than failing the track, because an Error is not an Exception and nothing on the playback
     * path was catching it. Neither D8's backported-method list nor {@code desugar_jdk_libs}
     * covers {@code java.net}, so there is no build-level fix to switch on; the call site itself
     * has to change. The {@code (String, String)} overload below has existed since API 1 and does
     * the same work — it only differs in announcing a checked exception for a charset that is
     * required to be present on every JVM.
     * </p>
     *
     * @param string The string to be encoded.
     * @return The encoded URL.
     */
    public static String encodeUrlUtf8(final String string) {
        try {
            return URLEncoder.encode(string, StandardCharsets.UTF_8.name());
        } catch (final UnsupportedEncodingException e) {
            // UTF-8 is guaranteed by the platform, so this is unreachable.
            throw new IllegalStateException("UTF-8 is not supported", e);
        }
    }

    /**
     * Decodes a URL using the UTF-8 character set.
     *
     * <p>
     * Same substitution, and same reason, as {@link #encodeUrlUtf8(String)}.
     * </p>
     *
     * @param url The URL to be decoded.
     * @return The decoded URL.
     */
    public static String decodeUrlUtf8(final String url) {
        try {
            return URLDecoder.decode(url, StandardCharsets.UTF_8.name());
        } catch (final UnsupportedEncodingException e) {
            // UTF-8 is guaranteed by the platform, so this is unreachable.
            throw new IllegalStateException("UTF-8 is not supported", e);
        }
    }

    /**
     * Remove all non-digit characters from a string.
     *
     * <p>
     * Examples:
     * </p>
     *
     * <ul>
     *     <li>1 234 567 views -&gt; 1234567</li>
     *     <li>$31,133.124 -&gt; 31133124</li>
     * </ul>
     *
     * @param toRemove string to remove non-digit chars
     * @return a string that contains only digits
     */
    @Nonnull
    public static String removeNonDigitCharacters(@Nonnull final String toRemove) {
        return toRemove.replaceAll("\\D+", "");
    }

    /**
     * Convert a mixed number word to a long.
     *
     * <p>
     * Examples:
     * </p>
     *
     * <ul>
     *     <li>123 -&gt; 123</li>
     *     <li>1.23K -&gt; 1230</li>
     *     <li>1.23M -&gt; 1230000</li>
     * </ul>
     *
     * @param numberWord string to be converted to a long
     * @return a long
     */
    public static long mixedNumberWordToLong(final String numberWord)
            throws NumberFormatException, ParsingException {
        String multiplier = "";
        try {
            multiplier = Parser.matchGroup("[\\d]+([\\.,][\\d]+)?([KMBkmb])+", numberWord, 2);
        } catch (final ParsingException ignored) {
        }
        final double count = Double.parseDouble(
                Parser.matchGroup1("([\\d]+([\\.,][\\d]+)?)", numberWord).replace(",", "."));
        switch (multiplier.toUpperCase()) {
            case "K":
                return (long) (count * 1e3);
            case "M":
                return (long) (count * 1e6);
            case "B":
                return (long) (count * 1e9);
            default:
                return (long) (count);
        }
    }

    /**
     * Check if the url matches the pattern.
     *
     * @param pattern the pattern that will be used to check the url
     * @param url     the url to be tested
     */
    public static void checkUrl(final String pattern, final String url) throws ParsingException {
        checkUrl(Pattern.compile(pattern), url);
    }

    /**
     * Check if the url matches the pattern.
     *
     * @param pattern the pattern that will be used to check the url
     * @param url     the url to be tested
     */
    public static void checkUrl(final Pattern pattern, final String url) throws ParsingException {
        if (isNullOrEmpty(url)) {
            throw new IllegalArgumentException("Url can't be null or empty");
        }

        if (!Parser.isMatch(pattern, url.toLowerCase())) {
            throw new ParsingException("Url doesn't match the pattern");
        }
    }

    public static String replaceHttpWithHttps(final String url) {
        if (url == null) {
