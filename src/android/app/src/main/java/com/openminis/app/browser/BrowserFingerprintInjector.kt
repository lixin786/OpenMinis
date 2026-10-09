package com.openminis.app.browser

import org.json.JSONObject

/**
 * [T-android-browser-fingerprint] Builds the document-start JS that applies a
 * [BrowserFingerprintProfile] to a WebView page.
 *
 * How it is applied: callers should install the script via
 * `WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))` from
 * androidx.webkit (already a dependency: `androidx.webkit:webkit:1.12.1`).
 * Document-start matters: a page that reads navigator/screen/canvas before our
 * patch runs would otherwise see the real device. If androidx.webkit is not
 * available on a given minSdk path, fall back to injecting the same JS at the
 * top of `WebViewClient.onPageStarted` — weaker, because inline page scripts may
 * run first, but better than nothing.
 *
 * A NOTE ON STRENGTH (read before trusting it): this is a JS-layer spoof. It
 * defeats ordinary fingerprinting scripts, which all read through these same JS
 * APIs. It does NOT defeat:
 *   - native/protobuf-level probes (few sites do this),
 *   - the real exit IP (handled outside the app),
 *   - the process-wide cookie jar (Android limitation).
 * A C++-level engine patch (what Camoufox does to Firefox) is strictly stronger,
 * but WebView cannot be patched that way. This is the ceiling achievable inside
 * a stock Android WebView, and it is honest to say so.
 */
object BrowserFingerprintInjector {

    /** The whole injection, parameterised by the profile's JSON. */
    fun script(profile: BrowserFingerprintProfile): String {
        val cfg = profile.toJson()
        val json = cfg.toString()
        val tzOffset = profile.timezoneOffsetMin
        val seed = profile.canvasSeed
        return buildString {
            append("(function(){\n")
            append("'use strict';\n")
            append("if (window.__minisFpApplied) return; window.__minisFpApplied = true;\n")
            append("var CFG = ").append(json).append(";\n")
            append(header())
            append(navigator())
            append(screen())
            append(timezone(tzOffset))
            append(canvas(seed))
            append(webgl())
            append(audio(seed))
            append("})();\n")
        }
    }

    private fun header() = """
// ---- helpers ----
function def(obj, prop, value){
  try { Object.defineProperty(obj, prop, { get: function(){ return value; },
        configurable: true }); } catch(e){ try { obj[prop] = value; } catch(_){} }
}
function defFn(obj, prop, fn){
  try { Object.defineProperty(obj, prop, { value: fn, configurable: true, writable: true }); }
  catch(e){ try { obj[prop] = fn; } catch(_){} }
}
"""

    private fun navigator() = """
// ---- navigator ----
try {
  var nav = navigator;
  def(nav, 'userAgent', CFG.userAgent);
  def(nav, 'appVersion', CFG.userAgent.replace('Mozilla/', ''));
  def(nav, 'platform', CFG.platform);
  def(nav, 'oscpu', CFG.oscpu);
  def(nav, 'languages', Object.freeze(CFG.languages.slice()));
  def(nav, 'language', CFG.languages[0]);
  def(nav, 'hardwareConcurrency', CFG.hardwareConcurrency);
  def(nav, 'deviceMemory', CFG.deviceMemory);
  def(nav, 'maxTouchPoints', CFG.maxTouchPoints);
  def(nav, 'webdriver', false);
  def(nav, 'pdfViewerEnabled', true);
  if (nav.userAgentData) {
    try { def(nav, 'userAgentData', undefined); } catch(e){}
  }
} catch(e){}
"""

    private fun screen() = """
// ---- screen / window ----
try {
  var scr = screen, win = window;
  def(scr, 'width', CFG.screenWidth);
  def(scr, 'height', CFG.screenHeight);
  def(scr, 'availWidth', CFG.availWidth);
  def(scr, 'availHeight', CFG.availHeight);
  def(scr, 'colorDepth', CFG.colorDepth);
  def(scr, 'pixelDepth', CFG.pixelDepth);
  def(win, 'devicePixelRatio', CFG.devicePixelRatio || 2.625);
  def(win, 'outerWidth', CFG.screenWidth);
  def(win, 'outerHeight', CFG.screenHeight);
} catch(e){}
"""

    private fun timezone(offsetMin: Int) = """
// ---- timezone (best-effort: getTimezoneOffset + Intl resolvedOptions) ----
try {
  var TZ = CFG.timezone, OFF = $offsetMin;
  defFn(Date.prototype, 'getTimezoneOffset', function(){ return OFF; });
  var RealDTF = Intl.DateTimeFormat;
  function PatchedDTF(){
    var args = Array.prototype.slice.call(arguments);
    var loc = args[0], opts = args[1] || {};
    if (typeof loc === 'object') { opts = loc; loc = undefined; }
    opts = Object.assign({}, opts, { timeZone: TZ });
    return new RealDTF(loc, opts);
  }
  PatchedDTF.prototype = RealDTF.prototype;
  PatchedDTF.supportedLocalesOf = RealDTF.supportedLocalesOf.bind(RealDTF);
  try { Object.defineProperty(PatchedDTF, 'name', { value: 'DateTimeFormat' }); } catch(e){}
  defFn(Intl, 'DateTimeFormat', PatchedDTF);
  var rro = Intl.DateTimeFormat.prototype.resolvedOptions;
  defFn(Intl.DateTimeFormat.prototype, 'resolvedOptions', function(){
    var r = rro.call(this); try { r.timeZone = TZ; } catch(e){} return r;
  });
} catch(e){}
"""

    // Deterministic per-profile canvas farbling: same account -> same values on
    // every launch, different accounts -> different values. This mirrors the
    // per-profile farbling validated on the Camoufox work (2026-10-09).
    //
    // IMPORTANT: perturb only RETURNED data, never write back to the source
    // canvas. Writing back (putImageData onto `this`) makes the canvas drift —
    // each call re-perturbs the already-perturbed pixels, so the same profile
    // is no longer reproducible. Draw into a fresh offscreen copy instead.
    private fun canvas(seed: Long) = """
// ---- canvas farbling (deterministic per profile) ----
try {
  var SEED = $seed;
  function prng(n){ var x = Math.sin(n * 12.9898 + SEED * 78.233) * 43758.5453;
                    return x - Math.floor(x); }
  function perturb(data){
    for (var i = 0; i < data.length; i += 4){
      var r = prng(i);
      if (r < 0.03){
        var d = (r < 0.015) ? 1 : -1;
        data[i]   = Math.max(0, Math.min(255, data[i]   + d));
        data[i+1] = Math.max(0, Math.min(255, data[i+1] - d));
      }
    }
    return data;
  }
  var proto = CanvasRenderingContext2D.prototype;
  var ORIG_GID = proto.getImageData;
  var ORIG_TDU = HTMLCanvasElement.prototype.toDataURL;
  var ORIG_TBB = HTMLCanvasElement.prototype.toBlob;
  // Perturb only the returned copy — never mutate the source canvas.
  defFn(proto, 'getImageData', function(){
    var d = ORIG_GID.apply(this, arguments);
    try { perturb(d.data); } catch(e){}
    return d;
  });
  // Build the data URL from an offscreen copy, so repeated calls are stable.
  defFn(HTMLCanvasElement.prototype, 'toDataURL', function(){
    try {
      var w = this.width, h = this.height;
      if (w > 0 && h > 0) {
        var tmp = document.createElement('canvas'); tmp.width = w; tmp.height = h;
        var tc = tmp.getContext('2d');
        tc.drawImage(this, 0, 0);
        var im = ORIG_GID.call(tc, 0, 0, w, h);
        perturb(im.data);
        tc.putImageData(im, 0, 0);
        return ORIG_TDU.apply(tmp, arguments);
      }
    } catch(e){}
    return ORIG_TDU.apply(this, arguments);
  });
  if (ORIG_TBB) defFn(HTMLCanvasElement.prototype, 'toBlob', function(){
    try {
      var w = this.width, h = this.height;
      if (w > 0 && h > 0) {
        var tmp = document.createElement('canvas'); tmp.width = w; tmp.height = h;
        var tc = tmp.getContext('2d');
        tc.drawImage(this, 0, 0);
        var im = ORIG_GID.call(tc, 0, 0, w, h);
        perturb(im.data);
        tc.putImageData(im, 0, 0);
        return ORIG_TBB.apply(tmp, arguments);
      }
    } catch(e){}
    return ORIG_TBB.apply(this, arguments);
  });
} catch(e){}
"""

    private fun webgl() = """
// ---- WebGL vendor/renderer ----
try {
  var VENDOR = CFG.webglVendor, RENDERER = CFG.webglRenderer;
  function patchGetParameter(proto){
    var g = proto.getParameter;
    if (!g) return;
    defFn(proto, 'getParameter', function(p){
      if (p === 37445) return VENDOR;    // UNMASKED_VENDOR_WEBGL
      if (p === 37446) return RENDERER;  // UNMASKED_RENDERER_WEBGL
      return g.apply(this, arguments);
    });
    var ge = proto.getExtension;
    if (ge) defFn(proto, 'getExtension', function(name){
      var r = ge.apply(this, arguments);
      if (name === 'WEBGL_debug_renderer_info' && r) {
        try { Object.defineProperty(r, 'UNMASKED_VENDOR_WEBGL', { value: 37445 }); } catch(e){}
        try { Object.defineProperty(r, 'UNMASKED_RENDERER_WEBGL', { value: 37446 }); } catch(e){}
      }
      return r;
    });
  }
  if (window.WebGLRenderingContext) patchGetParameter(WebGLRenderingContext.prototype);
  if (window.WebGL2RenderingContext) patchGetParameter(WebGL2RenderingContext.prototype);
} catch(e){}
"""

    private fun audio(seed: Long) = """
// ---- AudioContext farbling ----
try {
  var ASEED = $seed;
  function ahash(n){ var x = Math.sin(n * 43.017 + ASEED * 17.31) * 21943.771;
                     return x - Math.floor(x); }
  function perturbFreq(c){
    var f = c.getChannelData ? c.getChannelData(0) : null;
    if (!f) return;
    for (var i = 0; i < f.length; i += 997){
      if (ahash(i) < 0.02) f[i] = f[i] + (ahash(i+1) - 0.5) * 1e-6;
    }
  }
  var AC = window.AudioContext || window.webkitAudioContext;
  if (AC) {
    var gfd = AnalyserNode && AnalyserNode.prototype.getFloatFrequencyData;
    if (gfd) defFn(AnalyserNode.prototype, 'getFloatFrequencyData', function(arr){
      gfd.apply(this, arguments);
      for (var i = 0; i < arr.length; i += 991){
        if (ahash(i) < 0.02) arr[i] = arr[i] + (ahash(i+2) - 0.5) * 1e-3;
      }
    });
  }
} catch(e){}
"""
}
