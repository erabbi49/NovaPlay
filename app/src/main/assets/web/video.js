/* NovaPlay: reads a video page. window.__novaVideo(cfg) -> metadata + every stream URL it can see.
 * Needs cards.js (for the related list). Also defines window.__novaPlay() and window.__novaAgree(). */
(function () {
  'use strict';

  function txt(el) { return el ? String(el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
  function meta(name) {
    var el = document.querySelector('meta[property="' + name + '"],meta[name="' + name + '"]');
    return el ? (el.getAttribute('content') || '') : '';
  }
  function compact(n) {
    n = Number(n);
    if (!isFinite(n) || n <= 0) { return ''; }
    if (n >= 1e9) { return (n / 1e9).toFixed(1).replace(/\.0$/, '') + 'B'; }
    if (n >= 1e6) { return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M'; }
    if (n >= 1e3) { return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K'; }
    return String(n);
  }
  function isoDuration(s) {
    var m = /^P(?:T)?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/i.exec(s || '');
    if (!m) { return 0; }
    return (parseInt(m[1] || '0', 10) * 3600) + (parseInt(m[2] || '0', 10) * 60) + Math.round(parseFloat(m[3] || '0'));
  }

  // ----- stream URL deciphering (the site ships stream URLs as hex ciphertext) -----
  // Byte 0 = algorithm id, bytes 1..4 = little-endian int32 seed, the rest is XOR-ed with a
  // keystream (low byte of each generator output). Every operation below is on int32 values.
  function makeKeystream(algo, seed) {
    var s = seed | 0;
    switch (algo) {
      case 1: return function () { s = (Math.imul(s, 1664525) + 1013904223) | 0; return s; };
      case 2: return function () {
        var t = s;
        t = t ^ (t << 13); t = t ^ (t >>> 17); t = t ^ (t << 5);
        s = t | 0; return s;
      };
      case 3: return function () {
        s = (s + 0x9e3779b9) | 0;
        var t = s;
        t = t ^ (t >>> 16); t = Math.imul(t, 0x85ebca77);
        t = t ^ (t >>> 13); t = Math.imul(t, 0xc2b2ae3d);
        return (t ^ (t >>> 16)) | 0;
      };
      case 4: return function () {
        s = (s + 0x6d2b79f5) | 0;
        var t = s;
        t = ((t << 7) | (t >>> 25)) | 0;
        t = (t + 0x9e3779b9) | 0;
        t = t ^ (t >>> 11);
        return Math.imul(t, 0x27d4eb2d);
      };
      case 5: return function () {
        var t = s;
        t = t ^ (t << 7); t = t ^ (t >>> 9); t = t ^ (t << 8);
        s = (t + 0xa5a5a5a5) | 0; return s;
      };
      case 6: return function () {
        s = (Math.imul(s, 0x2c9277b5) + (0xac564b05 | 0)) | 0;
        var t = s ^ (s >>> 18);
        var shift = (s >>> 27) & 31;
        return (t >>> shift) | 0;
      };
      case 7: return function () {
        s = (s + (0x9e3779b9 | 0)) | 0;
        var e = s ^ (s << 5);
        e = Math.imul(e, 0x7feb352d);
        e = e ^ (e >>> 15);
        return Math.imul(e, 0x846ca68b);
      };
      default: return null;
    }
  }

  function decipherHex(hex) {
    if (hex.length % 2 !== 0 || hex.length < 12) { return null; }
    var n = hex.length / 2;
    var b = new Array(n);
    for (var i = 0; i < n; i++) { b[i] = parseInt(hex.substr(i * 2, 2), 16); }
    var gen = makeKeystream(b[0], b[1] | (b[2] << 8) | (b[3] << 16) | (b[4] << 24));
    if (!gen) { return null; }
    var out = '';
    for (var j = 5; j < n; j++) { out += String.fromCharCode(b[j] ^ (gen() & 0xFF)); }
    return /^[\x20-\x7e]+$/.test(out) ? out : null;
  }

  var HEX_RE = /^[0-9a-fA-F]{12,}$/;

  /** Returns a plain URL, or '' when the value cannot be turned into one. */
  function decipherUrl(raw) {
    if (!raw || typeof raw !== 'string') { return ''; }
    raw = raw.replace(/\\\//g, '/').trim();
    if (HEX_RE.test(raw)) {
      var d = decipherHex(raw);
      if (!d) { return ''; }
      raw = d;
    }
    if (raw.indexOf('//') === 0) { raw = 'https:' + raw; }
    if (!/^https?:\/\//i.test(raw)) { return ''; }
    var m;
    try {
      var u = new URL(raw);
      m = /^\/([0-9a-fA-F]{12,})([\/,].+)$/.exec(u.pathname);
      if (m) {
        var dec = decipherHex(m[1]);
        if (dec) { return u.origin + '/' + dec + m[2] + u.search + u.hash; }
      }
    } catch (e) {}
    return raw;
  }
  window.__novaDecipher = decipherUrl;

  window.__novaVideo = function (cfg) {
    var out = {
      title: '', thumb: '', dur: 0, up: '', upUrl: '', views: '', likes: '', desc: '',
      tags: [], streams: [], related: [], next: '', error: '', hasPlayer: false
    };
    var init = window.initials || {};
    var vm = init.videoModel || null;

    // ----- metadata -----
    try {
      if (vm) {
        out.title = vm.title || '';
        out.thumb = vm.thumbURL || vm.thumbUrl || '';
        out.dur = parseInt(vm.duration, 10) || 0;
        out.desc = vm.description || '';
        if (vm.author) { out.up = vm.author.name || ''; out.upUrl = vm.author.pageURL || vm.author.pageUrl || ''; }
        out.views = compact(vm.views);
        if (vm.rating) { out.likes = compact(vm.rating.likes); }
        if (Array.isArray(vm.categories)) {
          for (var c = 0; c < vm.categories.length; c++) {
            var cat = vm.categories[c];
            if (cat && cat.name) { out.tags.push({ name: String(cat.name), url: cat.url ? abs(cat.url) : '' }); }
          }
        }
      }
    } catch (e) {}
    if (!out.title) { out.title = txt(document.querySelector('h1')) || meta('og:title') || document.title || ''; }
    if (!out.thumb) { out.thumb = meta('og:image'); }
    if (!out.desc) { out.desc = meta('og:description'); }
    out.desc = String(out.desc || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 600);
    if (!out.up) {
      var ua = document.querySelector('a[href*="/users/"],a[href*="/creators/"],a[href*="/channels/"]');
      if (ua) { out.up = txt(ua); out.upUrl = ua.href; }
    }
    if (!out.tags.length) {
      var ta = document.querySelectorAll('a[href*="/categories/"],a[href*="/tags/"]');
      var seenTag = Object.create(null);
      for (var t = 0; t < ta.length && out.tags.length < 14; t++) {
        var name = txt(ta[t]);
        if (name && name.length < 40 && !seenTag[name]) { seenTag[name] = 1; out.tags.push({ name: name, url: ta[t].href }); }
      }
    }

    // ----- stream candidates -----
    var seen = Object.create(null);
    function addStream(u, label) {
      if (!u || typeof u !== 'string') { return; }
      u = u.replace(/\\\//g, '/').replace(/\\u0026/g, '&').trim();
      if (u.indexOf('//') === 0) { u = location.protocol + u; }
      if (!/^https?:\/\//i.test(u)) { return; }
      if (seen[u]) { return; }
      var lower = u.split('#')[0].toLowerCase();
      var pathOnly = lower.replace(/^https?:\/\/[^\/?]*/, '').split('?')[0];
      var kind = pathOnly.indexOf('.m3u8') >= 0 ? 'hls' : (/\.mp4$/.test(pathOnly) ? 'mp4' : '');
      if (!kind) { return; }
      if (/preview|teaser|thumb|sprite|poster|trailer|storyboard/.test(pathOnly)) { return; }
      seen[u] = 1;
      var codec = (lower.indexOf('.av1.') >= 0 || lower.indexOf('/av1/') >= 0) ? 'av1' : (lower.indexOf('.h264.') >= 0 ? 'h264' : '');
      out.streams.push({ url: u, kind: kind, label: label ? String(label) : '', codec: codec });
    }
    function addCiphered(u, label) { var d = decipherUrl(u); if (d) { addStream(d, label); } }
    function addSourceObj(o, label) {
      if (!o || typeof o !== 'object') { return; }
      addCiphered(o.url, label);
      addCiphered(o.fallback, label);
    }

    try {
      var xs = init.xplayerSettings && init.xplayerSettings.sources;
      if (xs) {
        var hls = xs.hls;
        if (typeof hls === 'string') { addCiphered(hls, ''); }
        else if (hls && typeof hls === 'object') {
          if (hls.url || hls.fallback) { addSourceObj(hls, ''); }
          else { for (var hk in hls) { if (typeof hls[hk] === 'string') { addCiphered(hls[hk], hk); } else { addSourceObj(hls[hk], ''); } } }
        }
        if (xs.standard && typeof xs.standard === 'object') {
          for (var k in xs.standard) {
            var arr = xs.standard[k];
            if (Array.isArray(arr)) {
              for (var a = 0; a < arr.length; a++) {
                var it = arr[a] || {};
                addSourceObj(it, it.quality || it.label || '');
              }
            }
          }
        }
      }
      var ms = vm && vm.sources;
      if (ms) {
        for (var fk in ms) {
          if (fk === 'download') { continue; }
          var d = ms[fk];
          if (d && typeof d === 'object') {
            for (var q in d) { var v = d[q]; addStream(typeof v === 'string' ? v : (v && v.url), q); }
          }
        }
      }
    } catch (e) {}

    var vids = document.querySelectorAll('video');
    out.hasPlayer = vids.length > 0;
    for (var i = 0; i < vids.length; i++) {
      addStream(vids[i].currentSrc || vids[i].src);
      var srcs = vids[i].querySelectorAll('source');
      for (var s = 0; s < srcs.length; s++) { addStream(srcs[s].src, srcs[s].getAttribute('label') || srcs[s].getAttribute('res') || srcs[s].title); }
    }
    var pre = document.querySelectorAll('link[rel="preload"][href],link[as="fetch"][href]');
    for (var p = 0; p < pre.length; p++) { addStream(pre[p].href); }
    addStream(meta('og:video'));
    addStream(meta('og:video:url'));
    addStream(meta('og:video:secure_url'));
    var lds = document.querySelectorAll('script[type="application/ld+json"]');
    for (var l = 0; l < lds.length; l++) {
      try {
        var data = JSON.parse(lds[l].textContent);
        var list = Array.isArray(data) ? data : [data];
        for (var x = 0; x < list.length; x++) {
          if (list[x] && list[x].contentUrl) { addStream(list[x].contentUrl); }
          if (!out.dur && list[x] && list[x].duration) { out.dur = isoDuration(list[x].duration); }
        }
      } catch (e) {}
    }
    if (!out.streams.length) {
      var scripts = document.querySelectorAll('script:not([src])');
      var re = /https?:\\?\/\\?\/(?:[^"'\s<>\\]|\\\/|\\u0026)*?\.(?:m3u8|mp4)(?:\?(?:[^"'\s<>\\]|\\\/|\\u0026)*)?/g;
      var budget = 3000000;
      for (var sc = 0; sc < scripts.length && budget > 0; sc++) {
        var text = scripts[sc].textContent || '';
        budget -= text.length;
        var mm;
        while ((mm = re.exec(text)) !== null) { addStream(mm[0]); }
      }
    }

    // ----- problems -----
    try {
      var closed = document.getElementById('videoClosed');
      if (closed) { out.error = txt(closed).slice(0, 200); }
      else if (/(^|\b)(404|not found)(\b|$)/i.test(document.title) && !out.streams.length && !vm) { out.error = 'Video not found'; }
    } catch (e) {}

    // ----- related videos -----
    try {
      if (window.__novaCards) {
        var page = window.__novaCards(cfg);
        var here = location.origin + location.pathname;
        for (var r = 0; r < page.items.length; r++) { if (page.items[r].url !== here) { out.related.push(page.items[r]); } }
        out.next = page.next;
      }
    } catch (e) {}
    return out;
  };

  /* Starts playback so the site's own player requests the real stream. */
  window.__novaPlay = function () {
    var v = document.querySelector('video');
    if (v) {
      try { v.muted = true; var pr = v.play(); if (pr && pr.catch) { pr.catch(function () {}); } } catch (e) {}
      return 'video';
    }
    var sels = ['.xplayer .xp-preload-image', '[class*="play-button"]', '[class*="PlayButton"]', '[class*="player"] [class*="play"]',
                'button[aria-label*="lay"]', '.xplayer', '[class*="player"]'];
    for (var i = 0; i < sels.length; i++) {
      var el = document.querySelector(sels[i]);
      if (el) { try { el.click(); } catch (e) {} return 'clicked ' + sels[i]; }
    }
    return 'nothing';
  };

  /* Clicks through age / cookie consent overlays. */
  window.__novaAgree = function () {
    var boxes = document.querySelectorAll('[class*="modal"],[class*="popup"],[class*="age"],[class*="consent"],[class*="cookie"],[role="dialog"]');
    var re = /^(i am|i'm)?\s*(18|over 18|of legal age|an adult)|^enter\b|^agree|^accept|^continue|^yes\b|^i agree/i;
    var clicked = 0;
    for (var i = 0; i < boxes.length && clicked < 3; i++) {
      var btns = boxes[i].querySelectorAll('button,a[role="button"],[class*="button"],[class*="btn"]');
      for (var j = 0; j < btns.length; j++) {
        var t = txt(btns[j]);
        if (t && t.length < 60 && re.test(t)) { try { btns[j].click(); clicked++; } catch (e) {} break; }
      }
    }
    return clicked;
  };
})();
