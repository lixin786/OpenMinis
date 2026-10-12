package com.openminis.app.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * [T-android-browser-fingerprint] A per-account browser identity.
 *
 * Minis' built-in browser is an Android WebView: one process-wide CookieManager
 * and one real device fingerprint. That is fine for single-user browsing, but
 * when several accounts must not be linked, each context needs its own stable
 * identity. A BrowserFingerprintProfile is that identity: it is drawn ONCE per
 * account and reused on every launch, so a returning "same device" keeps
 * reproducing the same values (a fresh random identity each launch is itself a
 * signal — see daijro/camoufox 'identity_seed' reasoning).
 *
 * What this CAN isolate: canvas, WebGL vendor/renderer, audio, screen, timezone,
 * locale, UA, hardwareConcurrency, deviceMemory, webdriver flag.
 *
 * What it CANNOT isolate (documented limits, not bugs):
 *  - Cookies / localStorage: Android's CookieManager is a process-wide singleton.
 *    All tabs share one cookie jar. True per-account cookie isolation would need
 *    separate WebView data directories, which WebView does not expose per-instance.
 *  - Network exit IP: WebView has no per-instance proxy. Android's ProxyController
 *    is process-global. IP must be handled outside the app (system/VPN-level).
 * These two are the real correlation vectors; the fingerprint layer removes the
 * third (device signature). See README-limits.md.
 */
data class BrowserFingerprintProfile(
    val id: String,
    val label: String,
    val userAgent: String,
    val platform: String,            // navigator.platform
    val oscpu: String,               // navigator.oscpu
    val screenWidth: Int,
    val screenHeight: Int,
    val availWidth: Int,
    val availHeight: Int,
    val colorDepth: Int,
    val pixelDepth: Int,
    // [T-fp-f8] Per-account DPR. Previously the JS hard-fell-back to 2.625 for
    // every account, which made the field useless as a differentiator and left
    // devicePixelRatio / screen / matchMedia mutually inconsistent.
    val devicePixelRatio: Double,
    // [T-fp-f6] UA-CH (navigator.userAgentData / Sec-CH-UA) needs a coherent
    // device story: the brand-list version must match the UA's Chrome major and
    // platformVersion/model must match the template, so a stubbed
    // getHighEntropyValues() can answer consistently.
    val deviceModel: String,
    val androidVersion: String,
    val timezone: String,            // IANA, e.g. "America/New_York"
    val timezoneOffsetMin: Int,      // minutes to add to UTC (JS getTimezoneOffset sign)
    val locale: String,              // e.g. "en-US"
    val languages: List<String>,
    val hardwareConcurrency: Int,
    val deviceMemory: Int,           // GB
    val webglVendor: String,
    val webglRenderer: String,
    val canvasSeed: Long,            // deterministic canvas/audio farbling seed
    val maxTouchPoints: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("label", label)
        put("userAgent", userAgent); put("platform", platform); put("oscpu", oscpu)
        put("screenWidth", screenWidth); put("screenHeight", screenHeight)
        put("availWidth", availWidth); put("availHeight", availHeight)
        put("colorDepth", colorDepth); put("pixelDepth", pixelDepth)
        put("devicePixelRatio", devicePixelRatio)
        put("deviceModel", deviceModel); put("androidVersion", androidVersion)
        put("timezone", timezone); put("timezoneOffsetMin", timezoneOffsetMin)
        put("locale", locale); put("languages", JSONArray(languages))
        put("hardwareConcurrency", hardwareConcurrency); put("deviceMemory", deviceMemory)
        put("webglVendor", webglVendor); put("webglRenderer", webglRenderer)
        put("canvasSeed", canvasSeed); put("maxTouchPoints", maxTouchPoints)
    }

    companion object {
        fun fromJson(o: JSONObject): BrowserFingerprintProfile {
            val langs = o.optJSONArray("languages")?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: listOf("en-US", "en")
            return BrowserFingerprintProfile(
                id = o.getString("id"), label = o.optString("label", o.getString("id")),
                userAgent = o.getString("userAgent"),
                platform = o.optString("platform", "Linux armv8l"),
                oscpu = o.optString("oscpu", "Linux armv8l"),
                screenWidth = o.optInt("screenWidth", 412),
                screenHeight = o.optInt("screenHeight", 915),
                availWidth = o.optInt("availWidth", 412),
                availHeight = o.optInt("availHeight", 915),
                colorDepth = o.optInt("colorDepth", 24),
                pixelDepth = o.optInt("pixelDepth", 24),
                devicePixelRatio = o.optDouble("devicePixelRatio", 2.625),
                deviceModel = o.optString("deviceModel", ""),
                androidVersion = o.optString("androidVersion", "14"),
                timezone = o.optString("timezone", "UTC"),
                timezoneOffsetMin = o.optInt("timezoneOffsetMin", 0),
                locale = o.optString("locale", "en-US"),
                languages = langs,
                hardwareConcurrency = o.optInt("hardwareConcurrency", 8),
                deviceMemory = o.optInt("deviceMemory", 8),
                webglVendor = o.optString("webglVendor", "Qualcomm"),
                webglRenderer = o.optString("webglRenderer", "Adreno (TM) 640"),
                canvasSeed = o.optLong("canvasSeed", 1L),
                maxTouchPoints = o.optInt("maxTouchPoints", 5),
            )
        }
    }
}

/**
 * [T-android-browser-fingerprint] Registry of fingerprint profiles, persisted
 * as JSON in SharedPreferences so an account's identity survives restarts.
 *
 * The draft draws from a small pool of plausible Android device identities;
 * callers may also add profiles via [upsert] (e.g. imported from a file, or
 * created by the host app's UI). Drawing is seeded by id so the same id always
 * yields the same profile even before it is persisted.
 */
object BrowserFingerprintRegistry {
    private const val PREFS = "minis_browser_fingerprints"
    private const val KEY = "profiles"
    private const val KEY_DEFAULT = "default_profile_id"

    // Plausible REAL Android device pool (screen sizes/DPRs are the values the
    // corresponding real hardware reports to WebView). Kept as data so a
    // profile is reproducible and reviewable — not invented per launch.
    // [T-fp-f7] No artificial width jitter any more: the old `+ ((h/13)%3)*4`
    // produced 416/420px widths that no real phone reports.
    private data class Device(
        // [T-fp-v7-ua-identity] No per-device UA string any more: since Chrome 110
        // the real Android UA is frozen (`Android 10; K`, no model), so the UA is
        // built from the live engine major and the device story lives in UA-CH
        // (model/platformVersion) + GPU/screen - exactly as on real hardware.
        val platform: String, val oscpu: String,
        val gpuVendor: String, val gpuRenderer: String,
        val cores: Int, val mem: Int, val touch: Int,
        val w: Int, val h: Int, val dpr: Double,
        val model: String, val androidVer: String,
    )

    // [T-fp-v7-pool] Rebuilt per AUDIT2 (P0-2): every entry is a REAL
    // SoC/GPU/cores/RAM/screen/DPR/Android-version pairing, and the CSS
    // viewport+DPR values are the ones the corresponding hardware reports to
    // WebView (screen-viewport references). The two Chinese models that used to
    // sit here were dropped because their CSS viewports could not be sourced;
    // re-add them once measured on real hardware.
    private val POOL = listOf(
        // Google Pixel 8 - Tensor G3 (9 cores) / Immortalis-G715, 8 GB, 1080x2400.
        Device("Linux armv8l", "Linux armv8l", "Google", "Mali-G715", 9, 8, 5, 412, 915, 2.625, "Pixel 8", "14"),
        // Google Pixel 8a - same SoC class, 8 GB, 1080x2400.
        Device("Linux armv8l", "Linux armv8l", "Google", "Mali-G715", 9, 8, 5, 412, 915, 2.625, "Pixel 8a", "14"),
        // Samsung Galaxy S23 (SM-S911B) - Snapdragon 8 Gen 2 / Adreno 740, 8 GB, 1080x2340.
        Device("Linux armv8l", "Linux armv8l", "Qualcomm", "Adreno (TM) 740", 8, 8, 5, 360, 780, 3.0, "SM-S911B", "13"),
        // Samsung Galaxy A55 5G (SM-A556B) - Exynos 1480 / Xclipse 530, 8 GB, 1080x2340.
        Device("Linux armv8l", "Linux armv8l", "Samsung", "Xclipse 530", 8, 8, 5, 412, 892, 2.625, "SM-A556B", "14"),
        // Samsung Galaxy A36 5G (SM-A366B) - Exynos 1580 / Xclipse 540, 8 GB, 1080x2408.
        Device("Linux armv8l", "Linux armv8l", "Samsung", "Xclipse 540", 8, 8, 5, 412, 918, 2.625, "SM-A366B", "15"),
    )

    // Common residential timezone/locale pairs, so a profile looks like a real
    // user rather than an anonymous datacenter host.
    // [T-fp-f4] The number is the NOMINAL UTC OFFSET in minutes (Berlin = +60,
    // New York = -300). The injected JS derives getTimezoneOffset() and the
    // Date.toString() GMT label from it, both date-aware for DST zones.
    private val LOCALES = listOf(
        Triple("America/New_York", -300, "en-US"),
        Triple("America/Los_Angeles", -420, "en-US"),
        Triple("Europe/London", 0, "en-GB"),
        Triple("Europe/Berlin", 60, "de-DE"),
        Triple("Asia/Tokyo", 540, "ja-JP"),
        Triple("Asia/Singapore", 480, "en-SG"),
    )

    @Volatile private var cache: MutableMap<String, BrowserFingerprintProfile>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(ctx: Context): MutableMap<String, BrowserFingerprintProfile> {
        cache?.let { return it }
        val map = mutableMapOf<String, BrowserFingerprintProfile>()
        runCatching {
            val raw = prefs(ctx).getString(KEY, null) ?: return@runCatching
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val p = BrowserFingerprintProfile.fromJson(arr.getJSONObject(i))
                map[p.id] = p
            }
        }
        cache = map
        return map
    }

    @Synchronized
    private fun persist(ctx: Context, map: Map<String, BrowserFingerprintProfile>) {
        val arr = JSONArray()
        map.values.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** All known profiles. */
    fun list(ctx: Context): List<BrowserFingerprintProfile> = load(ctx).values.toList()

    fun get(ctx: Context, id: String): BrowserFingerprintProfile? = load(ctx)[id]

    fun upsert(ctx: Context, profile: BrowserFingerprintProfile) {
        val map = load(ctx); map[profile.id] = profile; persist(ctx, map)
    }

    fun remove(ctx: Context, id: String) {
        val map = load(ctx); map.remove(id); persist(ctx, map)
        if (defaultId(ctx) == id) prefs(ctx).edit().remove(KEY_DEFAULT).apply()
    }

    fun defaultId(ctx: Context): String? = prefs(ctx).getString(KEY_DEFAULT, null)

    fun setDefault(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_DEFAULT, id).apply()
    }

    /**
     * Get an existing profile by id, or create (and persist) a deterministic
     * one. Deterministic because drawing is keyed off the id's hash — the same
     * account name always maps to the same device/locale, which is the whole
     * point of a stable identity.
     */
    @Synchronized
    fun getOrCreate(ctx: Context, id: String, label: String = id): BrowserFingerprintProfile {
        load(ctx)[id]?.let { return it }
        val p = generate(id, label, ctx)
        upsert(ctx, p)
        return p
    }

    /** Deterministically draw a profile for the given id. */
    fun generate(id: String, label: String = id, context: Context? = null): BrowserFingerprintProfile {
        val h = stableHash(id)
        val dev = POOL[(h % POOL.size).toInt()]
        val loc = LOCALES[((h / 7) % LOCALES.size).toInt()]
        val (tz, offMin, lang) = loc
        val langs = when {
            lang.startsWith("en") -> listOf(lang, "en")
            else -> listOf(lang)
        }
        return BrowserFingerprintProfile(
            id = id, label = label,
            userAgent = BrowserWebViewIdentity.mobileUserAgent(context),
            platform = dev.platform, oscpu = dev.oscpu,
            screenWidth = dev.w, screenHeight = dev.h,
            availWidth = dev.w, availHeight = dev.h - 48,
            colorDepth = 24, pixelDepth = 24,
            devicePixelRatio = dev.dpr,
            deviceModel = dev.model, androidVersion = dev.androidVer,
            timezone = tz, timezoneOffsetMin = offMin, locale = lang, languages = langs,
            hardwareConcurrency = dev.cores, deviceMemory = dev.mem,
            webglVendor = dev.gpuVendor, webglRenderer = dev.gpuRenderer,
            canvasSeed = (h and 0x7fffffff) or 1L,
            maxTouchPoints = dev.touch,
        )
    }

    /** Generate a fresh random id (for a brand-new account). */
    fun newId(): String = "acct-" + UUID.randomUUID().toString().take(8)

    private fun stableHash(s: String): Long {
        // FNV-1a 64-bit — stable across processes, unlike String.hashCode which
        // is also stable but only 32-bit; we want more index entropy.
        var h = -0x340d631b7bdddcdbL
        for (c in s) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return if (h < 0) -h else h
    }
}
