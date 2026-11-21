/* NovaPlay: reads a listing page (home, search, category, channel).
 *
 *   window.__novaCards(cfg)      -> { items, next, title, pages, tpl, cur, max, domN, jsonN }
 *   window.__novaFetchStart(cfg) -> reads the page's own HTML source in the background (so lists that the
 *                                   live page only renders lazily are complete anyway)
 *   window.__novaFetchPoll(cfg)  -> { state: idle|pending|done|error, res, msg, fetchN }
 *   window.__novaScroll()        -> scrolls one screen down (wakes lazy lists)
 *   window.__novaDiag(cfg)       -> plain-text report for Settings > Diagnostics
 *
 * cfg = { videoRe: "regex source tested on the link path", hostRe: "regex source tested on the link host" }
 *
 * Cards are collected two ways and merged: from the links in the page (DOM) and from the JSON the page
 * ships with (window.initials and friends). Whichever knows more videos wins. */
(function () {
  'use strict';

  var AD_RE = /(^|[\s_-])(ad|ads|advert|advertisement|sponsor|sponsored|promo|banner|spot)($|[\s_-])/i;
  var SKIP_KEYS = { videoModel: 1, xplayerSettings: 1, sources: 1 };
  var MAX_ITEMS = 240;

  function txt(el) { return el ? String(el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  function abs(u, base) { try { return new URL(u, base || location.href).href; } catch (e) { return ''; } }
  function str(v) { return typeof v === 'string' ? v : ''; }
  function firstOfSrcset(s) {
    if (!s) { return ''; }
    var first = String(s).split(',')[0].trim().split(/\s+/)[0];
    return first || '';
  }
  function isPlaceholder(u) {
    return !u || u.indexOf('data:') === 0 || /(^|[\/_-])(blank|placeholder|spacer|pixel|1x1)([._\/-]|$)/i.test(u);
  }
  function regDomain(h) {
    var p = String(h).split('.');
    return p.length <= 2 ? String(h) : p.slice(-2).join('.');
  }
  function looksLikeDuration(t) { return /^\s*(\d{1,2}:)?\d{1,2}:\d{2}\s*$/.test(t); }
  function compact(n) {
    n = Number(n);
    if (!isFinite(n) || n <= 0) { return ''; }
    if (n >= 1e9) { return (n / 1e9).toFixed(1).replace(/\.0$/, '') + 'B'; }
    if (n >= 1e6) { return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M'; }
    if (n >= 1e3) { return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K'; }
    return String(Math.round(n));
  }
  function clock(sec) {
    sec = Math.round(Number(sec));
    if (!isFinite(sec) || sec <= 0) { return ''; }
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    var ss = (s < 10 ? '0' : '') + s;
    return h > 0 ? (h + ':' + (m < 10 ? '0' : '') + m + ':' + ss) : (m + ':' + ss);
  }

  // ---------------------------------------------------------------- DOM cards

  function thumbOf(root, base) {
    var img = root.querySelector('img');
    if (img) {
      var c = [
        img.getAttribute('src'), img.getAttribute('data-src'), img.getAttribute('data-original'),
        img.getAttribute('data-lazy'), firstOfSrcset(img.getAttribute('data-srcset')),
        firstOfSrcset(img.getAttribute('srcset'))
      ];
      for (var i = 0; i < c.length; i++) { if (!isPlaceholder(c[i])) { return abs(c[i], base); } }
    }
    var source = root.querySelector('source[srcset],source[data-srcset]');
    if (source) {
      var s = firstOfSrcset(source.getAttribute('srcset') || source.getAttribute('data-srcset'));
      if (!isPlaceholder(s)) { return abs(s, base); }
    }
    var bg = root.querySelector('[style*="background-image"]');
    if (bg) {
      var m = /url\((['"]?)(.*?)\1\)/.exec(bg.getAttribute('style') || '');
      if (m && !isPlaceholder(m[2])) { return abs(m[2], base); }
    }
    return '';
  }

  function durationOf(root) {
    var els = root.querySelectorAll('[class*="duration"],[class*="length"],time');
    for (var i = 0; i < els.length; i++) { var t = txt(els[i]); if (looksLikeDuration(t)) { return t; } }
    var m = /(?:^|\s)((?:\d{1,2}:)?\d{1,2}:\d{2})(?:\s|$)/.exec(txt(root));
    return m ? m[1] : '';
  }

  function viewsOf(root) {
    var els = root.querySelectorAll('[class*="views"],[class*="view-count"],[class*="Views"]');
    for (var i = 0; i < els.length; i++) {
      var t = txt(els[i]);
      if (t && t.length < 24 && /\d/.test(t)) { return t; }
    }
    var m = /([\d][\d.,]*\s*[KMB]?)\s*(?:views?|plays?)/i.exec(txt(root));
    return m ? m[1].replace(/\s+/g, '') + ' views' : '';
  }

  function qualityOf(root) {
    var els = root.querySelectorAll('[class*="hd"],[class*="quality"],[class*="badge"],[class*="uhd"],[class*="Hd"]');
    for (var i = 0; i < els.length; i++) {
      var t = txt(els[i]);
      if (/^(HD|FHD|UHD|4K|8K|VR|1080p?|720p?|2160p?)$/i.test(t)) { return t.toUpperCase(); }
    }
    return '';
  }

  function previewOf(root, base) {
    var el = root.querySelector('[data-previewvideo],[data-preview-video]');
    if (!el && root.getAttribute) { el = root; }
    var v = el && (el.getAttribute('data-previewvideo') || el.getAttribute('data-preview-video'));
    return v ? abs(v, base) : '';
  }

  /* Only the card itself and its direct parent are checked: ad slots wrap their own link, while a whole
   * list can sit inside a wrapper whose class merely contains one of these words. */
  function inAdContainer(el, doc) {
    var p = el;
    for (var d = 0; d < 2 && p && p !== doc.body && p !== doc.documentElement; d++) {
      var cls = typeof p.className === 'string' ? p.className : '';
      if (AD_RE.test(cls) || AD_RE.test(p.id || '')) { return true; }
      if (p.getAttribute && (p.getAttribute('data-ad') !== null || p.getAttribute('data-sponsored') !== null)) { return true; }
      p = p.parentElement;
    }
    return false;
  }

  var lastStats = { anchors: 0, onSite: 0, videoLinks: 0, unique: 0, adDropped: 0, noTitle: 0 };

  function fromDom(doc, videoRe, hostOk, base) {
    var anchors = doc.querySelectorAll('a[href]');
    var recs = Object.create(null);
    var order = [];
    var keyOf = new Map();
    var stats = { anchors: anchors.length, onSite: 0, videoLinks: 0, unique: 0, adDropped: 0, noTitle: 0 };

    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i];
      var u;
      try { u = new URL(a.href); } catch (e) { continue; }
      if (!hostOk(u)) { continue; }
      stats.onSite++;
      if (!videoRe.test(u.pathname)) { continue; }
      stats.videoLinks++;
      var key = u.origin + u.pathname;
      keyOf.set(a, key);
      var rec = recs[key];
      if (!rec) { rec = recs[key] = { url: key, anchors: [] }; order.push(rec); }
      rec.anchors.push(a);
    }
    stats.unique = order.length;

    function rootFor(a, key) {
      var best = a;
      var p = a.parentElement;
      for (var d = 0; d < 6 && p && p !== doc.body && p !== doc.documentElement; d++) {
        var inner = p.querySelectorAll('a[href]');
        var ok = true;
        for (var j = 0; j < inner.length; j++) {
          var k = keyOf.get(inner[j]);
          if (k && k !== key) { ok = false; break; }
        }
        if (!ok) { break; }
        best = p;
        p = p.parentElement;
      }
      return best;
    }

    var items = [];
    for (var n = 0; n < order.length && items.length < MAX_ITEMS; n++) {
      var r = order[n];
      var main = r.anchors[0];
      for (var m = 0; m < r.anchors.length; m++) {
        if (r.anchors[m].querySelector('img')) { main = r.anchors[m]; break; }
      }
      var root = rootFor(main, r.url);
      if (inAdContainer(root, doc)) { stats.adDropped++; continue; }
      var thumb = thumbOf(root, base);
      var title = '';
      var cands = [];
      for (var q = 0; q < r.anchors.length; q++) {
        cands.push(r.anchors[q].getAttribute('title'));
        cands.push(r.anchors[q].getAttribute('data-video-title'));
        cands.push(r.anchors[q].getAttribute('aria-label'));
        cands.push(txt(r.anchors[q]));
      }
      var img = root.querySelector('img');
      if (img) { cands.push(img.getAttribute('alt')); }
      var named = root.querySelector('[class*="name"],[class*="title"]');
      if (named) { cands.push(txt(named)); }
      for (var c = 0; c < cands.length; c++) {
        var t = String(cands[c] || '').replace(/\s+/g, ' ').trim();
        if (t.length >= 3 && !looksLikeDuration(t) && !/^(HD|4K|VR|UHD|FHD)$/i.test(t) && t.length > title.length) { title = t; }
      }
      if (!title && !thumb) { stats.noTitle++; continue; }

      var up = '';
      var upUrl = '';
      var ua = root.querySelector('a[href*="/users/"],a[href*="/creators/"],a[href*="/channels/"],a[href*="/pornstars/"],a[href*="/models/"]');
      if (ua) { up = txt(ua); upUrl = ua.href; }

      items.push({
        url: r.url, title: title, thumb: thumb, dur: durationOf(root), views: viewsOf(root),
        up: up, upUrl: upUrl, q: qualityOf(root), prev: previewOf(root, base)
      });
    }
    lastStats = stats;
    return items;
  }

  // ---------------------------------------------------------------- JSON cards

  function cardFromJson(o, videoRe, hostOk, base) {
    var raw = str(o.pageURL) || str(o.pageUrl) || str(o.videoURL) || str(o.videoUrl) || str(o.link) || str(o.url) || str(o.href);
    if (!raw) { return null; }
    var u;
    try { u = new URL(raw, base); } catch (e) { return null; }
    if (!hostOk(u) || !videoRe.test(u.pathname)) { return null; }
    var title = str(o.title) || str(o.name) || str(o.titleLocalized) || str(o.headline);
    var bigRaw = str(o.imageURL) || str(o.imageUrl) || str(o.image) || str(o.poster);
    var smallRaw = str(o.thumbURL) || str(o.thumbUrl) || str(o.thumb) || str(o.thumbnailUrl) || str(o.thumbnail);
    var thumbRaw = bigRaw || smallRaw;
    var altRaw = (bigRaw && smallRaw && smallRaw !== bigRaw) ? smallRaw : '';
    if (!title && !thumbRaw) { return null; }

    var dur = '';
    if (typeof o.duration === 'number') { dur = clock(o.duration); }
    else if (typeof o.duration === 'string') {
      if (looksLikeDuration(o.duration)) { dur = o.duration.trim(); }
      else if (/^\d+$/.test(o.duration)) { dur = clock(o.duration); }
    }
    var views = '';
    if (typeof o.views === 'number') { views = compact(o.views); views = views ? views + ' views' : ''; }
    else if (typeof o.views === 'string') { views = o.views; }

    var up = '';
    var upUrl = '';
    var who = o.landing || o.author || o.channel || o.uploader || o.user || o.creator || o.pornstar;
    if (who && typeof who === 'object') {
      up = str(who.name) || str(who.title) || str(who.username) || str(who.nickname);
      upUrl = str(who.link) || str(who.pageURL) || str(who.pageUrl) || str(who.url);
    } else if (typeof who === 'string') { up = who; }

    var q = o.isUHD ? '4K' : (o.isHD ? 'HD' : '');
    var prev = str(o.trailerURL) || str(o.trailerUrl) || str(o.previewVideoURL) || str(o.previewVideoUrl);
    return {
      url: u.origin + u.pathname,
      title: title.replace(/\s+/g, ' ').trim(),
      thumb: thumbRaw ? abs(thumbRaw, base) : '',
      alt: altRaw ? abs(altRaw, base) : '',
      dur: dur, views: views,
      up: up, upUrl: upUrl ? abs(upUrl, base) : '',
      q: q, prev: prev ? abs(prev, base) : ''
    };
  }

  /* Depth-first, in key order, so cards come out in page order. */
  function fromJson(root, videoRe, hostOk, base) {
    var out = [];
    if (!root || typeof root !== 'object') { return out; }
    var seen = Object.create(null);
    var stack = [root];
    var visited = 0;
    while (stack.length && visited < 80000 && out.length < MAX_ITEMS) {
      var node = stack.pop();
      visited++;
      if (!node || typeof node !== 'object') { continue; }
      if (Array.isArray(node)) {
        if (node.length > 3000) { continue; }
        for (var i = node.length - 1; i >= 0; i--) {
          if (node[i] && typeof node[i] === 'object') { stack.push(node[i]); }
        }
        continue;
      }
      var card = cardFromJson(node, videoRe, hostOk, base);
      if (card) {
        if (!seen[card.url]) { seen[card.url] = 1; out.push(card); }
        continue;
      }
      var keys = Object.keys(node);
      for (var k = keys.length - 1; k >= 0; k--) {
        if (SKIP_KEYS[keys[k]]) { continue; }
        var v = node[keys[k]];
        if (v && typeof v === 'object') { stack.push(v); }
      }
    }
    return out;
  }

  /* Page numbers hidden in the JSON: { pageLinkTemplate: ".../{#}", active: 2, maxPages: 40, next: "..." }. */
  function pagingFromJson(root, base) {
    if (!root || typeof root !== 'object') { return null; }
    var queue = [root];
    var head = 0;
    while (head < queue.length && head < 40000) {
      var o = queue[head++];
      if (!o || typeof o !== 'object') { continue; }
      if (Array.isArray(o)) {
        if (o.length > 500) { continue; }
        for (var i = 0; i < o.length; i++) { if (o[i] && typeof o[i] === 'object') { queue.push(o[i]); } }
        continue;
      }
      var hasTpl = typeof o.pageLinkTemplate === 'string' && o.pageLinkTemplate;
      if (hasTpl || (typeof o.maxPages === 'number' && o.maxPages > 0)) {
        var tpl = '';
        if (hasTpl) {
          tpl = abs(o.pageLinkTemplate.replace(/\{[^{}]*\}|%d|\$\{[^}]*\}/g, '__N__'), base).replace(/__N__/g, '{n}');
          if (tpl.indexOf('{n}') < 0) { tpl = ''; }
        }
        var cur = parseInt(o.active || o.current || o.currentPage || o.page || 0, 10) || 0;
        var max = parseInt(o.maxPages || o.pages || o.totalPages || o.last || 0, 10) || 0;
        var next = typeof o.next === 'string' && o.next ? abs(o.next, base) : '';
        return { tpl: tpl, cur: cur, max: max, next: next };
      }
      var keys = Object.keys(o);
      for (var k = 0; k < keys.length; k++) {
        if (SKIP_KEYS[keys[k]]) { continue; }
        var v = o[keys[k]];
        if (v && typeof v === 'object') { queue.push(v); }
      }
    }
    return null;
  }

  // ---------------------------------------------------------------- JSON found in a document

  var parsedScripts = (typeof WeakMap === 'function') ? new WeakMap() : null;

  /* "window.initials = {...};" -> the object. Bracket matching, so trailing code does not matter. */
  function parseAssigned(text, name) {
    var i = text.indexOf(name);
    if (i < 0) { return null; }
    var s = text.indexOf('{', i);
    if (s < 0 || s - i > 60) { return null; }
    var depth = 0, inStr = false, esc = false;
    for (var k = s; k < text.length; k++) {
      var ch = text.charCodeAt(k);
      if (inStr) {
        if (esc) { esc = false; } else if (ch === 92) { esc = true; } else if (ch === 34) { inStr = false; }
      } else if (ch === 34) { inStr = true; }
      else if (ch === 123) { depth++; }
      else if (ch === 125) {
        depth--;
        if (depth === 0) {
          try { return JSON.parse(text.slice(s, k + 1)); } catch (e) { return null; }
        }
      }
    }
    return null;
  }

  var GLOBAL_NAMES = ['initials', '__INITIAL_STATE__', '__NUXT__', '__NEXT_DATA__', '__PRELOADED_STATE__'];

  function jsonOfScript(el) {
    if (parsedScripts && parsedScripts.has(el)) { return parsedScripts.get(el); }
    var res = null;
    try {
      var text = el.textContent || '';
      var type = (el.getAttribute('type') || '').toLowerCase();
      if (type.indexOf('json') >= 0) {
        if (text.length < 4000000) { res = JSON.parse(text); }
      } else if (text.length < 4000000) {
        for (var i = 0; i < GLOBAL_NAMES.length && !res; i++) {
          if (text.indexOf('window.' + GLOBAL_NAMES[i]) >= 0) { res = parseAssigned(text, 'window.' + GLOBAL_NAMES[i]); }
        }
      }
    } catch (e) { res = null; }
    if (parsedScripts) { parsedScripts.set(el, res); }
    return res;
  }

  function jsonRoots(doc, live) {
    var roots = [];
    if (live) {
      for (var i = 0; i < GLOBAL_NAMES.length; i++) {
        var g = window[GLOBAL_NAMES[i]];
        if (g && typeof g === 'object') { roots.push(g); }
      }
    }
    var scripts = doc.querySelectorAll('script');
    for (var s = 0; s < scripts.length; s++) {
      var el = scripts[s];
      if (el.src) { continue; }
      var type = (el.getAttribute('type') || '').toLowerCase();
      if (!type || type.indexOf('json') >= 0 || type.indexOf('javascript') >= 0) {
        if (live && roots.length && !type) { continue; } // already have the live globals
        var j = jsonOfScript(el);
        if (j && typeof j === 'object') { roots.push(j); }
      }
    }
    return roots;
  }

  // ---------------------------------------------------------------- pager in the DOM

  var NEXT_SELECTORS = [
    'a.prev-next-list-link--next[href]', '.prev-next-list-link--next a[href]',
    'a[rel~="next"][href]', 'link[rel="next"][href]', 'a[data-page="next"][href]',
    '[class*="pagination"] a[class*="next"][href]', '[class*="pager"] a[class*="next"][href]',
    'a[class*="next-page"][href]', 'a[class*="pagination-next"][href]',
    'a[aria-label*="ext" i][href]'
  ];

  function pagerFromDom(doc, hostOk, base) {
    var res = { next: '', pages: [], cur: 0, max: 0 };
    var i;
    for (i = 0; i < NEXT_SELECTORS.length && !res.next; i++) {
      var el;
      try { el = doc.querySelector(NEXT_SELECTORS[i]); } catch (e) { el = null; }
      if (el) {
        var h = abs(el.getAttribute('href'), base);
        if (h) { res.next = h; }
      }
    }
    var boxes = doc.querySelectorAll('[class*="pagination"],[class*="pager"],[class*="prev-next"],nav[aria-label*="age" i]');
    var seenN = Object.create(null);
    for (var b = 0; b < boxes.length; b++) {
      var links = boxes[b].querySelectorAll('a[href]');
      for (var l = 0; l < links.length; l++) {
        var a = links[l];
        var t = txt(a);
        var href = abs(a.getAttribute('href'), base);
        if (!href) { continue; }
        try { if (!hostOk(new URL(href))) { continue; } } catch (e) { continue; }
        if (/^\d{1,5}$/.test(t)) {
          var n = parseInt(t, 10);
          if (!seenN[n]) { seenN[n] = 1; res.pages.push({ n: n, url: href }); }
          if (n > res.max) { res.max = n; }
          var cls = typeof a.className === 'string' ? a.className : '';
          if (/active|current|selected/i.test(cls) || a.getAttribute('aria-current')) { res.cur = n; }
        } else if (!res.next && /^(next|next page|›|»|→|>|>>)$/i.test(t)) {
          res.next = href;
        }
      }
      // the current page is often a plain element, not a link
      var marks = boxes[b].querySelectorAll('[class*="active"],[class*="current"],[aria-current]');
      for (var m = 0; m < marks.length; m++) {
        var mt = txt(marks[m]);
        if (/^\d{1,5}$/.test(mt)) {
          var mn = parseInt(mt, 10);
          res.cur = mn;
          if (mn > res.max) { res.max = mn; }
        }
      }
    }
    res.pages.sort(function (x, y) { return x.n - y.n; });
    return res;
  }

  // ---------------------------------------------------------------- merge + collect

  function mergeCards(first, second) {
    var primary = first.length >= second.length ? first : second;
    var other = primary === first ? second : first;
    var byUrl = Object.create(null);
    var out = [];
    var i;
    for (i = 0; i < primary.length; i++) { byUrl[primary[i].url] = primary[i]; out.push(primary[i]); }
    for (i = 0; i < other.length; i++) {
      var o = other[i];
      var p = byUrl[o.url];
      if (p) {
        // fill what the primary list does not know
        for (var f in o) { if (!p[f] && o[f]) { p[f] = o[f]; } }
      } else if (out.length < MAX_ITEMS) {
        byUrl[o.url] = o;
        out.push(o);
      }
    }
    return out;
  }

  /* DOM cards and JSON cards describe the same videos. The longer list sets the order; per card the
   * title comes from the JSON (clean), the thumbnail from the DOM (known to render), the rest from whichever has it. */
  function combine(dom, js) {
    var domBy = Object.create(null);
    var jsBy = Object.create(null);
    var i;
    for (i = 0; i < dom.length; i++) { domBy[dom[i].url] = dom[i]; }
    for (i = 0; i < js.length; i++) { jsBy[js[i].url] = js[i]; }
    var out = [];
    var seen = Object.create(null);
    function add(list) {
      for (var n = 0; n < list.length && out.length < MAX_ITEMS; n++) {
        var url = list[n].url;
        if (seen[url]) { continue; }
        seen[url] = 1;
        var d = domBy[url];
        var j = jsBy[url];
        var m = {};
        var f;
        if (j) { for (f in j) { m[f] = j[f]; } }
        if (d) { for (f in d) { if (!m[f]) { m[f] = d[f]; } } }
        if (d && d.thumb) {
          if (j && j.thumb && j.thumb !== d.thumb) { m.alt = j.thumb; }
          m.thumb = d.thumb;
        }
        out.push(m);
      }
    }
    var domFirst = dom.length >= js.length;
    add(domFirst ? dom : js);
    add(domFirst ? js : dom);
    return out;
  }

  function collect(doc, live, cfg, base) {
    var videoRe = new RegExp(cfg.videoRe, 'i');
    var hostRe = cfg.hostRe ? new RegExp(cfg.hostRe, 'i') : null;
    var here = new URL(base);
    function hostOk(u) { return hostRe ? hostRe.test(u.hostname) : regDomain(u.hostname) === regDomain(here.hostname); }

    var dom = fromDom(doc, videoRe, hostOk, base);
    var roots = jsonRoots(doc, live);
    var js = [];
    var paging = null;
    for (var i = 0; i < roots.length; i++) {
      js = mergeCards(js, fromJson(roots[i], videoRe, hostOk, base));
      if (!paging) { paging = pagingFromJson(roots[i], base); }
    }
    var pager = pagerFromDom(doc, hostOk, base);
    paging = paging || { tpl: '', cur: 0, max: 0, next: '' };
    var max = Math.max(paging.max || 0, pager.max || 0);
    return {
      items: combine(dom, js),
      next: pager.next || paging.next || '',
      title: doc.title || '',
      pages: pager.pages,
      tpl: paging.tpl || '',
      cur: paging.cur || pager.cur || 0,
      max: max,
      domN: dom.length,
      jsonN: js.length
    };
  }

  window.__novaCards = function (cfg) {
    return collect(document, true, cfg, location.href);
  };

  // ---------------------------------------------------------------- the page's own source

  var fetched = null;

  window.__novaFetchStart = function (cfg) {
    if (fetched && fetched.state === 'pending') { return 'pending'; }
    fetched = { state: 'pending', res: null, msg: '', len: 0 };
    var mine = fetched;
    try {
      fetch(location.href, { credentials: 'include', headers: { 'Accept': 'text/html,application/xhtml+xml' } })
        .then(function (r) { return r.text(); })
        .then(function (html) {
          var doc = new DOMParser().parseFromString(html, 'text/html');
          mine.len = html.length;
          mine.res = collect(doc, false, cfg, location.href);
          mine.state = 'done';
        })
        .catch(function (e) { mine.state = 'error'; mine.msg = String((e && e.message) || e); });
    } catch (e) { mine.state = 'error'; mine.msg = String(e); }
    return 'started';
  };

  function mergeResults(live, src) {
    var out = {};
    for (var k in live) { out[k] = live[k]; }
    out.items = mergeCards(live.items, src.items);
    out.next = live.next || src.next || '';
    out.pages = live.pages.length >= src.pages.length ? live.pages : src.pages;
    out.tpl = live.tpl || src.tpl || '';
    out.cur = live.cur || src.cur || 0;
    out.max = Math.max(live.max || 0, src.max || 0);
    return out;
  }

  window.__novaFetchPoll = function (cfg) {
    if (!fetched) { return { state: 'idle' }; }
    var out = { state: fetched.state, msg: fetched.msg, len: fetched.len };
    if (fetched.state === 'done') {
      out.res = mergeResults(window.__novaCards(cfg), fetched.res);
      out.fetchN = fetched.res.items.length;
      out.fetchDomN = fetched.res.domN;
      out.fetchJsonN = fetched.res.jsonN;
    }
    return out;
  };

  // ---------------------------------------------------------------- helpers for the scraper

  window.__novaScroll = function () {
    var before = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
    try {
      window.scrollBy(0, Math.max(600, Math.round((window.innerHeight || 800) * 0.9)));
      window.dispatchEvent(new Event('scroll'));
      document.dispatchEvent(new Event('scroll'));
    } catch (e) {}
    return before;
  };

  window.__novaDiag = function (cfg) {
    var L = [];
    function line(k, v) { L.push(k + ': ' + v); }
    var r = null;
    try { r = window.__novaCards(cfg); } catch (e) { line('cards error', String(e && e.message || e)); }
    line('url', location.href);
    line('title', document.title);
    line('ready/visible', document.readyState + ' / ' + document.visibilityState);
    line('anchors (all / on site / video links / unique)', lastStats.anchors + ' / ' + lastStats.onSite + ' / ' + lastStats.videoLinks + ' / ' + lastStats.unique);
    line('dropped as ad / no title', lastStats.adDropped + ' / ' + lastStats.noTitle);
    if (r) {
      line('cards (merged / dom / json)', r.items.length + ' / ' + r.domN + ' / ' + r.jsonN);
      line('next link', r.next || '(none)');
      line('pager (cur / max / links)', r.cur + ' / ' + r.max + ' / ' + r.pages.length);
      line('page template', r.tpl || '(none)');
      for (var i = 0; i < Math.min(3, r.items.length); i++) {
        line('card ' + (i + 1), r.items[i].title.slice(0, 50) + ' | ' + r.items[i].dur + ' | ' + r.items[i].views + ' | thumb ' + (r.items[i].thumb ? 'yes' : 'NO'));
      }
    }
    var keys = [];
    for (var g = 0; g < GLOBAL_NAMES.length; g++) {
      var v = window[GLOBAL_NAMES[g]];
      if (v && typeof v === 'object') { keys.push(GLOBAL_NAMES[g] + '{' + Object.keys(v).slice(0, 12).join(',') + '}'); }
    }
    line('json globals', keys.length ? keys.join(' ') : '(none)');
    try {
      var as = document.querySelectorAll('a[href]');
      var shown = 0;
      var re = new RegExp(cfg.videoRe, 'i');
      for (var a = 0; a < as.length && shown < 2; a++) {
        var u;
        try { u = new URL(as[a].href); } catch (e) { continue; }
        if (re.test(u.pathname)) { line('sample link', as[a].outerHTML.slice(0, 260)); shown++; }
      }
    } catch (e) {}
    var body = txt(document.body);
    line('page text', body.length + ' chars: ' + body.slice(0, 160));
    return L.join('\n');
  };
})();
