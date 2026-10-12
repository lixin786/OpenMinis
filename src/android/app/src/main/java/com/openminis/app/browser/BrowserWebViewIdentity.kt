package com.openminis.app.browser

import android.content.Context
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat

/**
 * [T-fp-v7-ua-identity] Single source of truth for the WebView's own identity.
 *
 * Why this exists (AUDIT2, letters id=63/73/75):
 *  - The default "Mobile Chrome (Android)" preset used to hardcode
 *    `Android 14; Pixel 8 … Chrome/134.0.0.0`. With no fingerprint account
 *    selected, every tab then claimed to be a Pixel 8 while platform, timezone,
 *    locale, core count and GPU were the real device's — a cross-checkable
 *    contradiction.
 *  - Chrome 110+ froze the Android UA: real Chrome/WebView sends
 *    `Mozilla/5.0 (Linux; Android 10; K) … Chrome/<realMajor>.0.0.0 …`, with the
 *    model/Android version moved into Client Hints. A UA that still embeds a
 *    model while claiming Chrome/134 is a pre-reduction string, i.e. inconsistent
 *    with the engine, and its Chrome major must never sit below the engine's.
 *
 * So: the mobile UA is the reduced form built from the REAL WebView major, and
 * device identity lives in UA-CH (`model`, `platformVersion`) + GPU/screen,
 * exactly as it does on a real device. Nothing here impersonates a different
 * device; when a fingerprint profile is active it supplies the model/GPU/screen
 * through those hints, not through the UA string.
 */
object BrowserWebViewIdentity {

    private const val FALLBACK_MAJOR = 130

    @Volatile private var cachedMajor: Int? = null
    @Volatile private var cachedMobileUa: String? = null

    /** Real WebView (Chrome) major version, e.g. 113 or 143. */
    fun chromeMajor(context: Context?): Int {
        cachedMajor?.let { return it }
        val fromPackage = context?.let { ctx ->
            runCatching { WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName }.getOrNull()
        }
        val major = fromPackage?.substringBefore('.')?.toIntOrNull()
            ?: runCatching {
                // Last resort: parse the WebView's own default UA string.
                @Suppress("DEPRECATION") WebSettings.getDefaultUserAgent(context).let { ua ->
                    Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1)?.toIntOrNull()
                }
            }.getOrNull()
            ?: FALLBACK_MAJOR
        cachedMajor = major
        return major
    }

    /** Reduced Android UA (frozen "Android 10; K"), real engine major. */
    fun mobileUserAgent(context: Context?): String {
        cachedMobileUa?.let { return it }
        val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${chromeMajor(context)}.0.0.0 Mobile Safari/537.36"
        cachedMobileUa = ua
        return ua
    }

    /** Desktop Chrome UA, same engine major (an explicit user choice, not a device claim). */
    fun desktopUserAgent(context: Context?): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${chromeMajor(context)}.0.0.0 Safari/537.36"

    /**
     * Rewrites `Chrome/<n>` in [ua] to the real engine major when [ua] is behind
     * it, so a stored profile can never advertise an engine older than the one
     * actually rendering the page (AUDIT2 P0-3 "版本倒挂").
     */
    fun withEngineMajor(ua: String, context: Context?): String {
        val real = chromeMajor(context)
        val m = Regex("Chrome/(\\d+)(?:\\.0\\.0\\.0)?").find(ua) ?: return ua
        val current = m.groupValues[1].toIntOrNull() ?: return ua
        if (current >= real) return ua
        return ua.replaceRange(m.range, "Chrome/$real.0.0.0")
    }
}
