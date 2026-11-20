/* NovaPlay page bootstrap: runs scriptlets for this host and applies cosmetic filters.
 * Talks to the native side through the "NovaCF" JavaScript interface. */
(function () {
  'use strict';
  if (window.__novaBoot) { return; }
  window.__novaBoot = true;
  var host = (location.hostname || '').toLowerCase();
  if (!host) { return; }

  var styleEl = null;
  function addCss(css) {
    if (!css) { return; }
    try {
      if (!styleEl) {
        styleEl = document.createElement('style');
        styleEl.setAttribute('data-nova', '1');
        (document.head || document.documentElement).appendChild(styleEl);
      }
      styleEl.appendChild(document.createTextNode(css));
    } catch (e) {}
  }

  function startCosmetic() {
    addCss(NovaCF.specific(host));
    var seenC = Object.create(null);
    var seenI = Object.create(null);
    function scan() {
      var cls = [];
      var ids = [];
      var els = document.querySelectorAll('[class],[id]');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        var id = el.id;
        if (id && typeof id === 'string' && !seenI[id]) { seenI[id] = 1; ids.push(id); }
        var cn = el.getAttribute('class');
        if (cn) {
          var parts = cn.split(/\s+/);
          for (var j = 0; j < parts.length; j++) {
            var c = parts[j];
            if (c && !seenC[c]) { seenC[c] = 1; cls.push(c); }
          }
        }
      }
      if (cls.length || ids.length) {
        addCss(NovaCF.generic(host, JSON.stringify(cls), JSON.stringify(ids)));
      }
    }
    var timer = null;
    function later() {
      if (timer) { return; }
      timer = setTimeout(function () { timer = null; try { scan(); } catch (e) {} }, 250);
    }
    function begin() {
      try { scan(); } catch (e) {}
      try { new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] }); } catch (e) {}
    }
    if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', begin, { once: true }); } else { begin(); }
  }

  function start() {
    if (typeof NovaCF === 'undefined') { return false; }
    try {
      if (NovaCF.scriptletsEnabled() && window.__novaScriptlets) {
        var list = JSON.parse(NovaCF.scriptlets(host));
        if (list && list.length) { window.__novaScriptlets.run(list); }
      }
    } catch (e) {}
    try { if (NovaCF.cosmeticEnabled()) { startCosmetic(); } } catch (e) {}
    return true;
  }

  if (!start()) {
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      if (start() || tries > 60) { clearInterval(iv); }
    }, 20);
  }
})();
