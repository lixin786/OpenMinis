package com.openminis.app.browser

import org.json.JSONObject

/**
 * [T-android-browser-fingerprint] Builds the document-start JS that applies a
 * [BrowserFingerprintProfile] to a WebView page — and, since batch F, to every
 * same-origin child frame too.
 *
 * How it is applied: callers install the script via
 * `WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))` from
 * androidx.webkit (already a dependency: `androidx.webkit:webkit:1.12.1`).
 * Document-start matters: a page that reads navigator/screen/canvas before our
 * patch runs would otherwise see the real device.
 *
 * Batch-F fixes folded into this generator:
 *  - F-01/F-11: `about:blank` (and other frame-created-blank) iframes do NOT
 *    receive the WebView document-start script — measured on-device: same-origin
 *    and srcdoc frames were covered, about:blank was not, and every value inside
 *    it was the real device. The injected script now patches each frame itself
 *    when it is inserted into the DOM (appendChild / insertBefore / replaceChild
 *    / innerHTML), which runs before any child script can read the APIs.
 *  - F-04: `Date.prototype.toString/toTimeString` leaked the real zone
 *    ("GMT+0000 (Greenwich Mean Time)" while getTimezoneOffset said +540) and
 *    `Intl` resolved to the system locale (en-US while navigator.language said
 *    ja-JP). Both are now derived from the profile.
 *  - F-06: `navigator.userAgentData` used to be deleted; every real Chrome has
 *    it, so its absence was itself a tell. It is now a coherent stub whose
 *    brand/version list matches the UA's Chrome major, with a matching
 *    getHighEntropyValues(). The request-header half (Sec-CH-UA) is set on the
 *    WebView via WebSettingsCompat.setUserAgentMetadata in BrowserUseManager.
 *  - F-08: `matchMedia('(resolution: Ndppx)')` answered with the real display
 *    resolution while devicePixelRatio claimed otherwise. Resolution queries are
 *    now evaluated against the profile's DPR, and devicePixelRatio itself comes
 *    from the profile instead of a hard-coded 2.625.
 *  - F-09: `window.chrome` was missing entirely (mobile Chrome always has it).
 *    A minimal loadTimes/csi/app stub is installed.
 *  - F-10: the old guard `window.__minisFpApplied = true` advertised the patch
 *    in `Object.getOwnPropertyNames(window)`. State now lives in a closure-held
 *    WeakSet plus a behavioural check (navigator.userAgent already equals the
 *    profile's), so the window object stays clean.
 *
 * A NOTE ON STRENGTH (read before trusting it): this is a JS-layer spoof. It
 * defeats ordinary fingerprinting scripts, which all read through these same JS
 * APIs. It does NOT defeat:
 *   - native/protobuf-level probes (few sites do this),
 *   - the real exit IP (handled outside the app),
 *   - the process-wide cookie jar (Android limitation),
 *   - system fonts (F-05, accepted for this iteration: JS cannot change the
 *     font list, and every Android device ships the same Noto CJK set, so the
 *     template's locale stays coherent with what the fonts can render).
 * A C++-level engine patch (what Camoufox does to Firefox) is strictly stronger,
 * but WebView cannot be patched that way. This is the ceiling achievable inside
 * a stock Android WebView, and it is honest to say so.
 */
object BrowserFingerprintInjector {

    /** The whole injection, parameterised by the profile's JSON. */
    fun script(profile: BrowserFingerprintProfile): String {
        val cfg = profile.toJson()
        val json = cfg.toString()
        val major = FpUaMetadata.chromeMajor(profile.userAgent)
        return buildString {
            append("(function(){\n")
            append("'use strict';\n")
            append("var CFG = ").append(json).append(";\n")
            append("var TZ = ").append(JSONObject.quote(profile.timezone)).append(";\n")
            append("var TZ_OFF = ").append(profile.timezoneOffsetMin).append(";\n")
            append("var LOCALE = ").append(JSONObject.quote(profile.locale)).append(";\n")
            append("var SEED = ").append(profile.canvasSeed).append(";\n")
            append("var DPR = ").append(profile.devicePixelRatio).append(";\n")
            append("var CHROME_MAJOR = ").append(JSONObject.quote(major)).append(";\n")
            append(engine())
            append("install(window);\n")
            append("hookFrames(window);\n")
            append("})();\n")
        }
    }

    /**
     * Shared UA-CH values: the JS stub and the WebView-level
     * `setUserAgentMetadata` must agree, so both are derived from one place.
     */
    object FpUaMetadata {
        fun chromeMajor(ua: String): String =
            Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1) ?: "134"

        /**
         * [T-fp-v8-ch-brands] Brand list exactly as real Chrome reports it,
         * captured from Chrome 113 on Android 14 during the AUDIT3 control run:
         * order is `Google Chrome, Chromium, <grease>`, and the greased brand is
         * spelled `Not-A.Brand` (the previous "Not A(Brand" was invented).
         */
        fun brands(major: String): List<Pair<String, String>> = listOf(
            "Google Chrome" to major,
            "Chromium" to major,
            "Not-A.Brand" to "24",
        )

        fun fullVersion(major: String): String = "$major.0.0.0"
    }

    // The engine. Written as one plain-JS string on purpose: it is injected
    // verbatim, so keeping it readable here is keeping it debuggable on-device.
    // Kotlin string templates are avoided inside (string concatenation only) to
    // keep "$" out of the JS.
    private fun engine(): String = """
// ---------- helpers ----------
function def(obj, prop, value){
  try { Object.defineProperty(obj, prop, { get: function(){ return value; },
        configurable: true }); } catch(e){ try { obj[prop] = value; } catch(_){} }
}
function defFn(obj, prop, fn){
  try { Object.defineProperty(obj, prop, { value: fn, configurable: true, writable: true }); }
  catch(e){ try { obj[prop] = fn; } catch(_){} }
}
// F-10: no marker on any window object. A page-scoped WeakSet tracks the frames
// we already patched, and a behavioural check guards the document-start-vs-parent
// install race (if navigator.userAgent already reports the profile UA, a patch
// is in place in this realm).
var PATCHED = (typeof WeakSet === 'function') ? new WeakSet() : null;
function mark(w){ try { if (PATCHED) PATCHED.add(w); } catch(e){} }
function marked(w){ try { return !!PATCHED && PATCHED.has(w); } catch(e){ return false; } }
// NB: the host sets the WebView's UA string to the profile UA natively
// (setUserAgentString), so matching the UA alone would look "already patched"
// before the injection ever ran. Require a value only the patch produces.
function already(w){
  try {
    return !!(w.navigator && w.navigator.userAgent === CFG.userAgent
              && w.navigator.platform === CFG.platform);
  } catch(e){ return false; }
}
function pad2(n){ return (n < 10 ? '0' : '') + n; }

// ---------- timezone / date / locale (F-04) ----------
// Offset and zone name are computed FOR THE DATE BEING FORMATTED, not from a
// fixed profile constant: a DST zone must read +0200/CEST in July and
// +0100/CET in January, exactly like a device in that zone. TZ_OFF (the
// profile's nominal UTC offset) is only the fallback if Intl cannot answer.
function offsetMinutesFor(RealDTF, d){
  try {
    var p = new RealDTF('en-US', { timeZone: TZ, timeZoneName: 'longOffset' }).formatToParts(d);
    for (var i = 0; i < p.length; i++) {
      if (p[i].type === 'timeZoneName') {
        var v = p[i].value;
        if (/^GMT$/.test(v)) return 0;
        var m = /GMT([+-])(\d{1,2})(?::?(\d{2}))?/.exec(v);
        if (m) return (m[1] === '-' ? -1 : 1) * (parseInt(m[2], 10) * 60 + parseInt(m[3] || '0', 10));
      }
    }
  } catch(e){}
  return TZ_OFF;
}
function tzOffsetTextFor(RealDTF, d){
  var off = offsetMinutesFor(RealDTF, d);
  var sign = off >= 0 ? '+' : '-';
  var abs = Math.abs(off);
  return 'GMT' + sign + pad2(Math.floor(abs / 60)) + pad2(abs % 60);
}
function tzLongNameFor(RealDTF, d){
  try {
    var p = new RealDTF('en-US', { timeZone: TZ, timeZoneName: 'long' }).formatToParts(d);
    for (var i = 0; i < p.length; i++) if (p[i].type === 'timeZoneName') return p[i].value;
  } catch(e){}
  return 'GMT';
}
function partsInTz(RealDTF, d){
  var f = new RealDTF('en-US', { timeZone: TZ, hour12: false, weekday: 'short',
    year: 'numeric', month: 'short', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit' });
  var out = {};
  var p = f.formatToParts(d);
  for (var i = 0; i < p.length; i++) out[p[i].type] = p[i].value;
  return out;
}
function installTimezone(w){
  var RealDTF = w.Intl.DateTimeFormat;
  var RealDate = w.Date;
  // [T-fp-f4] JS getTimezoneOffset() is "UTC - local" (negative east of
  // Greenwich): Tokyo must report -540, not +540 — and the value is
  // date-dependent in DST zones, so it is computed per call.
  defFn(RealDate.prototype, 'getTimezoneOffset', function(){
    return -offsetMinutesFor(RealDTF, this);
  });
  defFn(RealDate.prototype, 'toString', function(){
    try {
      var p = partsInTz(RealDTF, this);
      return p.weekday + ' ' + p.month + ' ' + p.day + ' ' + p.year + ' ' +
             p.hour + ':' + p.minute + ':' + p.second + ' ' +
             tzOffsetTextFor(RealDTF, this) + ' (' + tzLongNameFor(RealDTF, this) + ')';
    } catch(e){}
    return 'Thu Jan 01 1970 00:00:00 ' + tzOffsetTextFor(RealDTF, this) + ' (' + tzLongNameFor(RealDTF, this) + ')';
  });
  defFn(RealDate.prototype, 'toTimeString', function(){
    try {
      var p = partsInTz(RealDTF, this);
      return p.hour + ':' + p.minute + ':' + p.second + ' ' +
             tzOffsetTextFor(RealDTF, this) + ' (' + tzLongNameFor(RealDTF, this) + ')';
    } catch(e){}
    return '00:00:00 ' + tzOffsetTextFor(RealDTF, this) + ' (' + tzLongNameFor(RealDTF, this) + ')';
  });
  // toLocale* default to the profile locale + timezone when the caller omits them.
  function wrapLocale(name){
    var orig = RealDate.prototype[name];
    if (!orig) return;
    defFn(RealDate.prototype, name, function(){
      var a = Array.prototype.slice.call(arguments);
      if (a[0] === undefined || a[0] === null) a[0] = LOCALE;
      var opts = a[1];
      if (opts === undefined || opts === null) opts = {};
      if (typeof opts === 'object' && !opts.timeZone) {
        opts = Object.assign({}, opts, { timeZone: TZ });
      }
      a[1] = opts;
      return orig.apply(this, a);
    });
  }
  wrapLocale('toLocaleString');
  wrapLocale('toLocaleDateString');
  wrapLocale('toLocaleTimeString');
  // Intl: default locale/timeZone follow the profile; resolvedOptions() reports
  // the profile's locale instead of the system one.
  function PatchedDTF(){
    var args = Array.prototype.slice.call(arguments);
    var loc = args[0], opts = args[1] || {};
    if (typeof loc === 'object' && loc !== null) { opts = loc; loc = undefined; }
    if (loc === undefined || loc === null) loc = LOCALE;
    opts = Object.assign({}, opts, { timeZone: opts.timeZone || TZ });
    return new RealDTF(loc, opts);
  }
  PatchedDTF.prototype = RealDTF.prototype;
  PatchedDTF.supportedLocalesOf = RealDTF.supportedLocalesOf.bind(RealDTF);
  try { Object.defineProperty(PatchedDTF, 'name', { value: 'DateTimeFormat' }); } catch(e){}
  defFn(w.Intl, 'DateTimeFormat', PatchedDTF);
  var rro = RealDTF.prototype.resolvedOptions;
  defFn(RealDTF.prototype, 'resolvedOptions', function(){
    var r = rro.call(this);
    try { r.timeZone = TZ; } catch(e){}
    try { if (r.locale) r.locale = LOCALE; } catch(e){}
    return r;
  });
}

// ---------- UA-CH (F-06) ----------
function installUaCh(w){
  try {
    var nav = w.navigator;
    var major = CHROME_MAJOR;
    var brands = [
      { brand: 'Google Chrome', version: major },
      { brand: 'Chromium', version: major },
      { brand: 'Not-A.Brand', version: '24' }
    ];
    var full = [
      { brand: 'Google Chrome', version: major + '.0.0.0' },
      { brand: 'Chromium', version: major + '.0.0.0' },
      { brand: 'Not-A.Brand', version: '24.0.0.0' }
    ];
    var uad = {
      brands: brands,
      mobile: true,
      platform: 'Android',
      getHighEntropyValues: function(){
        return new Promise(function(resolve){
          resolve({
            architecture: '',
            bitness: '',
            brands: brands,
            fullVersionList: full,
            mobile: true,
            model: CFG.deviceModel || '',
            platform: 'Android',
            platformVersion: (CFG.androidVersion || '14') + '.0.0',
            uaFullVersion: major + '.0.0.0',
            wow64: false
          });
        });
      },
      toJSON: function(){ return { brands: brands, mobile: true, platform: 'Android' }; }
    };
    def(nav, 'userAgentData', uad);
  } catch(e){}
}

// ---------- canvas farbling (deterministic per profile) ----------
function installCanvas(w){
  try {
    var prng = function(n){ var x = Math.sin(n * 12.9898 + SEED * 78.233) * 43758.5453;
                           return x - Math.floor(x); };
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
    var proto = w.CanvasRenderingContext2D && w.CanvasRenderingContext2D.prototype;
    var elProto = w.HTMLCanvasElement && w.HTMLCanvasElement.prototype;
    if (!proto || !elProto || !proto.getImageData) return;
    var ORIG_GID = proto.getImageData;
    var ORIG_TDU = elProto.toDataURL;
    var ORIG_TBB = elProto.toBlob;
    // Perturb only the returned copy — never mutate the source canvas.
    defFn(proto, 'getImageData', function(){
      var d = ORIG_GID.apply(this, arguments);
      try { perturb(d.data); } catch(e){}
      return d;
    });
    function copyAndPerturb(src){
      var cw = src.width, ch = src.height;
      if (!(cw > 0 && ch > 0)) return null;
      var tmp = w.document.createElement('canvas'); tmp.width = cw; tmp.height = ch;
      var tc = tmp.getContext('2d');
      tc.drawImage(src, 0, 0);
      var im = ORIG_GID.call(tc, 0, 0, cw, ch);
      perturb(im.data);
      tc.putImageData(im, 0, 0);
      return tmp;
    }
    // Build the data URL from an offscreen copy, so repeated calls are stable.
    defFn(elProto, 'toDataURL', function(){
      try {
        var tmp = copyAndPerturb(this);
        if (tmp) return ORIG_TDU.apply(tmp, arguments);
      } catch(e){}
      return ORIG_TDU.apply(this, arguments);
    });
    if (ORIG_TBB) defFn(elProto, 'toBlob', function(){
      try {
        var tmp = copyAndPerturb(this);
        if (tmp) return ORIG_TBB.apply(tmp, arguments);
      } catch(e){}
      return ORIG_TBB.apply(this, arguments);
    });
  } catch(e){}
}

// ---------- WebGL vendor/renderer ----------
function installWebgl(w){
  try {
    var VENDOR = CFG.webglVendor, RENDERER = CFG.webglRenderer;
    function patchGetParameter(proto){
      if (!proto) return;
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
    patchGetParameter(w.WebGLRenderingContext && w.WebGLRenderingContext.prototype);
    patchGetParameter(w.WebGL2RenderingContext && w.WebGL2RenderingContext.prototype);
  } catch(e){}
}

// ---------- AudioContext farbling ----------
function installAudio(w){
  try {
    var AC = w.AudioContext || w.webkitAudioContext;
    if (!AC) return;
    function ahash(n){ var x = Math.sin(n * 43.017 + SEED * 17.31) * 21943.771;
                       return x - Math.floor(x); }
    var AN = w.AnalyserNode;
    if (AN && AN.prototype){
      var gfd = AN.prototype.getFloatFrequencyData;
      if (gfd) defFn(AN.prototype, 'getFloatFrequencyData', function(arr){
        gfd.apply(this, arguments);
        for (var i = 0; i < arr.length; i += 991){
          if (ahash(i) < 0.02) arr[i] = arr[i] + (ahash(i+2) - 0.5) * 1e-3;
        }
      });
    }
  } catch(e){}
}

// ---------- matchMedia resolution (F-08) ----------
function installMatchMedia(w){
  try {
    var realMM = w.matchMedia;
    if (!realMM) return;
    var RE = /\(\s*(min-|max-)?(?:device-)?resolution\s*:\s*([0-9.]+)\s*dppx\s*\)/i;
    var RE_DPR = /\(\s*(min-|max-)?(?:-webkit-)?device-pixel-ratio\s*:\s*([0-9.]+)\s*\)/i;
    function fakeMql(q, matches){
      return { matches: matches, media: q, onchange: null,
        addListener: function(){}, removeListener: function(){},
        addEventListener: function(){}, removeEventListener: function(){},
        dispatchEvent: function(){ return false; } };
    }
    defFn(w, 'matchMedia', function(q){
      try {
        if (typeof q === 'string') {
          var m = RE.exec(q) || RE_DPR.exec(q);
          if (m) {
            var bound = parseFloat(m[2]);
            var ok;
            if (m[1] === 'min-') ok = DPR >= bound;
            else if (m[1] === 'max-') ok = DPR <= bound;
            else ok = Math.abs(DPR - bound) < 0.001;
            return fakeMql(q, ok);
          }
        }
      } catch(e){}
      return realMM.call(w, q);
    });
  } catch(e){}
}

// ---------- window.chrome stub (F-09) ----------
function installChrome(w){
  try {
    if (w.chrome) return;
    var t0 = Date.now();
    // [T-fp-v8-chrome-stub] Real Chrome on Android exposes exactly
    // loadTimes + csi (control capture, Chrome 113 / Android 14). The stub
    // used to add `app` and `runtime`, which no Android Chrome has.
    def(w, 'chrome', {
      csi: function(){ return { onloadT: t0, startE: t0, pageT: 1000 + (SEED % 500), tran: 15 }; },
      loadTimes: function(){
        return { requestTime: t0 / 1000, startLoadTime: t0 / 1000, commitLoadTime: t0 / 1000,
          finishDocumentLoadTime: t0 / 1000, finishLoadTime: t0 / 1000, firstPaintTime: t0 / 1000,
          firstPaintAfterLoadTime: 0, navigationType: 'Other',
          wasFetchedViaSpdy: true, wasNpnNegotiated: true, npnNegotiatedProtocol: 'h2',
          wasAlternateProtocolAvailable: false, connectionInfo: 'h2' };
      }
    });
  } catch(e){}
}

// ---------- one realm ----------
function install(w){
  if (!w) return;
  try { if (!w.document || !w.navigator || !w.screen) return; } catch(e){ return; }
  if (marked(w) || already(w)) return;
  mark(w);
  try {
    var nav = w.navigator, scr = w.screen;
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
    def(scr, 'width', CFG.screenWidth);
    def(scr, 'height', CFG.screenHeight);
    def(scr, 'availWidth', CFG.availWidth);
    def(scr, 'availHeight', CFG.availHeight);
    def(scr, 'colorDepth', CFG.colorDepth);
    def(scr, 'pixelDepth', CFG.pixelDepth);
    def(w, 'devicePixelRatio', DPR);
    def(w, 'outerWidth', CFG.screenWidth);
    def(w, 'outerHeight', CFG.screenHeight);
  } catch(e){}
  installUaCh(w);
  installTimezone(w);
  installCanvas(w);
  installWebgl(w);
  installAudio(w);
  installMatchMedia(w);
  installChrome(w);
}

// ---------- child frames (F-01/F-11) ----------
// about:blank frames never receive the WebView document-start script, so patch
// them the moment they enter the DOM: their initial document already exists at
// that point and no child script can have run yet.
function patchFrame(node){
  try {
    if (!node || node.tagName !== 'IFRAME') return;
    var cw = node.contentWindow;
    if (cw) install(cw);
  } catch(e){}
}
function hookFrames(w){
  try {
    var N = w.Node && w.Node.prototype;
    if (!N) return;
    var origAppend = N.appendChild;
    if (origAppend && !origAppend.__fpHooked) {
      var fa = function(node){
        var r = origAppend.apply(this, arguments);
        patchFrame(node);
        return r;
      };
      fa.__fpHooked = true;
      defFn(N, 'appendChild', fa);
    }
    var origInsert = N.insertBefore;
    if (origInsert && !origInsert.__fpHooked) {
      var fi = function(node){
        var r = origInsert.apply(this, arguments);
        patchFrame(node);
        return r;
      };
      fi.__fpHooked = true;
      defFn(N, 'insertBefore', fi);
    }
    var origReplace = N.replaceChild;
    if (origReplace && !origReplace.__fpHooked) {
      var fr = function(node){
        var r = origReplace.apply(this, arguments);
        patchFrame(node);
        return r;
      };
      fr.__fpHooked = true;
      defFn(N, 'replaceChild', fr);
    }
    // innerHTML-created frames are parsed before the setter returns; patch the
    // subtree afterwards (their about:blank documents are already there).
    var E = w.Element && w.Element.prototype;
    if (E) {
      var desc = Object.getOwnPropertyDescriptor(E, 'innerHTML');
      if (desc && desc.set && !desc.set.__fpHooked) {
        var setter = function(v){
          var r = desc.set.call(this, v);
          try {
            var ifr = this.querySelectorAll ? this.querySelectorAll('iframe') : [];
            for (var i = 0; i < ifr.length; i++) patchFrame(ifr[i]);
          } catch(e){}
          return r;
        };
        setter.__fpHooked = true;
        try { Object.defineProperty(E, 'innerHTML', { set: setter, get: desc.get, configurable: true }); } catch(e){}
      }
    }
  } catch(e){}
}
"""
}
