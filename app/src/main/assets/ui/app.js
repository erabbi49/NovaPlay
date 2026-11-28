/* NovaPlay 2 - YouTube-style front end for eporner (runs inside the Android WebView shell) */
(function () {
  'use strict';

  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var N = window.NovaNative || null;
  var SITE = 'https://www.eporner.com', API = SITE + '/api/v2', PER = 36;
  var ORD = [['latest', 'Latest'], ['top-weekly', 'Trending'], ['top-rated', 'Top rated'], ['most-popular', 'Most viewed'], ['top-monthly', 'This month'], ['longest', 'Longest'], ['shortest', 'Shortest']];

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }

  /* ---------- icons (Material, 24px) ---------- */
  var IC = {
    home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
    explore: 'M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1c.61 0 1.1-.49 1.1-1.1s-.49-1.1-1.1-1.1zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19z',
    subs: 'M20 8H4V6h16v2zm-2-6H6v2h12V2zm4 10v8c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2v-8c0-1.1.9-2 2-2h16c1.1 0 2 .9 2 2zm-6 4l-6-3.27v6.53L16 16z',
    download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
    you: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM7.07 18.28c.43-.9 3.05-1.78 4.93-1.78s4.51.88 4.93 1.78C15.57 19.36 13.86 20 12 20s-3.57-.64-4.93-1.72zm11.29-1.45c-1.43-1.74-4.9-2.33-6.36-2.33s-4.93.59-6.36 2.33A7.95 7.95 0 0 1 4 12c0-4.41 3.59-8 8-8s8 3.59 8 8c0 1.82-.62 3.49-1.64 4.83zM12 6c-1.94 0-3.5 1.56-3.5 3.5S10.06 13 12 13s3.5-1.56 3.5-3.5S13.94 6 12 6zm0 5c-.83 0-1.5-.67-1.5-1.5S11.17 8 12 8s1.5.67 1.5 1.5S12.83 11 12 11z',
    search: 'M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
    back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
    close: 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    more: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    play: 'M8 5v14l11-7z',
    pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
    next: 'M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z',
    prev: 'M6 6h2v12H6zm3.5 6l8.5 6V6z',
    fs: 'M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z',
    fsx: 'M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z',
    gear: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
    like: 'M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z',
    share: 'M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z',
    later: 'M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z',
    history: 'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
    queue: 'M3 10h11v2H3zm0-4h11v2H3zm0 8h7v2H3zm13-1v8l6-4z',
    pip: 'M19 11h-8v6h8v-6zm4 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-2 .02H3V4.97h18v14.05z',
    loop: 'M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z',
    down: 'M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6z',
    right: 'M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z',
    left: 'M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z',
    check: 'M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
    trash: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
    add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
    tag: 'M20 10V8h-4V4h-2v4h-4V4H8v4H4v2h4v4H4v2h4v4h2v-4h4v4h2v-4h4v-2h-4v-4h4zm-6 4h-4v-4h4v4z',
    ambient: 'M20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm0-10c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z',
    headset: 'M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h3c1.66 0 3-1.34 3-3v-7c0-4.97-4.03-9-9-9z',
    speed: 'M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z',
    quality: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
    refresh: 'M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    trend: 'M16 6l2.29 2.29-4.88 4.88-4-4L2 16.59 3.41 18l6-6 4 4 6.3-6.29L22 12V6z',
    shield: 'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-1.06 14.54L7.4 12l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41-5.64 5.66z'
  };
  function ic(n, c) { return '<svg class="ic' + (c ? ' ' + c : '') + '" viewBox="0 0 24 24" aria-hidden="true"><path d="' + (IC[n] || '') + '"/></svg>'; }

  /* ---------- storage ---------- */
  var KEY = 'novaplay2';
  var DEF = { set: { theme: 'dark', ambient: 2, quality: '720', autoplay: true, bg: true, gay: 0, lq: 0, hist: true, resume: true, pip: true, adult: false }, hist: [], liked: [], later: [], follows: [], searches: [], dl: {}, pos: {} };
  var D;
  try { D = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { D = {}; }
  Object.keys(DEF).forEach(function (k) { if (D[k] == null) D[k] = JSON.parse(JSON.stringify(DEF[k])); });
  Object.keys(DEF.set).forEach(function (k) { if (D.set[k] == null) D.set[k] = DEF.set[k]; });
  var saveT = 0;
  function flush() { try { localStorage.setItem(KEY, JSON.stringify(D)); } catch (e) {} }
  function save() { clearTimeout(saveT); saveT = setTimeout(flush, 200); }
  window.addEventListener('pagehide', flush);
  document.addEventListener('visibilitychange', function () { if (document.hidden) flush(); });

  /* ---------- formatting ---------- */
  function fmtN(n) {
    n = +n || 0;
    if (n >= 1e9) return (n / 1e9).toFixed(1).replace(/\.0$/, '') + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1e5) return Math.round(n / 1e3) + 'K';
    if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K';
    return String(n);
  }
  function fmtT(s) { s = Math.max(0, Math.floor(+s || 0)); var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (x < 10 ? '0' : '') + x; }
  function fmtSize(b) { b = +b || 0; return b >= 1e9 ? (b / 1e9).toFixed(2) + ' GB' : (b / 1e6).toFixed(b >= 1e8 ? 0 : 1) + ' MB'; }
  function ago(s) {
    if (!s) return '';
    var t = Date.parse(String(s).replace(' ', 'T') + 'Z'); if (isNaN(t)) return '';
    var d = (Date.now() - t) / 1000, u = [[31536000, 'year'], [2592000, 'month'], [604800, 'week'], [86400, 'day'], [3600, 'hour'], [60, 'minute']];
    for (var i = 0; i < u.length; i++) if (d >= u[i][0]) { var k = Math.floor(d / u[i][0]); return k + ' ' + u[i][1] + (k > 1 ? 's' : '') + ' ago'; }
    return 'Just now';
  }
  function hue(s) { var h = 0; s = String(s); for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360; return h; }
  function safe(t) { return String(t || 'video').replace(/[\\/:*?"<>|\r\n]+/g, ' ').replace(/\s+/g, ' ').trim(); }

  /* ---------- data layer (eporner API + player data) ---------- */
  var V = new Map();
  function norm(v) {
    var th = v.default_thumb || (v.thumbs && v.thumbs[0]) || {};
    var o = {
      id: String(v.id), title: v.title || 'Untitled',
      tags: String(v.keywords || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean),
      views: +v.views || 0, rate: parseFloat(v.rate) || 0, url: String(v.url || '').replace(/^http:/, 'https:'),
      added: v.added || '', sec: +v.length_sec || 0, thumb: String(th.src || '').replace(/^http:/, 'https:'), embed: v.embed || ''
    };
    V.set(o.id, o); return o;
  }
  function mini(v) { return { id: v.id, title: v.title, tags: v.tags.slice(0, 10), views: v.views, rate: v.rate, url: v.url, added: v.added, sec: v.sec, thumb: v.thumb, embed: v.embed }; }
  function reg(v) { if (v && v.id != null) { v.id = String(v.id); v.tags = v.tags || []; if (!V.has(v.id)) V.set(v.id, v); } return v; }

  function http(url, timeout) {
    return new Promise(function (res, rej) {
      var x = new XMLHttpRequest();
      x.open('GET', url, true); x.timeout = timeout || 25000;
      x.onload = function () { if (x.status >= 200 && x.status < 300) res(x.responseText); else rej(new Error('Server returned ' + x.status)); };
      x.onerror = function () { rej(new Error('No connection. Check your internet and try again.')); };
      x.ontimeout = function () { rej(new Error('The request timed out.')); };
      x.send();
    });
  }
  function jget(u) { return http(u).then(function (t) { return JSON.parse(t); }); }
  var cache = new Map();
  function cached(key, ttl, fn) {
    var c = cache.get(key); if (c && Date.now() - c.t < ttl) return c.p;
    var p = fn(); cache.set(key, { t: Date.now(), p: p }); p.catch(function () { cache.delete(key); }); return p;
  }
  function list(o) {
    var q = o.q || 'all', order = o.order || 'latest', page = o.page || 1, per = o.per || PER;
    var u = API + '/video/search/?query=' + encodeURIComponent(q) + '&per_page=' + per + '&page=' + page + '&thumbsize=big&order=' + order + '&gay=' + D.set.gay + '&lq=' + D.set.lq + '&format=json';
    return cached(u, 300000, function () {
      return jget(u).then(function (j) { return { videos: (j.videos || []).map(norm), page: +j.page || page, pages: +j.total_pages || 1, total: +j.total_count || 0 }; });
    });
  }
  function getSources(v) {
    return cached('src:' + v.id, 600000, function () {
      return http(v.url).then(function (html) {
        var m = html.match(/hash["']?\s*[:=]\s*["']([\da-f]{32})/i);
        if (!m) throw new Error('Player data not found for this video.');
        var h = m[1], hh = [0, 8, 16, 24].map(function (i) { return parseInt(h.slice(i, i + 8), 16).toString(36); }).join('');
        return jget(SITE + '/xhr/video/' + v.id + '?hash=' + hh + '&device=generic&domain=www.eporner.com&fallback=false&embed=false&supportedFormats=dash,mp4&_=' + Date.now());
      }).then(function (j) {
        if (j.available === false) throw new Error(j.message || 'This video is not available.');
        var out = [], src0 = j.sources || {};
        Object.keys(src0).forEach(function (kind) {
          if (kind !== 'mp4' && kind !== 'hls') return;
          var g = src0[kind]; if (!g || typeof g !== 'object') return;
          Object.keys(g).forEach(function (k) {
            var e = g[k], src = e && e.src; if (!src || typeof src !== 'string') return;
            if (src.indexOf('//') === 0) src = 'https:' + src; if (!/^https?:/.test(src)) return;
            var hm = (k + ' ' + (e.labelShort || '')).match(/(\d{3,4})\s*p/i);
            out.push({ kind: kind, label: String(e.labelShort || k).replace(/\s*\(.*\)\s*/, ''), h: hm ? +hm[1] : 0, src: src });
          });
        });
        if (!out.length) throw new Error('No playable stream was found.');
        return out;
      });
    });
  }
  function related(v) {
    var qs = v.tags.slice(0, 3); if (!qs.length) qs = String(v.title).split(/\s+/).slice(0, 2);
    var seen = {}, out = []; seen[v.id] = 1;
    return qs.reduce(function (p, q) {
      return p.then(function () {
        if (out.length >= 24) return;
        return list({ q: q, order: 'most-popular', per: 24 }).then(function (r) { r.videos.forEach(function (x) { if (!seen[x.id]) { seen[x.id] = 1; out.push(x); } }); }, function () {});
      });
    }, Promise.resolve()).then(function () { return out; });
  }
  function trending() {
    return cached('trend', 900000, function () {
      return list({ q: 'all', order: 'top-weekly', per: 100 }).then(function (r) {
        var m = {};
        r.videos.forEach(function (v) { v.tags.slice(0, 6).forEach(function (t) { var k = t.toLowerCase(); if (k.length < 3 || k.length > 24) return; var e = m[k] || (m[k] = { tag: t, n: 0, thumb: v.thumb }); e.n++; }); });
        return Object.keys(m).map(function (k) { return m[k]; }).sort(function (a, b) { return b.n - a.n; }).slice(0, 40);
      });
    });
  }

  /* ---------- state ---------- */
  var S = { tk: 0, ptk: 0, rk: null, route: { name: 'home', q: {}, key: '' }, stack: [], scroll: {}, depth: {}, more: null, err: false, errE: null, perr: null, warp: '', restore: false, queue: [], cur: null, curSrc: null, src: [], rel: [], trend: null, chipsFn: null, dlTimer: 0, dlSig: '', rate: 1 };
  var view = $('#view');
  var W = $('#watch'), P = $('#player'), vid = $('#vid');
  var lastSave = 0, uiT = 0, resumeAt = 0, triedLower = false, upnT = 0, toastT = 0, sheetRows = null, lastLoad = 0;
  function logv(m) { try { if (N && N.logMedia) N.logMedia(m); } catch (e) {} } /* a line in Settings > Video connection log */

  /* ---------- small UI helpers ---------- */
  function toast(m) { var t = $('#toast'); t.innerHTML = '<span>' + esc(m) + '</span>'; t.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(function () { t.classList.remove('on'); }, 2300); }
  function sheet(title, rows, html) {
    var w = $('#sheetwrap'); sheetRows = rows;
    w.innerHTML = '<div class="scrim" data-sc="1"></div><div class="sheet"><div class="grab"></div>' + (title ? '<div class="st">' + esc(title) + '</div>' : '') + (html || '') +
      rows.map(function (r, i) {
        return '<button class="sr' + (r.on ? ' on' : '') + '" data-si="' + i + '">' + (r.icon ? ic(r.icon) : '<span class="sp0"></span>') + '<span class="sl1">' + esc(r.label) + (r.sub ? '<small>' + esc(r.sub) + '</small>' : '') + '</span>' + (r.check ? ic('check') : '') + (r.toggle != null ? '<i class="sw' + (r.toggle ? ' on' : '') + '"></i>' : '') + '</button>';
      }).join('') + '</div>';
    w.className = 'open';
    requestAnimationFrame(function () { requestAnimationFrame(function () { w.classList.add('in'); }); });
  }
  function closeSheet() {
    var w = $('#sheetwrap'); if (!w.classList.contains('open')) return false;
    w.classList.remove('in'); sheetRows = null;
    setTimeout(function () { if (!w.classList.contains('in')) { w.className = ''; w.innerHTML = ''; } }, 240);
    return true;
  }
  function pick(title, opts, cur, fn) { sheet(title, opts.map(function (o) { return { label: o[1], check: o[0] === cur, on: o[0] === cur, fn: function () { fn(o[0]); } }; })); }
  function empty(icon, title, text, btn, goto) { return '<div class="empty">' + ic(icon) + '<h3>' + esc(title) + '</h3><p>' + esc(text || '') + '</p>' + (btn ? '<button class="btn" data-go="' + esc(goto) + '" data-root="1">' + esc(btn) + '</button>' : '') + '</div>'; }
  function errView(e) {
    S.err = true; S.errE = e;
    var w = warpInfo(), wait = w && (w.state === 'connecting' || w.state === 'registering'); /* (waiting for the VPN question is not "connecting": the person has to tap) */
    view.innerHTML = '<div class="empty">' + ic('refresh') + '<h3>Can’t load videos</h3><p>' + esc((e && e.message) || 'Check your connection and try again.') + '</p><button class="btn" data-act="retry">Try again</button>' +
      (wait ? '<p>WARP is connecting. The videos load when it is on.</p>' : warpOffer() ? '<p>Not loading on your Wi‑Fi? Your internet provider may be blocking the site.</p><button class="btn ghost" data-act="warpon">' + ic('shield') + 'Turn on WARP (1.1.1.1)</button>' : '') + '</div>';
  }
  function skel(n) { var a = ''; for (var i = 0; i < (n || 4); i++) a += '<div class="card sk"><div class="th"></div><div class="cm"><i class="av"></i><div class="tx"><b></b><b class="s"></b></div></div></div>'; return '<div class="feed">' + a + '</div>'; }
  function done() { if (S.restore) { S.restore = false; window.scrollTo(0, S.scroll[S.rk] || 0); } }

  /* ---------- templates ---------- */
  function card(v) {
    if (!V.has(v.id)) V.set(v.id, v);
    var t0 = v.tags[0] || 'video', pos = D.pos[v.id], pr = pos && v.sec ? Math.min(100, pos / v.sec * 100) : 0;
    return '<article class="card"><div class="th" data-play="' + v.id + '"><img loading="lazy" decoding="async" alt="" src="' + esc(v.thumb) + '"><span class="dur">' + fmtT(v.sec) + '</span>' + (pr > 2 ? '<i class="prog" style="width:' + pr + '%"></i>' : '') + '</div>' +
      '<div class="cm"><a class="av" data-tag="' + esc(t0) + '" style="background:hsl(' + hue(t0) + ' 55% 40%)">' + esc(t0.charAt(0)) + '</a>' +
      '<div class="tx" data-play="' + v.id + '"><h3>' + esc(v.title) + '</h3><div class="sub">' + esc(t0) + ' · ' + fmtN(v.views) + ' views · ' + ago(v.added) + (v.rate ? ' · ' + Math.round(v.rate * 20) + '%' : '') + '</div></div>' +
      '<button class="kb" data-menu="' + v.id + '" aria-label="More">' + ic('more') + '</button></div>' +
      (v.tags.length ? '<div class="htags">' + v.tags.slice(0, 6).map(function (t) { return '<a data-tag="' + esc(t) + '">#' + esc(t) + '</a>'; }).join('') + '</div>' : '') + '</article>';
  }
  function mcard(v) {
    if (!V.has(v.id)) V.set(v.id, v);
    return '<div class="mc" data-play="' + v.id + '"><div class="th"><img loading="lazy" alt="" src="' + esc(v.thumb) + '"><span class="dur">' + fmtT(v.sec) + '</span></div><h3>' + esc(v.title) + '</h3><div class="sub">' + fmtN(v.views) + ' views · ' + ago(v.added) + '</div></div>';
  }
  function shelf(title, path, vs) {
    if (!vs || !vs.length) return '';
    vs.forEach(reg);
    return '<div class="h2">' + esc(title) + (path ? '<a data-go="' + esc(path) + '" data-root="1">See all</a>' : '') + '</div><div class="shelf">' + vs.map(mcard).join('') + '</div>';
  }
  function tile(x) { return '<a class="tile" data-tag="' + esc(x.tag) + '" style="background-image:url(\'' + String(x.thumb || '').replace(/['"()\s]/g, '') + '\')"><span>#' + esc(x.tag) + '</span></a>'; }
  function pager(page, pages) {
    if (pages <= 1) return '';
    var set = {}; [1, pages, page - 2, page - 1, page, page + 1, page + 2].forEach(function (p) { if (p >= 1 && p <= pages) set[p] = 1; });
    var a = Object.keys(set).map(Number).sort(function (x, y) { return x - y; }), h = '<div class="pager">', last = 0;
    if (page > 1) h += '<a data-p="' + (page - 1) + '" aria-label="Previous page">' + ic('left') + '</a>';
    a.forEach(function (p) { if (p - last > 1) h += '<a class="gap" data-pg="' + pages + '">…</a>'; h += '<a data-p="' + p + '"' + (p === page ? ' class="on"' : '') + '>' + p + '</a>'; last = p; });
    if (page < pages) h += '<a data-p="' + (page + 1) + '" aria-label="Next page">' + ic('right') + '</a>';
    return h + '</div>';
  }
  function isFol(t) { t = String(t).toLowerCase(); return D.follows.some(function (x) { return x.toLowerCase() === t; }); }
  function tagHead(t, total) {
    var on = isFol(t);
    return '<div class="wc" style="padding-top:12px"><a class="av" style="width:56px;height:56px;font-size:22px;background:hsl(' + hue(t) + ' 55% 40%)">' + esc(t.charAt(0)) + '</a><div class="nm"><b>#' + esc(t) + '</b><small>' + (total ? fmtN(total) + ' videos' : 'Tag') + '</small></div><button class="fol' + (on ? ' on' : '') + '" data-fol="' + esc(t) + '">' + (on ? 'Following' : 'Follow') + '</button></div>';
  }

  /* ---------- chips ---------- */
  function setChips(fn) { S.chipsFn = fn; drawChips(); }
  function drawChips() {
    var el = $('#chips');
    if (!S.chipsFn) { el.style.display = 'none'; el.innerHTML = ''; return; }
    var keep = el.scrollLeft;
    el.innerHTML = S.chipsFn().map(function (c) { return '<a class="chip' + (c.on ? ' on' : '') + '" data-go="' + esc(c.go) + '" data-r="1">' + esc(c.label) + '</a>'; }).join('');
    el.style.display = 'flex'; el.scrollLeft = keep;
  }

  /* ---------- router ---------- */
  function parse() {
    var h = location.hash.replace(/^#\/?/, ''), i = h.indexOf('?'), path = i < 0 ? h : h.slice(0, i);
    var q = {}; new URLSearchParams(i < 0 ? '' : h.slice(i + 1)).forEach(function (v, k) { q[k] = v; });
    var p = path.split('/').filter(Boolean).map(decodeURIComponent);
    return { name: p[0] || 'home', arg: p[1] || '', q: q, key: h };
  }
  function go(path, o) {
    o = o || {};
    if (o.root) S.stack = []; else if (!o.replace) S.stack.push(S.rk || '');
    var h = '#/' + path;
    if (location.hash === h) { render(); return; }
    if (o.replace) location.replace(h); else location.hash = h;
  }
  function back() {
    if (S.stack.length) { var k = S.stack.pop(); S.restore = true; location.hash = '#/' + k; return true; }
    if (S.route.name !== 'home') { go('home', { root: true }); return true; }
    return false;
  }
  function gotoPage(n) {
    var r = S.route, q = new URLSearchParams(r.q); q.set('p', n);
    go(r.name + (r.arg ? '/' + encodeURIComponent(r.arg) : '') + '?' + q.toString(), { replace: true });
  }
  function setNav(n) {
    var map = { home: 'home', explore: 'explore', following: 'following', downloads: 'downloads', you: 'you', history: 'you', watchlater: 'you', liked: 'you', settings: 'you' };
    var t = map[n]; if (n === 'search' || n === 'tag') return; if (!t) t = 'home';
    $$('#nav a').forEach(function (a) { a.classList.toggle('on', a.getAttribute('data-tab') === t); });
  }
  function render() {
    var r = parse();
    if (S.rk != null && S.rk !== r.key) S.scroll[S.rk] = window.pageYOffset;
    S.rk = r.key; S.route = r; S.more = null; S.err = false; var tk = ++S.tk;
    stopDl(); setNav(r.name); $('#hdr').classList.remove('hide'); S.chipsFn = null; drawChips();
    var map = { home: pageHome, explore: pageExplore, following: pageFollowing, downloads: pageDownloads, you: pageYou, search: pageSearch, tag: pageTag, history: pageHistory, watchlater: pageSaved, liked: pageSaved, settings: pageSettings };
    window.scrollTo(0, 0);
    (map[r.name] || pageHome)(r, tk);
  }

  /* ---------- pages ---------- */
  /* A list of videos. The first page is drawn at once; the next ones are added below by themselves while you scroll, and
     the page numbers stay under the list. loader(n) gives page n as {videos, page, pages}. */
  var AUTO_PAGES = 8; /* pages added by scrolling alone; after that a button asks first, so a very long list stays light */
  function listPage(tk, loader, head, first, none) {
    var h = function (res) { return head ? head(res) : ''; }, key = S.rk, back = S.restore ? Math.min(S.depth[key] || 0, 12) : 0; /* (a very deep list comes back 12 pages deep at most) */
    view.innerHTML = h(null) + skel();
    return loader(first || 1).then(function (res) {
      if (tk !== S.tk) return;
      if (!res.videos.length) { view.innerHTML = h(res) + (none || empty('search', 'No videos found', 'Try a different search or sort.')); done(); return; }
      var seen = {}; res.videos.forEach(function (v) { seen[v.id] = 1; });
      view.innerHTML = h(res) + '<div class="feed">' + res.videos.map(card).join('') + '</div><div id="more"></div><div id="pagerbox">' + pager(res.page, res.pages) + '</div>';
      var m = S.more = { tk: tk, key: key, loader: loader, page: res.page, pages: res.pages, busy: false, failed: false, auto: 0, seen: seen };
      S.depth[key] = res.page;
      moreBar(m);
      function finish() { if (tk !== S.tk) return; m.auto = 0; done(); autoMore(); }
      if (back > res.page) { /* coming back to a list that had been scrolled through: bring its pages back first, then the position */
        (function again() {
          if (tk !== S.tk) return;
          if (m.page < back && m.page < m.pages) loadMore().then(function (ok) { if (ok) again(); else finish(); });
          else finish();
        })();
      } else { done(); autoMore(); }
    }, function (e) { if (tk === S.tk) errView(e); });
  }
  function moreBar(m) {
    var el = $('#more'); if (!el || !m) return;
    if (m.busy) el.innerHTML = '<i class="ld" role="status" aria-label="Loading more videos"></i>';
    else if (m.failed) el.innerHTML = '<button class="btn ghost" data-act="more">Couldn’t load more. Tap to retry</button>';
    else if (m.page < m.pages && m.auto >= AUTO_PAGES) el.innerHTML = '<button class="btn ghost" data-act="more">Load more videos</button>';
    else el.innerHTML = '';
  }
  /* adds the next page under the list; gives true when it did */
  function loadMore() {
    var m = S.more;
    if (!m || m.busy || m.tk !== S.tk || m.page >= m.pages) return Promise.resolve(false);
    var want = m.page + 1;
    m.busy = true; m.failed = false; moreBar(m);
    return m.loader(want).then(function (res) {
      m.busy = false;
      if (S.more !== m || m.tk !== S.tk) return false;
      if (!res.videos.length || (res.page && res.page < want)) { m.pages = m.page; moreBar(m); var e1 = $('#pagerbox'); if (e1) e1.innerHTML = pager(m.page, m.pages); return false; } /* nothing more here: that was the end */
      var fresh = res.videos.filter(function (v) { if (m.seen[v.id]) return false; m.seen[v.id] = 1; return true; });
      var feed = $('#view .feed');
      if (feed && fresh.length) feed.insertAdjacentHTML('beforeend', fresh.map(card).join(''));
      m.page = want; if (res.pages) m.pages = res.pages;
      S.depth[m.key] = m.page;
      var pg = $('#pagerbox'); if (pg) pg.innerHTML = pager(m.page, m.pages);
      moreBar(m);
      return true;
    }, function () {
      m.busy = false;
      if (S.more !== m || m.tk !== S.tk) return false;
      m.failed = true; moreBar(m); return false;
    });
  }
  function nearEnd() { var el = $('#more'); return !!el && el.getBoundingClientRect().top < window.innerHeight + 900; }
  /* adds the next page when the end of the list is near; called while scrolling and after each page */
  function autoMore() {
    var m = S.more;
    if (!m || m.busy || m.failed || m.tk !== S.tk || m.page >= m.pages || m.auto >= AUTO_PAGES || !nearEnd()) return;
    m.auto++;
    loadMore().then(function (ok) { if (ok) setTimeout(autoMore, 80); });
  }
  function pageHome(r, tk) {
    var t = r.q.t || '', c = t ? '' : (r.q.c || 'latest'), page = +r.q.p || 1;
    setChips(function () {
      var a = ORD.map(function (o) { return { label: o[1], go: 'home?c=' + o[0], on: !t && c === o[0] }; });
      var tr = S.trend || [], low = t.toLowerCase();
      if (t && !tr.some(function (x) { return x.tag.toLowerCase() === low; })) a.push({ label: t, go: 'home?t=' + encodeURIComponent(t), on: true });
      tr.slice(0, 14).forEach(function (x) { a.push({ label: x.tag, go: 'home?t=' + encodeURIComponent(x.tag), on: low === x.tag.toLowerCase() }); });
      return a;
    });
    listPage(tk, function (n) { return t ? list({ q: t, order: 'most-popular', page: n }) : list({ order: c, page: n }); }, null, page);
  }
  function pageSearch(r, tk) {
    var q = r.q.q || '', o = r.q.o || 'most-popular', page = +r.q.p || 1;
    if (!q) { view.innerHTML = empty('search', 'Search videos', 'Type a word or tap a tag to find videos.'); openSearch(''); return; }
    setChips(function () { return ORD.map(function (x) { return { label: x[1], go: 'search?q=' + encodeURIComponent(q) + '&o=' + x[0], on: o === x[0] }; }); });
    listPage(tk, function (n) { return list({ q: q, order: o, page: n }); }, function (res) { return '<div class="h2">' + (res ? fmtN(res.total) + ' results for “' + esc(q) + '”' : 'Searching…') + '</div>'; }, page);
  }
  function pageTag(r, tk) {
    var t = r.arg, o = r.q.o || 'most-popular', page = +r.q.p || 1;
    setChips(function () { return ORD.map(function (x) { return { label: x[1], go: 'tag/' + encodeURIComponent(t) + '?o=' + x[0], on: o === x[0] }; }); });
    listPage(tk, function (n) { return list({ q: t, order: o, page: n }); }, function (res) { return tagHead(t, res ? res.total : 0); }, page);
  }
  function pageExplore(r, tk) {
    view.innerHTML = skel(2);
    Promise.all([trending(), list({ order: 'latest', per: 12 }), list({ order: 'top-monthly', per: 12 })]).then(function (rs) {
      if (tk !== S.tk) return; S.trend = rs[0];
      view.innerHTML = '<div class="h2">Trending tags</div><div class="tiles">' + rs[0].slice(0, 16).map(tile).join('') + '</div>' + shelf('New videos', 'home?c=latest', rs[1].videos) + shelf('Top this month', 'home?c=top-monthly', rs[2].videos);
      done();
    }, function (e) { if (tk === S.tk) errView(e); });
  }
  function pageFollowing(r, tk) {
    var f = D.follows;
    if (!f.length) { view.innerHTML = empty('subs', 'Follow tags you like', 'Open a video and tap Follow on its tag to see new videos from it here.', 'Explore tags', 'explore'); return; }
    var act = r.q.t || '', low = act.toLowerCase();
    setChips(function () { var a = [{ label: 'All', go: 'following', on: !act }]; f.forEach(function (t) { a.push({ label: t, go: 'following?t=' + encodeURIComponent(t), on: low === t.toLowerCase() }); }); return a; });
    var tags = act ? [act] : f.slice(0, 8), per = act ? PER : 10;
    /* page n of every followed tag, merged newest first */
    function loader(n) {
      return Promise.all(tags.map(function (t) { return list({ q: t, order: 'latest', per: per, page: n }).catch(function () { return { videos: [], page: n, pages: 1 }; }); })).then(function (rs) {
        var seen = {}, all = [], pages = 1;
        rs.forEach(function (x) { pages = Math.max(pages, x.pages || 1); x.videos.forEach(function (v) { if (!seen[v.id]) { seen[v.id] = 1; all.push(v); } }); });
        all.sort(function (a, b) { return a.added < b.added ? 1 : -1; });
        return { videos: all, page: n, pages: pages };
      });
    }
    listPage(tk, loader, null, +r.q.p || 1, empty('subs', 'No videos yet', 'Nothing new from these tags right now.'));
  }
  function pageYou(r, tk) {
    var h = D.hist.slice(0, 12), l = D.later.slice(0, 12), k = D.liked.slice(0, 12);
    function row(go, icon, label, root) { return '<button class="row" data-go="' + go + '"' + (root ? ' data-root="1"' : '') + '>' + ic(icon) + '<span class="rt">' + label + '</span>' + ic('right') + '</button>'; }
    view.innerHTML = '<div class="prof"><span class="av">N</span><div><h2>You</h2><small>Everything is saved on this device</small></div></div>' +
      shelf('History', 'history', h) + shelf('Watch later', 'watchlater', l) + shelf('Liked videos', 'liked', k) +
      '<div class="h2">Library</div>' + row('downloads', 'download', 'Downloads', true) + row('history', 'history', 'Watch history') + row('watchlater', 'later', 'Watch later') + row('liked', 'like', 'Liked videos') +
      '<button class="row" data-act="queue">' + ic('queue') + '<span class="rt">Queue</span>' + ic('right') + '</button>' + row('settings', 'gear', 'Settings');
    done();
  }
  function pageHistory(r, tk) {
    var a = D.hist; a.forEach(reg);
    view.innerHTML = '<div class="h2">Watch history' + (a.length ? '<a data-act="clearhist">Clear all</a>' : '') + '</div>' + (a.length ? '<div class="feed">' + a.map(card).join('') + '</div>' : empty('history', 'No watch history', D.set.hist ? 'Videos you watch show up here.' : 'Watch history is paused. Turn it back on in Settings.'));
    done();
  }
  function pageSaved(r, tk) {
    var k = r.name === 'liked' ? 'liked' : 'later', a = D[k]; a.forEach(reg);
    view.innerHTML = '<div class="h2">' + (k === 'liked' ? 'Liked videos' : 'Watch later') + '</div>' + (a.length ? '<div class="feed">' + a.map(card).join('') + '</div>' : empty(k === 'liked' ? 'like' : 'later', 'Nothing here yet', k === 'liked' ? 'Videos you like show up here.' : 'Save videos to watch them later.'));
    done();
  }
  function pageSettings(r, tk) {
    var s = D.set;
    function row(id, icon, label, val, sub) { return '<button class="row" data-set="' + id + '">' + ic(icon) + '<span class="rt">' + label + (sub ? '<small>' + sub + '</small>' : '') + '</span><span class="rv">' + (val == null ? '' : val) + '</span></button>'; }
    function tog(id, icon, label, on, sub) { return '<button class="row" data-set="' + id + '">' + ic(icon) + '<span class="rt">' + label + (sub ? '<small>' + sub + '</small>' : '') + '</span><i class="sw' + (on ? ' on' : '') + '"></i></button>'; }
    var wr = N && N.warpState ? warpRows() : '';
    view.innerHTML = '<div class="h2">Settings</div>' +
      (wr ? '<div class="grp">Connection</div><div id="warpRows">' + wr + '</div>' : '') +
      '<div class="grp">Appearance</div>' + row('theme', 'ambient', 'Theme', { dark: 'Dark', black: 'Black', light: 'Light' }[s.theme]) +
      '<div class="grp">Player</div>' + row('ambient', 'ambient', 'Ambient mode', ['Off', 'Subtle', 'Strong'][s.ambient]) + row('quality', 'quality', 'Default quality', s.quality === 'best' ? 'Best' : s.quality + 'p') +
      tog('autoplay', 'next', 'Autoplay next video', s.autoplay) + tog('bg', 'headset', 'Keep playing in background', s.bg, 'Audio continues when you leave the app') + tog('pip', 'pip', 'Pop-out player when you leave', s.pip, 'Picture-in-picture while a video is playing') + tog('resume', 'history', 'Resume where you left off', s.resume) +
      '<div class="grp">Content</div>' + row('gay', 'tag', 'Show', ['Straight only', 'Everything', 'Gay only'][s.gay]) + tog('lq', 'quality', 'Include low-quality videos', !!s.lq) +
      '<div class="grp">Privacy</div>' + tog('hist', 'history', 'Pause watch history', !s.hist) + row('clearhist', 'trash', 'Clear watch history', '') + row('clearsearch', 'trash', 'Clear search history', '') +
      '<div class="grp">About</div>' + row('about', 'play', 'NovaPlay 2.0', 'Ad blocking on') + (N && N.mediaLog ? row('vlog', 'history', 'Video connection log', '', 'Shows what happened if a video won’t load') : '') + (crashInfo() ? row('crash', 'history', 'Problem report', '', 'What went wrong the last time NovaPlay stopped') : '');
    done();
  }
  function videoLog() {
    var t = ''; try { t = N && N.mediaLog ? N.mediaLog() : ''; } catch (e) {}
    t = t || 'Nothing yet. Play a video, then open this again.';
    sheet('Video connection log', [
      { icon: 'share', label: 'Copy log', sub: 'Then paste it in the chat', keep: true, fn: function () { if (N && N.copy) { N.copy('NovaPlay video log\n' + t); toast('Log copied'); } else if (N && N.share) N.share('NovaPlay video log\n' + t); } },
      { icon: 'share', label: 'Share log', fn: function () { if (N && N.share) N.share('NovaPlay video log\n' + t); else toast('Sharing isn’t available here'); } }
    ], '<pre class="vlog">' + esc(t) + '</pre>');
  }
  /* what went wrong the last time the app stopped unexpectedly (the shell writes it down, see CrashLog.java) */
  function crashInfo() { try { var r = N && N.crashReport ? N.crashReport() : ''; return r ? JSON.parse(r) : null; } catch (e) { return null; } }
  function crashSheet(c) {
    if (!c || !c.text) return;
    if (c.fresh && N && N.crashSeen) N.crashSeen();
    var t = c.text;
    sheet(c.fresh ? 'NovaPlay had a problem' : 'Last problem report', [
      { icon: 'share', label: 'Copy report', sub: 'Then paste it in the chat so it can be fixed', keep: true, fn: function () { if (N && N.copy) { N.copy(t); toast('Report copied'); } else if (N && N.share) N.share(t); } },
      { icon: 'share', label: 'Share report', fn: function () { if (N && N.share) N.share(t); else toast('Sharing isn’t available here'); } },
      { icon: 'trash', label: 'Delete report', fn: function () { if (N && N.crashClear) N.crashClear(); toast('Report deleted'); if (S.route.name === 'settings') render(); } }
    ], '<pre class="vlog">' + esc(t) + '</pre>');
  }
  /* Built-in Cloudflare WARP (what the 1.1.1.1 app does); the shell does the work, see WarpController.java */
  function warpInfo() { try { var r = N && N.warpState ? N.warpState() : ''; return r ? JSON.parse(r) : null; } catch (e) { return null; } }
  function warpOffer() { var w = warpInfo(); return !!w && w.state !== 'on' && w.state !== 'connecting' && w.state !== 'registering'; }
  var WARP_TXT = { off: 'Off', registering: 'Registering with Cloudflare…', connecting: 'Connecting…', permission: 'Waiting for your OK', on: 'Connected', error: 'Couldn’t connect' };
  function warpSub(w) {
    if (!w) return '';
    if (w.state === 'off') return w.msg || 'Like the 1.1.1.1 app. Helps when videos won’t load on your Wi‑Fi';
    return w.msg || WARP_TXT[w.state] || '';
  }
  function warpRows() {
    var w = warpInfo(); if (!w) return '';
    return '<button class="row" data-set="warp">' + ic('shield') + '<span class="rt">Cloudflare WARP (1.1.1.1)<small>' + esc(warpSub(w)) + '</small></span><i class="sw' + (w.wanted ? ' on' : '') + '"></i></button>' +
      '<button class="row" data-set="warpmore">' + ic('history') + '<span class="rt">WARP details<small>Log, whole-phone mode, reset</small></span><span class="rv">' + esc(WARP_TXT[w.state] || '') + '</span></button>';
  }
  function warpSheet() {
    var w = warpInfo(); if (!w) return;
    var log = ''; try { log = N.warpLog ? N.warpLog() : ''; } catch (e) {}
    log = log || 'Nothing yet. Turn WARP on, then open this again.';
    var text = 'NovaPlay WARP log\nstate: ' + w.state + (w.msg ? ' (' + w.msg + ')' : '') + '\nmode: ' + (w.all ? 'whole phone' : 'NovaPlay only') + (w.port ? '\nport: ' + w.port : '') + '\n\n' + log;
    sheet('Cloudflare WARP', [
      { icon: 'refresh', label: w.state === 'on' ? 'Reconnect' : 'Turn on / try again', fn: function () { if (N && N.warpRetry) { N.warpRetry(); toast('Connecting WARP…'); } else if (N && N.warpSet) { N.warpSet(true); toast('Connecting WARP…'); } } },
      { icon: 'shield', label: 'Cover the whole phone', sub: w.all ? 'On: other apps and downloads use WARP too (like the 1.1.1.1 app)' : 'Off: only NovaPlay uses WARP. Turn on if downloads fail', toggle: !!w.all, fn: function () { if (N && N.warpAll) { N.warpAll(!w.all); toast(w.all ? 'WARP now covers only NovaPlay' : 'WARP now covers the whole phone'); } } },
      { icon: 'share', label: 'Copy log', sub: 'Then paste it in the chat', keep: true, fn: function () { if (N && N.copy) { N.copy(text); toast('Log copied'); } else if (N && N.share) N.share(text); } },
      { icon: 'share', label: 'Share log', fn: function () { if (N && N.share) N.share(text); else toast('Sharing isn’t available here'); } },
      { icon: 'trash', label: 'Reset WARP account', sub: 'This phone registers with Cloudflare again next time', fn: function () { if (N && N.warpForget) { N.warpForget(); toast('WARP account reset'); } } }
    ], '<pre class="vlog">' + esc(text) + '</pre>');
  }
  function warpOn() { if (N && N.warpSet) { N.warpSet(true); toast('Turning WARP on…'); } }
  /* the shell tells the page whenever the WARP state changes */
  window.__onWarp = function (st) {
    if (!st) return;
    var prev = S.warp; S.warp = st.state;
    var box = $('#warpRows'); if (box && S.route.name === 'settings') box.innerHTML = warpRows();
    if (st.state === prev) return;
    var pe = $('#perr'), perr = pe && pe.classList.contains('on') && S.perr;
    if (st.state === 'on') {
      toast('WARP connected');
      if (perr && S.cur) act('reload'); /* a video that could not be loaded: load it again now */
      if (S.err) render(); /* a list that could not load: load it again now */
      else setTimeout(function () { if (S.err) render(); }, 1500); /* (the failed request may still be on its way) */
    } else {
      if (st.state === 'error' && st.msg) toast(st.msg);
      else if (st.state === 'off' && prev === 'on') toast('WARP is off');
      if (S.err) errView(S.errE); /* the WARP part of the error screens follows the state */
      if (perr) showErr(perr.m, perr.a);
    }
  };
  function setting(id) {
    var s = D.set;
    function again() { save(); if (S.route.name === 'settings') render(); }
    if (id === 'theme') pick('Theme', [['dark', 'Dark'], ['black', 'Black'], ['light', 'Light']], s.theme, function (v) { s.theme = v; applyTheme(); again(); });
    else if (id === 'ambient') pick('Ambient mode', [[0, 'Off'], [1, 'Subtle'], [2, 'Strong']], s.ambient, function (v) { s.ambient = v; applyAmbient(); again(); });
    else if (id === 'quality') pick('Default quality', [['best', 'Best available'], ['1080', '1080p'], ['720', '720p'], ['480', '480p'], ['360', '360p'], ['240', '240p']], s.quality, function (v) { s.quality = v; again(); });
    else if (id === 'gay') pick('Show', [[0, 'Straight only'], [1, 'Everything'], [2, 'Gay only']], s.gay, function (v) { s.gay = v; cache.clear(); again(); });
    else if (id === 'lq') { s.lq = s.lq ? 0 : 1; cache.clear(); again(); }
    else if (id === 'pip') { s.pip = !s.pip; if (N && N.setAutoPip) N.setAutoPip(!!s.pip); again(); }
    else if (id === 'autoplay' || id === 'bg' || id === 'resume') { s[id] = !s[id]; again(); }
    else if (id === 'hist') { s.hist = !s.hist; again(); }
    else if (id === 'clearhist') { D.hist = []; D.pos = {}; save(); toast('Watch history cleared'); }
    else if (id === 'clearsearch') { D.searches = []; save(); toast('Search history cleared'); }
    else if (id === 'about') toast('NovaPlay 2.0 · ad blocking is on');
    else if (id === 'vlog') videoLog();
    else if (id === 'warp') { var w0 = warpInfo(); if (w0 && N.warpSet) { N.warpSet(!w0.wanted); if (!w0.wanted) toast('Turning WARP on…'); } }
    else if (id === 'warpmore') warpSheet();
    else if (id === 'crash') crashSheet(crashInfo());
  }

  /* ---------- downloads ---------- */
  function stopDl() { clearInterval(S.dlTimer); S.dlTimer = 0; S.dlSig = ''; }
  function pct(x) { return x.status === 8 ? 100 : (x.total > 0 ? Math.min(100, Math.round(x.bytes / x.total * 100)) : 0); }
  function dlText(x) { return ({ 1: 'Waiting…', 2: 'Downloading · ' + pct(x) + '%', 4: 'Paused', 8: 'Downloaded · ' + fmtSize(x.total || x.bytes), 16: 'Download failed' })[x.status] || 'Waiting…'; }
  function dlItems() { try { return JSON.parse(N.downloads()); } catch (e) { return []; } }
  function dlRow(x) {
    var m = D.dl[x.id], v = m && m.vid, title = (v && v.title) || x.title || 'Video';
    return '<div class="dl" id="dl' + x.id + '"><div class="th" data-dlplay="' + x.id + '">' + (v && v.thumb ? '<img alt="" src="' + esc(v.thumb) + '">' : '') + (v && v.sec ? '<span class="dur">' + fmtT(v.sec) + '</span>' : '') + '</div>' +
      '<div class="in" data-dlplay="' + x.id + '"><h3>' + esc(title) + '</h3><div class="sub dls">' + esc(dlText(x)) + '</div>' + (x.status === 8 ? '' : '<div class="dlb"><i style="width:' + pct(x) + '%"></i></div>') + '</div>' +
      '<button class="kb" data-dlmenu="' + x.id + '" aria-label="More">' + ic('more') + '</button></div>';
  }
  function pageDownloads(r, tk) {
    if (!N || !N.downloads) { view.innerHTML = empty('download', 'Downloads work in the Android app', 'Install NovaPlay on your phone to save videos for offline viewing.'); return; }
    function draw() {
      if (tk !== S.tk) { stopDl(); return; }
      var items = dlItems().sort(function (a, b) { return b.id - a.id; });
      var sig = items.map(function (x) { return x.id + ':' + x.status; }).join(',');
      if (sig !== S.dlSig) { S.dlSig = sig; view.innerHTML = '<div class="h2">Downloads</div>' + (items.length ? items.map(dlRow).join('') : empty('download', 'No downloads yet', 'Videos you download appear here and play without internet.')); done(); }
      else items.forEach(function (x) { var el = $('#dl' + x.id); if (!el) return; $('.dls', el).textContent = dlText(x); var b = $('.dlb i', el); if (b) b.style.width = pct(x) + '%'; });
    }
    draw(); S.dlTimer = setInterval(draw, 1200);
  }
  function playOffline(x) {
    var m = D.dl[x.id], v = m && m.vid ? (V.get(m.vid.id) || m.vid) : { id: 'dl' + x.id, title: x.title || 'Downloaded video', tags: [], views: 0, rate: 0, url: '', added: '', sec: 0, thumb: '', embed: '' };
    reg(v); playVideo(v, { offline: [{ kind: 'mp4', label: 'Downloaded', h: 9999, src: x.uri }] });
  }
  function startDownload(v, s) {
    var name = safe(v.title).slice(0, 80) + ' [' + (s.label || 'video') + '].mp4';
    if (N && N.download) {
      var id = N.download(s.src, name, v.title);
      if (id) { D.dl[id] = { vid: mini(v), label: s.label }; save(); toast('Download started'); } else toast('Couldn’t start the download');
    } else { window.open(s.src, '_blank'); }
  }
  function downloadSheet(v) {
    toast('Getting download links…');
    getSources(v).then(function (l) {
      var mp = l.filter(function (s) { return s.kind === 'mp4'; }).sort(function (a, b) { return b.h - a.h; });
      if (!mp.length) { toast('No downloadable file found'); return; }
      sheet('Download', mp.map(function (s) { return { icon: 'download', label: s.label || 'Video', fn: function () { startDownload(v, s); } }; }));
    }, function (e) { toast(e.message || 'Couldn’t get download links'); });
  }

  /* ---------- search overlay ---------- */
  function openSearch(q) { $('#search').hidden = false; $('#sq').value = q || ''; renderSug(); setTimeout(function () { $('#sq').focus(); }, 60); }
  function closeSearch() { $('#search').hidden = true; $('#sq').blur(); }
  function renderSug() {
    var q = $('#sq').value.trim().toLowerCase(), h = '';
    D.searches.filter(function (x) { return !q || x.toLowerCase().indexOf(q) >= 0; }).slice(0, 10).forEach(function (x) { h += '<div class="sg" data-sq="' + esc(x) + '">' + ic('history') + '<span>' + esc(x) + '</span><button data-rmq="' + esc(x) + '" aria-label="Remove">' + ic('close') + '</button></div>'; });
    if (!q) (S.trend || []).slice(0, 8).forEach(function (t) { h += '<div class="sg" data-sq="' + esc(t.tag) + '">' + ic('trend') + '<span>' + esc(t.tag) + '</span></div>'; });
    $('#sug').innerHTML = h;
  }
  function doSearch(q) {
    q = (q || '').trim(); if (!q) return;
    D.searches = [q].concat(D.searches.filter(function (x) { return x !== q; })).slice(0, 30); save();
    closeSearch(); if (W.classList.contains('open')) minimize(); go('search?q=' + encodeURIComponent(q));
  }
  function askPage(pages) {
    sheet('Go to page', [{ icon: 'check', label: 'Go', fn: function () { var n = parseInt($('#pgIn').value, 10); if (n >= 1 && n <= pages) gotoPage(n); else toast('Enter a number from 1 to ' + pages); } }],
      '<div style="padding:4px 16px 8px"><input id="pgIn" class="inp" type="number" inputmode="numeric" min="1" max="' + pages + '" placeholder="Page 1 to ' + pages + '"></div>');
    setTimeout(function () { var i = $('#pgIn'); if (i) i.focus(); }, 260);
  }

  /* ---------- lists, queue, tags ---------- */
  function toggleList(k, v) {
    var a = D[k], i = a.findIndex(function (x) { return x.id === v.id; });
    if (i >= 0) { a.splice(i, 1); toast(k === 'liked' ? 'Removed from Liked videos' : 'Removed from Watch later'); } else { a.unshift(mini(v)); toast(k === 'liked' ? 'Added to Liked videos' : 'Saved to Watch later'); }
    save(); refreshActions();
  }
  function toggleFollow(t) {
    var low = t.toLowerCase(), i = D.follows.findIndex(function (x) { return x.toLowerCase() === low; });
    if (i >= 0) { D.follows.splice(i, 1); toast('Unfollowed #' + t); } else { D.follows.unshift(t); toast('Following #' + t); }
    save(); $$('.fol[data-fol]').forEach(function (b) { var on = isFol(b.getAttribute('data-fol')); b.classList.toggle('on', on); b.textContent = on ? 'Following' : 'Follow'; });
  }
  function addQueue(v) {
    if (!S.cur) { playVideo(v); return; }
    if (S.queue.some(function (x) { return x.id === v.id; })) { toast('Already in queue'); return; }
    S.queue.push(v); toast('Added to queue');
  }
  function playNext(v) {
    if (!S.cur) { playVideo(v); return; }
    if (v.id === S.cur.id) { toast('Already playing'); return; }
    S.queue = S.queue.filter(function (x) { return x.id !== v.id; });
    var i = S.queue.findIndex(function (x) { return x.id === S.cur.id; }); S.queue.splice(i + 1, 0, v); toast('Playing next');
  }
  function curIdx() { return S.cur ? S.queue.findIndex(function (x) { return x.id === S.cur.id; }) : -1; }
  function nextVid() {
    var i = curIdx(); if (i >= 0 && i + 1 < S.queue.length) return S.queue[i + 1];
    var seen = {}; S.queue.forEach(function (x) { seen[x.id] = 1; });
    return (S.rel || []).filter(function (x) { return !seen[x.id]; })[0] || null;
  }
  function prevVid() { var i = curIdx(); if (i > 0) return S.queue[i - 1]; var h = D.hist[1]; return h ? (V.get(h.id) || h) : null; }
  function queueSheet() {
    var i = curIdx();
    if (!S.queue.length) { toast('Your queue is empty'); return; }
    sheet('Queue', S.queue.map(function (v, n) { return { icon: n === i ? 'play' : 'queue', label: v.title, sub: n === i ? 'Now playing' : fmtT(v.sec), on: n === i, keep: true, fn: function () { if (n === i) { closeSheet(); return; } closeSheet(); playVideo(v, { keepQueue: true }); } }; }).concat([{ icon: 'trash', label: 'Clear queue', fn: function () { S.queue = S.cur ? [S.cur] : []; toast('Queue cleared'); } }]));
  }
  function openTag(t) { if (W.classList.contains('open')) minimize(); go('tag/' + encodeURIComponent(t) + '?o=most-popular'); }
  function share(v) {
    var t = v.title + '\n' + v.url;
    if (N && N.share) N.share(t); else if (navigator.share) navigator.share({ title: v.title, url: v.url }).catch(function () {}); else toast('Link: ' + v.url);
  }
  function cardMenu(v) {
    if (!v) return;
    var later = D.later.some(function (x) { return x.id === v.id; });
    var rows = [
      { icon: 'queue', label: 'Play next', fn: function () { playNext(v); } },
      { icon: 'add', label: 'Add to queue', fn: function () { addQueue(v); } },
      { icon: 'later', label: later ? 'Remove from Watch later' : 'Save to Watch later', fn: function () { toggleList('later', v); } },
      { icon: 'download', label: 'Download', fn: function () { downloadSheet(v); } },
      { icon: 'share', label: 'Share', fn: function () { share(v); } }
    ];
    if (v.tags[0]) rows.push({ icon: 'tag', label: 'More from #' + v.tags[0], fn: function () { openTag(v.tags[0]); } });
    sheet(null, rows);
  }
  function addHist(v) {
    if (!D.set.hist) return;
    D.hist = D.hist.filter(function (x) { return x.id !== v.id; }); D.hist.unshift(mini(v)); if (D.hist.length > 300) D.hist.length = 300; save();
  }

  /* ---------- watch page ---------- */
  function renderInfo(v) {
    var t0 = v.tags[0] || '';
    $('#wb').innerHTML =
      '<h1 class="wt">' + esc(v.title) + '</h1>' +
      '<div class="wm">' + fmtN(v.views) + ' views · ' + ago(v.added) + (v.rate ? ' · ' + Math.round(v.rate * 20) + '% liked' : '') + ' · ' + fmtT(v.sec) + '</div>' +
      (v.tags.length ? '<div class="wtags">' + v.tags.map(function (t) { return '<a data-tag="' + esc(t) + '">#' + esc(t) + '</a>'; }).join('') + '</div>' : '') +
      '<div class="wa" id="wa"></div>' +
      (t0 ? '<div class="wc"><a class="av" data-tag="' + esc(t0) + '" style="background:hsl(' + hue(t0) + ' 55% 40%)">' + esc(t0.charAt(0)) + '</a><div class="nm"><b>#' + esc(t0) + '</b><small>Tag</small></div><button class="fol' + (isFol(t0) ? ' on' : '') + '" data-fol="' + esc(t0) + '">' + (isFol(t0) ? 'Following' : 'Follow') + '</button></div>' : '') +
      '<div class="wu"><h3>Up next</h3><label class="ap" data-act="autoplay">Autoplay <i class="sw' + (D.set.autoplay ? ' on' : '') + '" id="apSw"></i></label></div><div id="rel"></div>';
    refreshActions();
  }
  function refreshActions() {
    var v = S.cur, el = $('#wa'); if (!v || !el) return;
    var liked = D.liked.some(function (x) { return x.id === v.id; }), later = D.later.some(function (x) { return x.id === v.id; });
    function b(act, icon, label, on) { return '<button class="ab' + (on ? ' on' : '') + '" data-act="' + act + '">' + ic(icon) + label + '</button>'; }
    el.innerHTML = b('like', 'like', liked ? 'Liked' : 'Like', liked) + b('later', 'later', later ? 'Saved' : 'Watch later', later) + b('share', 'share', 'Share') + b('download', 'download', 'Download') + b('queue', 'queue', 'Queue') + b('pip', 'pip', 'Pop-out') + b('loop', 'loop', 'Loop', vid.loop);
  }
  function loadRelated(v, tk) {
    var box = $('#rel'); if (box) box.innerHTML = skel(3);
    related(v).then(function (l) {
      if (tk !== S.ptk) return; S.rel = l;
      var b = $('#rel'); if (b) b.innerHTML = l.length ? '<div class="feed">' + l.map(card).join('') + '</div>' : '<div class="empty"><p>No related videos found.</p></div>';
    }, function () { if (tk === S.ptk && $('#rel')) $('#rel').innerHTML = '<div class="empty"><p>Couldn’t load related videos.</p></div>'; });
  }
  function openWatch() {
    W.classList.add('open'); P.classList.remove('hidden', 'mini'); P.classList.add('full');
    $('#mini').classList.remove('on'); document.body.classList.remove('hasmini'); document.body.classList.add('inwatch'); showUI(true);
  }
  function minimize() {
    if (!S.cur) return;
    if (P.classList.contains('fs')) fs(false);
    W.classList.remove('open'); P.classList.remove('full', 'ui'); P.classList.add('mini');
    $('#mini').classList.add('on'); document.body.classList.remove('inwatch'); document.body.classList.add('hasmini');
  }
  function closePlayer() {
    savePos();
    try { vid.pause(); } catch (e) {} vid.removeAttribute('src'); vid.load(); leaveEmbed();
    if (P.classList.contains('fs')) fs(false);
    P.className = 'hidden'; W.classList.remove('open'); $('#mini').classList.remove('on');
    document.body.classList.remove('hasmini', 'inwatch'); S.cur = null; S.ptk++; clearTimeout(upnT); $('#upn').classList.remove('on');
    if (N) { N.setPlaying(false, ''); N.keepAwake(false); }
  }
  function playVideo(v, o) {
    o = o || {}; reg(v); var tk = ++S.ptk;
    if (S.cur && S.cur.id !== v.id) savePos();
    S.cur = v;
    if (!o.keepQueue) S.queue = [v]; else if (!S.queue.some(function (x) { return x.id === v.id; })) S.queue.push(v);
    clearTimeout(upnT); $('#upn').classList.remove('on'); showErr(null); leaveEmbed(); triedLower = false; S.stuck = 0;
    renderInfo(v); openWatch(); addHist(v); setBusy(true);
    $('#miT').textContent = v.title; $('#miS').textContent = v.tags[0] ? '#' + v.tags[0] : '';
    $('#ws').scrollTop = 0; vid.pause(); vid.removeAttribute('src'); vid.load(); paint(0); $('#sBuf').style.width = '0';
    var p = o.offline ? Promise.resolve(o.offline) : getSources(v);
    p.then(function (l) {
      if (tk !== S.ptk) return;
      S.src = l; resumeAt = D.set.resume ? (D.pos[v.id] || 0) : 0; startSrc(chooseSrc(l));
    }, function (e) { if (tk === S.ptk) { setBusy(false); showErr((e && e.message) || 'This video can’t be played.', true); } });
    if (!o.offline) loadRelated(v, tk); else S.rel = [];
  }
  function chooseSrc(l) {
    var mp = l.filter(function (s) { return s.kind === 'mp4'; }).sort(function (a, b) { return b.h - a.h; });
    if (mp.length) {
      var pref = D.set.quality === 'best' ? 99999 : (parseInt(D.set.quality, 10) || 720);
      for (var i = 0; i < mp.length; i++) if (mp[i].h <= pref) return mp[i];
      return mp[mp.length - 1];
    }
    return l[0];
  }
  function startSrc(s) {
    if (!s) { setBusy(false); showErr('No playable stream was found.', true); return; }
    S.curSrc = s; $('#perr').classList.remove('on'); setBusy(true); lastLoad = Date.now();
    if (N && N.allowMedia && /^https?:/.test(s.src)) N.allowMedia(s.src); /* the shell fetches this server's video with the site's headers */
    /* the shell's own video server hands the file to the player over plain HTTP, so seeking and buffering work like on any web page */
    var local = ''; S.local = false;
    if (N && N.proxyUrl && s.kind === 'mp4' && /^https?:/.test(s.src)) { try { local = N.proxyUrl(s.src) || ''; } catch (e) { local = ''; } }
    if (local) { S.local = true; logv('play ' + s.label + ' through the local server'); }
    vid.src = local || s.src; vid.load();
    var pl = vid.play(); if (pl && pl.catch) pl.catch(function () {});
  }
  function showErr(m, actions) {
    var e = $('#perr');
    if (!m) { S.perr = null; e.classList.remove('on'); e.innerHTML = ''; return; }
    S.perr = { m: m, a: actions };
    e.innerHTML = '<p>' + esc(m) + '</p>' + (actions ? '<div class="r2"><button class="btn" data-act="reload">Try again</button><button class="btn ghost" data-act="embed">Use web player</button>' + (warpOffer() ? '<button class="btn ghost" data-act="warpon">Turn on WARP</button>' : '') + '</div>' : '');
    e.classList.add('on');
  }
  function useEmbed(v) {
    if (!v) return;
    try { vid.pause(); } catch (e) {} vid.removeAttribute('src'); vid.load(); showErr(null); setBusy(false);
    P.classList.add('emb'); showUI(true);
    $('#frame').innerHTML = '<iframe src="' + esc(v.embed || (SITE + '/embed/' + v.id + '/')) + '" allow="autoplay; fullscreen" allowfullscreen sandbox="allow-scripts allow-same-origin allow-presentation allow-fullscreen"></iframe>';
  }
  function leaveEmbed() { P.classList.remove('emb'); $('#frame').innerHTML = ''; }
  function setBusy(b) { P.classList.toggle('busy', !!b); }

  /* ---------- player controls ---------- */
  function showUI(sticky) { P.classList.add('ui'); clearTimeout(uiT); if (!sticky && !vid.paused) uiT = setTimeout(function () { P.classList.remove('ui'); }, 3200); }
  function toggleUI() { if (P.classList.contains('ui') && !vid.paused) { P.classList.remove('ui'); clearTimeout(uiT); } else showUI(); }
  function togglePlay() { if (!S.cur) return; if (vid.paused || vid.ended) { var p = vid.play(); if (p && p.catch) p.catch(function () {}); } else vid.pause(); showUI(); }
  function seekBy(d) { var dur = isFinite(vid.duration) ? vid.duration : 1e9; vid.currentTime = Math.max(0, Math.min(dur - 0.3, vid.currentTime + d)); paint(); }
  function fs(on) {
    if (on === undefined) on = !P.classList.contains('fs');
    P.classList.toggle('fs', on); $('#bFs').innerHTML = ic(on ? 'fsx' : 'fs');
    if (N) { N.immersive(on); N.orientation(on && vid.videoWidth >= vid.videoHeight ? 'landscape' : 'auto'); }
  }
  function paint(t) {
    var d = vid.duration; if (!isFinite(d) || !d) d = S.cur ? S.cur.sec : 0;
    var c = (t == null ? vid.currentTime : t) || 0, p = d ? Math.min(100, c / d * 100) : 0;
    $('#sPl').style.width = p + '%'; $('#sKn').style.left = p + '%'; $('#sThin').style.width = p + '%'; $('#miP').style.width = p + '%';
    $('#tCur').textContent = fmtT(c); $('#tDur').textContent = fmtT(d);
  }
  function paintBuf() { var d = vid.duration; if (!isFinite(d) || !d || !vid.buffered.length) return; $('#sBuf').style.width = Math.min(100, vid.buffered.end(vid.buffered.length - 1) / d * 100) + '%'; }
  function savePos() {
    if (!S.cur || !D.set.resume || !vid.getAttribute('src')) return;
    var t = vid.currentTime, d = isFinite(vid.duration) && vid.duration ? vid.duration : S.cur.sec;
    if (!isFinite(t) || !d) return;
    if (t < 8 || t > d - 12) delete D.pos[S.cur.id]; else D.pos[S.cur.id] = Math.floor(t);
    var k = Object.keys(D.pos); if (k.length > 400) delete D.pos[k[0]];
    save();
  }
  function playState() {
    var p = !vid.paused && !vid.ended, i = ic(p ? 'pause' : 'play');
    $('#bPlay').innerHTML = i; $('#miPlay').innerHTML = i;
    if (N) { N.setPlaying(p && !!S.cur, S.cur ? S.cur.title : ''); N.keepAwake(p); }
  }
  function onEnded() {
    if (vid.loop || !S.cur) return;
    delete D.pos[S.cur.id]; save(); P.classList.add('ui'); playState();
    var n = nextVid(); if (!n || !D.set.autoplay) return;
    var left = 5, box = $('#upn'); S.upNext = n;
    function draw() { box.innerHTML = '<small>Up next in</small><div class="cd">' + left + '</div><b>' + esc(n.title) + '</b><div class="r2"><button class="btn" data-act="upnow">Play now</button><button class="btn ghost" data-act="upcancel">Cancel</button></div>'; }
    box.classList.add('on'); draw(); clearTimeout(upnT);
    (function tick() { upnT = setTimeout(function () { left--; if (left <= 0) { box.classList.remove('on'); playVideo(n, { keepQueue: true }); } else { draw(); tick(); } }, 1000); })();
  }
  function onVidError() {
    if (!S.cur || !vid.getAttribute('src')) return;
    var er = vid.error;
    if (S.local) { /* the local server didn't work out: let the player load the site's address itself (the shell still adds the site's headers) */
      logv('local server failed (error ' + (er ? er.code : '?') + ' at ' + Math.round(vid.currentTime) + 's), using the direct address');
      S.local = false; resumeAt = vid.currentTime || resumeAt;
      vid.src = S.curSrc.src; vid.load();
      var pl = vid.play(); if (pl && pl.catch) pl.catch(function () {});
      return;
    }
    logv('video error ' + (er ? er.code : '?'));
    var cur = S.curSrc, lower = (S.src || []).filter(function (s) { return s.kind === 'mp4' && s.h < (cur ? cur.h : 99999); }).sort(function (a, b) { return b.h - a.h; });
    if (!triedLower && lower.length) { triedLower = true; resumeAt = vid.currentTime || resumeAt; toast('Switching to ' + lower[0].label); startSrc(lower[0]); return; }
    setBusy(false); showErr('This video couldn’t be loaded.', true);
  }
  /* the site's video servers answer a request that doesn't look like it comes from the site with a short
     "only available at ..." clip: far shorter than the video it should be, so open the site's own player instead */
  function looksBlocked() {
    var v = S.cur, d = vid.duration;
    return !!(v && S.curSrc && /^https?:/.test(S.curSrc.src) && v.sec >= 120 && isFinite(d) && d > 0 && d < 180 && d < v.sec * 0.2);
  }
  function blocked() { toast('The site blocked this stream. Opening its own player…'); useEmbed(S.cur); }
  vid.addEventListener('play', playState); vid.addEventListener('pause', playState);
  vid.addEventListener('playing', function () { setBusy(false); playState(); showUI(); });
  vid.addEventListener('waiting', function () { setBusy(true); });
  vid.addEventListener('canplay', function () { setBusy(false); });
  vid.addEventListener('progress', paintBuf);
  vid.addEventListener('durationchange', function () { paint(); });
  vid.addEventListener('timeupdate', function () { paint(); paintBuf(); if (Date.now() - lastSave > 5000) { lastSave = Date.now(); savePos(); } });
  vid.addEventListener('loadedmetadata', function () {
    if (looksBlocked()) { blocked(); return; }
    if (resumeAt > 0 && isFinite(vid.duration) && resumeAt < vid.duration - 6) vid.currentTime = resumeAt;
    resumeAt = 0; vid.playbackRate = S.rate || 1; paint();
  });
  vid.addEventListener('ended', onEnded); vid.addEventListener('error', onVidError);
  /* a video that stops loading (a request that never gets an answer) is reconnected from where it stopped, twice, before the error is shown */
  ['progress', 'timeupdate', 'playing', 'seeked', 'loadeddata'].forEach(function (ev) { vid.addEventListener(ev, function () { lastLoad = Date.now(); }); });
  setInterval(function () {
    if (!S.cur || !S.curSrc || !vid.getAttribute('src') || P.classList.contains('emb') || document.hidden) return;
    if (S.stuck && Date.now() - (S.stuckAt || 0) > 40000 && !vid.seeking && vid.readyState >= 3) S.stuck = 0;
    if (!(vid.seeking || !vid.paused) || vid.readyState >= 3 || Date.now() - lastLoad < 12000) return;
    if (S.local && vid.readyState === 0) { /* the local server never delivered a first byte: use the site's address itself */
      logv('local server did not answer; using the direct address');
      S.local = false; lastLoad = Date.now(); vid.src = S.curSrc.src; vid.load();
      var pl = vid.play(); if (pl && pl.catch) pl.catch(function () {});
      return;
    }
    S.stuck = (S.stuck || 0) + 1; S.stuckAt = lastLoad = Date.now();
    if (S.stuck > 2) { logv('the video stopped loading; giving up'); setBusy(false); showErr('This video stopped loading.', true); return; }
    logv('the video stopped loading at ' + Math.round(vid.currentTime) + 's; reconnecting (' + S.stuck + ')');
    toast('Reconnecting…'); resumeAt = vid.currentTime || resumeAt; startSrc(S.curSrc);
  }, 3000);

  /* gestures: tap = controls, double-tap = seek 10s, hold = 2x speed */
  (function () {
    var g = $('#gest'), lastTap = 0, lastSide = 0, tapT = 0, pressT = 0, pressed = false, prevRate = 1, acc = 0, accT = 0, ripT = 0;
    function ripple(side) {
      acc += 10; clearTimeout(accT); accT = setTimeout(function () { acc = 0; }, 900);
      var el = $(side < 0 ? '#ripL' : '#ripR'); $('span', el).textContent = acc + ' seconds'; el.classList.add('on');
      clearTimeout(ripT); ripT = setTimeout(function () { $('#ripL').classList.remove('on'); $('#ripR').classList.remove('on'); }, 550);
    }
    g.addEventListener('pointerdown', function () {
      pressed = false; clearTimeout(pressT);
      pressT = setTimeout(function () { if (!vid.paused) { pressed = true; prevRate = vid.playbackRate; vid.playbackRate = 2; $('#badge').classList.add('on'); } }, 500);
    });
    function end() { clearTimeout(pressT); if (pressed) { vid.playbackRate = prevRate; $('#badge').classList.remove('on'); } }
    g.addEventListener('pointercancel', function () { end(); pressed = false; });
    g.addEventListener('pointerup', function (e) {
      clearTimeout(pressT);
      if (pressed) { end(); pressed = false; return; }
      var r = g.getBoundingClientRect(), x = e.clientX - r.left, side = x < r.width / 3 ? -1 : (x > r.width * 2 / 3 ? 1 : 0), now = Date.now();
      if (side && lastSide === side && now - lastTap < 320) { clearTimeout(tapT); lastTap = now; seekBy(side * 10); ripple(side); return; }
      lastTap = now; lastSide = side; clearTimeout(tapT);
      tapT = setTimeout(function () { if (Date.now() - lastTap >= 300) toggleUI(); }, 320);
    });
  })();

  /* seek bar */
  (function () {
    var sk = $('#seek'), trk = $('#trk'), scrub = false;
    function at(x) { var r = trk.getBoundingClientRect(), p = Math.max(0, Math.min(1, (x - r.left) / r.width)), d = isFinite(vid.duration) && vid.duration ? vid.duration : (S.cur ? S.cur.sec : 0); return p * d; }
    sk.addEventListener('pointerdown', function (e) { scrub = true; P.classList.add('scrub'); clearTimeout(uiT); try { sk.setPointerCapture(e.pointerId); } catch (x) {} paint(at(e.clientX)); });
    sk.addEventListener('pointermove', function (e) { if (scrub) paint(at(e.clientX)); });
    function up(e) { if (!scrub) return; scrub = false; P.classList.remove('scrub'); vid.currentTime = at(e.clientX); showUI(); }
    sk.addEventListener('pointerup', up);
    sk.addEventListener('pointercancel', function () { scrub = false; P.classList.remove('scrub'); showUI(); });
  })();

  /* ambient mode: a tiny blurred copy of the video glows behind the page */
  var actx = $('#amb').getContext('2d');
  function ambLoop() {
    setTimeout(ambLoop, 100);
    if (!D.set.ambient || vid.paused || vid.readyState < 2 || !W.classList.contains('open') || document.hidden || P.classList.contains('emb')) return;
    try { actx.drawImage(vid, 0, 0, 48, 27); } catch (e) {}
  }
  function applyAmbient() { W.classList.remove('amb1', 'amb2'); if (D.set.ambient) W.classList.add('amb' + D.set.ambient); }
  function applyTheme() {
    var t = D.set.theme; document.documentElement.setAttribute('data-theme', t);
    if (N && N.setBars) N.setBars({ dark: '#0f0f0f', black: '#000000', light: '#ffffff' }[t] || '#0f0f0f', t !== 'light');
  }

  /* player sheets */
  function qualitySheet() {
    var cur = S.curSrc;
    sheet('Quality', (S.src || []).slice().sort(function (a, b) { return b.h - a.h; }).map(function (s) { var on = !!cur && cur.src === s.src; return { label: s.label + (s.kind === 'hls' ? ' (stream)' : ''), check: on, on: on, fn: function () { resumeAt = vid.currentTime; triedLower = false; startSrc(s); } }; }));
  }
  function speedSheet() {
    sheet('Playback speed', [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map(function (x) { var on = vid.playbackRate === x; return { label: x === 1 ? 'Normal' : x + '×', check: on, on: on, fn: function () { S.rate = x; vid.playbackRate = x; toast('Speed ' + (x === 1 ? 'normal' : x + '×')); } }; }));
  }
  function playerSheet() {
    sheet('Playback', [
      { icon: 'quality', label: 'Quality', sub: S.curSrc ? S.curSrc.label : '', keep: true, fn: qualitySheet },
      { icon: 'speed', label: 'Playback speed', sub: vid.playbackRate === 1 ? 'Normal' : vid.playbackRate + '×', keep: true, fn: speedSheet },
      { icon: 'ambient', label: 'Ambient mode', sub: ['Off', 'Subtle', 'Strong'][D.set.ambient], keep: true, fn: function () { D.set.ambient = (D.set.ambient + 1) % 3; applyAmbient(); save(); playerSheet(); } },
      { icon: 'loop', label: 'Loop video', toggle: vid.loop, keep: true, fn: function () { vid.loop = !vid.loop; refreshActions(); playerSheet(); } },
      { icon: 'headset', label: 'Play in background', toggle: D.set.bg, keep: true, fn: function () { D.set.bg = !D.set.bg; save(); playerSheet(); } },
      { icon: 'pip', label: 'Picture-in-picture', fn: pip }
    ]);
  }
  function pip() {
    if (N && N.pip) N.pip(); else if (vid.requestPictureInPicture) vid.requestPictureInPicture().catch(function () { toast('Picture-in-picture isn’t available'); });
  }

  /* ---------- actions ---------- */
  function act(a) {
    var v = S.cur;
    if (a === 'retry') render();
    else if (a === 'warpon') warpOn();
    else if (a === 'more') { var m = S.more; if (m) { m.auto = 0; m.failed = false; loadMore().then(function (ok) { if (ok) autoMore(); }); } }
    else if (a === 'like' && v) toggleList('liked', v);
    else if (a === 'later' && v) toggleList('later', v);
    else if (a === 'share' && v) share(v);
    else if (a === 'download' && v) downloadSheet(v);
    else if (a === 'queue') queueSheet();
    else if (a === 'pip') pip();
    else if (a === 'loop') { vid.loop = !vid.loop; refreshActions(); toast(vid.loop ? 'Loop on' : 'Loop off'); }
    else if (a === 'autoplay') { D.set.autoplay = !D.set.autoplay; save(); var sw = $('#apSw'); if (sw) sw.classList.toggle('on', D.set.autoplay); }
    else if (a === 'reload' && v) { cache.delete('src:' + v.id); playVideo(v, { keepQueue: true }); }
    else if (a === 'embed') useEmbed(v);
    else if (a === 'upnow') { clearTimeout(upnT); $('#upn').classList.remove('on'); if (S.upNext) playVideo(S.upNext, { keepQueue: true }); }
    else if (a === 'upcancel') { clearTimeout(upnT); $('#upn').classList.remove('on'); }
    else if (a === 'clearhist') { sheet('Clear watch history?', [{ icon: 'trash', label: 'Clear history', fn: function () { D.hist = []; D.pos = {}; save(); toast('Watch history cleared'); if (S.route.name === 'history') render(); } }]); }
  }

  /* ---------- events ---------- */
  document.addEventListener('click', function (e) {
    var t = e.target, a;
    if ((a = t.closest('[data-si]'))) { var r = sheetRows && sheetRows[+a.getAttribute('data-si')]; if (r) { if (!r.keep) closeSheet(); if (r.fn) r.fn(); } return; }
    if (t.closest('[data-sc]')) { closeSheet(); return; }
    if ((a = t.closest('[data-rmq]'))) { var q = a.getAttribute('data-rmq'); D.searches = D.searches.filter(function (x) { return x !== q; }); save(); renderSug(); return; }
    if ((a = t.closest('[data-sq]'))) { doSearch(a.getAttribute('data-sq')); return; }
    if ((a = t.closest('[data-menu]'))) { cardMenu(V.get(a.getAttribute('data-menu'))); return; }
    if ((a = t.closest('[data-tag]'))) { openTag(a.getAttribute('data-tag')); return; }
    if ((a = t.closest('[data-fol]'))) { toggleFollow(a.getAttribute('data-fol')); return; }
    if ((a = t.closest('[data-dlplay]'))) { var it = dlItems().filter(function (x) { return String(x.id) === a.getAttribute('data-dlplay'); })[0]; if (it && it.status === 8) playOffline(it); else if (it) toast(dlText(it)); return; }
    if ((a = t.closest('[data-dlmenu]'))) {
      var id = a.getAttribute('data-dlmenu'), item = dlItems().filter(function (x) { return String(x.id) === id; })[0], rows = [];
      if (item && item.status === 8) rows.push({ icon: 'play', label: 'Play', fn: function () { playOffline(item); } });
      rows.push({ icon: 'trash', label: 'Delete download', fn: function () { if (N && N.removeDownload) N.removeDownload(id); delete D.dl[id]; save(); S.dlSig = ''; toast('Download deleted'); } });
      sheet(null, rows); return;
    }
    if ((a = t.closest('[data-play]'))) { var v = V.get(a.getAttribute('data-play')); if (v) playVideo(v); return; }
    if ((a = t.closest('[data-tab]'))) { var tab = a.getAttribute('data-tab'); if (S.route.name === tab && window.pageYOffset > 10) window.scrollTo(0, 0); else go(tab, { root: true }); return; }
    if ((a = t.closest('[data-go]'))) { go(a.getAttribute('data-go'), { root: a.hasAttribute('data-root'), replace: a.hasAttribute('data-r') }); return; }
    if ((a = t.closest('[data-p]'))) { gotoPage(+a.getAttribute('data-p')); return; }
    if ((a = t.closest('[data-pg]'))) { askPage(+a.getAttribute('data-pg')); return; }
    if ((a = t.closest('[data-set]'))) { setting(a.getAttribute('data-set')); return; }
    if ((a = t.closest('[data-act]'))) { act(a.getAttribute('data-act')); return; }
  });
  $('#bSearch').onclick = function () { openSearch(S.route.name === 'search' ? (S.route.q.q || '') : ''); };
  $('#bGear').onclick = function () { go('settings'); };
  $('#sBack').onclick = closeSearch;
  $('#sClr').onclick = function () { $('#sq').value = ''; renderSug(); $('#sq').focus(); };
  $('#sq').addEventListener('input', renderSug);
  $('#sq').addEventListener('keydown', function (e) { if (e.key === 'Enter') doSearch($('#sq').value); });
  $('#bPlay').onclick = togglePlay; $('#miPlay').onclick = togglePlay;
  $('#bNext').onclick = function () { var n = nextVid(); if (n) playVideo(n, { keepQueue: true }); else toast('Nothing up next'); };
  $('#bPrev').onclick = function () { if (vid.currentTime > 4) { vid.currentTime = 0; return; } var p = prevVid(); if (p) playVideo(p, { keepQueue: true }); else vid.currentTime = 0; };
  $('#bMin').onclick = function (e) { e.stopPropagation(); minimize(); };
  $('#bSet').onclick = function (e) { e.stopPropagation(); playerSheet(); };
  $('#bFs').onclick = function (e) { e.stopPropagation(); fs(); };
  $('#miClose').onclick = closePlayer; $('#miInfo').onclick = openWatch;
  P.addEventListener('click', function () { if (P.classList.contains('mini')) openWatch(); });
  $('#gYes').onclick = function () { D.set.adult = true; flush(); $('#gate').hidden = true; };
  $('#gNo').onclick = function () { if (N && N.exit) N.exit(); else document.body.innerHTML = ''; };
  document.addEventListener('visibilitychange', function () { if (document.hidden && !D.set.bg && !vid.paused) vid.pause(); });
  (function () {
    var lastY = 0, tick = false, h = $('#hdr');
    window.addEventListener('scroll', function () {
      if (tick) return; tick = true;
      requestAnimationFrame(function () { var y = window.pageYOffset; if (y > lastY + 6 && y > 80) h.classList.add('hide'); else if (y < lastY - 6 || y < 40) h.classList.remove('hide'); lastY = y; tick = false; autoMore(); });
    }, { passive: true });
  })();

  /* hooks called by the Android shell */
  window.__nativeBack = function () {
    if (closeSheet()) return true;
    if (!$('#search').hidden) { closeSearch(); return true; }
    if (!$('#gate').hidden) return false;
    if (P.classList.contains('fs')) { fs(false); return true; }
    if (W.classList.contains('open')) { minimize(); return true; }
    return back();
  };
  window.__onPip = function (on) { document.body.classList.toggle('pip', !!on); if (on) P.classList.remove('ui'); };
  window.__nova = { S: S, D: D, go: go, playVideo: playVideo };

  /* ---------- boot ---------- */
  (function init() {
    var set = { bSearch: 'search', bGear: 'gear', bMin: 'down', bSet: 'gear', bPrev: 'prev', bNext: 'next', bFs: 'fs', miClose: 'close', sBack: 'back', sClr: 'close' };
    Object.keys(set).forEach(function (k) { $('#' + k).innerHTML = ic(set[k]); });
    [['home', 'Home', 'home'], ['explore', 'Explore', 'explore'], ['following', 'Following', 'subs'], ['downloads', 'Downloads', 'download'], ['you', 'You', 'you']].forEach(function (t) { $('#nav').insertAdjacentHTML('beforeend', '<a data-tab="' + t[0] + '">' + ic(t[2]) + '<span>' + t[1] + '</span></a>'); });
    playState(); applyTheme(); applyAmbient(); ambLoop();
    if (N && N.setAutoPip) N.setAutoPip(!!D.set.pip);
    if (!D.set.adult) $('#gate').hidden = false;
    trending().then(function (t) { S.trend = t; if (S.chipsFn) drawChips(); }, function () {});
    window.addEventListener('hashchange', render); render();
    setTimeout(function () { var c = crashInfo(); if (c && c.fresh) crashSheet(c); }, 700); /* the app stopped last time: show what happened */
  })();
})();
