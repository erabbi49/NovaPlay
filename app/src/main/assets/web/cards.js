/* NovaPlay: extracts video cards and the next-page link from a listing page.
 * window.__novaCards(cfg) -> { items: [...], next: "url" | "", title: "..." }
 * cfg = { videoRe: "regex source tested on the link path", hostRe: "regex source tested on the link host" } */
(function () {
  'use strict';

  var AD_RE = /(^|[\s_-])(ad|ads|advert|advertisement|sponsor|sponsored|promo|banner|spot)($|[\s_-])/i;

  function txt(el) { return el ? String(el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
  function firstOfSrcset(s) {
    if (!s) { return ''; }
    var first = String(s).split(',')[0].trim().split(/\s+/)[0];
    return first || '';
  }
  function isPlaceholder(u) {
    return !u || u.indexOf('data:') === 0 || /(^|[\/_-])(blank|placeholder|spacer|pixel|1x1)([._\/-]|$)/i.test(u);
  }

  function thumbOf(root) {
    var img = root.querySelector('img');
    if (img) {
      var c = [
        img.getAttribute('src'), img.getAttribute('data-src'), img.getAttribute('data-original'),
        img.getAttribute('data-lazy'), firstOfSrcset(img.getAttribute('data-srcset')),
        firstOfSrcset(img.getAttribute('srcset'))
      ];
      for (var i = 0; i < c.length; i++) { if (!isPlaceholder(c[i])) { return abs(c[i]); } }
    }
    var source = root.querySelector('source[srcset],source[data-srcset]');
    if (source) {
      var s = firstOfSrcset(source.getAttribute('srcset') || source.getAttribute('data-srcset'));
      if (!isPlaceholder(s)) { return abs(s); }
    }
    var bg = root.querySelector('[style*="background-image"]');
    if (bg) {
      var m = /url\((['"]?)(.*?)\1\)/.exec(bg.getAttribute('style') || '');
      if (m && !isPlaceholder(m[2])) { return abs(m[2]); }
    }
    return '';
  }

  function looksLikeDuration(t) { return /^\s*(\d{1,2}:)?\d{1,2}:\d{2}\s*$/.test(t); }

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

  function previewOf(root) {
    var el = root.querySelector('[data-previewvideo],[data-preview-video]');
    if (!el && root.getAttribute) { el = root; }
    var v = el && (el.getAttribute('data-previewvideo') || el.getAttribute('data-preview-video'));
    return v ? abs(v) : '';
  }

  function inAdContainer(el) {
    var p = el;
    for (var d = 0; d < 4 && p && p !== document.body; d++) {
      var cls = typeof p.className === 'string' ? p.className : '';
      if (AD_RE.test(cls) || AD_RE.test(p.id || '')) { return true; }
      p = p.parentElement;
    }
    return false;
  }

  // the site's own links may sit on a sub-domain of the page being read (de.example.com vs example.com)
  function regDomain(h) {
    var p = String(h).split('.');
    return p.length <= 2 ? String(h) : p.slice(-2).join('.');
  }

  window.__novaCards = function (cfg) {
    var videoRe = new RegExp(cfg.videoRe, 'i');
    var hostRe = cfg.hostRe ? new RegExp(cfg.hostRe, 'i') : null;
    var anchors = document.querySelectorAll('a[href]');
    var recs = Object.create(null);
    var order = [];
    var keyOf = new Map();

    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i];
      var u;
      try { u = new URL(a.href); } catch (e) { continue; }
      if (hostRe ? !hostRe.test(u.hostname) : regDomain(u.hostname) !== regDomain(location.hostname)) { continue; }
      if (!videoRe.test(u.pathname)) { continue; }
      var key = u.origin + u.pathname;
      keyOf.set(a, key);
      var rec = recs[key];
      if (!rec) { rec = recs[key] = { url: key, anchors: [] }; order.push(rec); }
      rec.anchors.push(a);
    }

    function rootFor(a, key) {
      var best = a;
      var p = a.parentElement;
      for (var d = 0; d < 6 && p && p !== document.body && p !== document.documentElement; d++) {
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
    for (var n = 0; n < order.length && items.length < 150; n++) {
      var r = order[n];
      var main = r.anchors[0];
      for (var m = 0; m < r.anchors.length; m++) {
        if (r.anchors[m].querySelector('img')) { main = r.anchors[m]; break; }
      }
      var root = rootFor(main, r.url);
      if (inAdContainer(root)) { continue; }
      var thumb = thumbOf(root);
      var title = '';
      var cands = [];
      for (var q = 0; q < r.anchors.length; q++) {
        cands.push(r.anchors[q].getAttribute('title'));
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
      if (!title && !thumb) { continue; }

      var up = '';
      var upUrl = '';
      var ua = root.querySelector('a[href*="/users/"],a[href*="/creators/"],a[href*="/channels/"],a[href*="/pornstars/"],a[href*="/models/"]');
      if (ua) { up = txt(ua); upUrl = ua.href; }

      items.push({
        url: r.url, title: title, thumb: thumb, dur: durationOf(root), views: viewsOf(root),
        up: up, upUrl: upUrl, q: qualityOf(root), prev: previewOf(root)
      });
    }

    var next = '';
    var nextEl = document.querySelector('a[data-page="next"][href],a[rel="next"][href],link[rel="next"][href],[class*="pagination"] a[class*="next"][href]');
    if (nextEl) { next = abs(nextEl.getAttribute('href')); }
    return { items: items, next: next, title: document.title || '' };
  };
})();
