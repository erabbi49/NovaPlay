/* NovaPlay scriptlets: own implementations of the most common uBlock Origin scriptlets.
 * Called as window.__novaScriptlets.run([[name, arg1, arg2, ...], ...]). Each entry is wrapped in try/catch. */
(function () {
  'use strict';
  if (window.__novaScriptlets) { return; }
  var S = Object.create(null);
  function def(names, fn) { for (var i = 0; i < names.length; i++) { S[names[i]] = fn; } }

  function rnd() {
    return String.fromCharCode(97 + Math.floor(Math.random() * 26)) + Math.random().toString(36).slice(2, 9);
  }

  /* "text", "/regex/flags" and "!negated" needles */
  function toMatcher(needle) {
    if (!needle) { return null; }
    var neg = false;
    if (needle.charAt(0) === '!') { neg = true; needle = needle.slice(1); }
    var re = null;
    var last = needle.lastIndexOf('/');
    if (needle.length > 2 && needle.charAt(0) === '/' && last > 0) {
      try { re = new RegExp(needle.slice(1, last), needle.slice(last + 1).replace('g', '')); } catch (e) { re = null; }
    }
    return {
      test: function (s) {
        var r = re ? re.test(String(s)) : String(s).indexOf(needle) !== -1;
        return neg ? !r : r;
      }
    };
  }

  function constVal(raw) {
    switch (raw) {
      case 'undefined': return { v: undefined };
      case 'false': return { v: false };
      case 'true': return { v: true };
      case 'null': return { v: null };
      case 'noopFunc': return { v: function () {} };
      case 'trueFunc': return { v: function () { return true; } };
      case 'falseFunc': return { v: function () { return false; } };
      case 'throwFunc': return { v: function () { throw new Error(rnd()); } };
      case 'emptyStr': case '': return { v: '' };
      case 'emptyArr': return { v: [] };
      case 'emptyObj': return { v: {} };
      case 'yes': return { v: 'yes' };
      case 'no': return { v: 'no' };
    }
    if (/^-?\d+(\.\d+)?$/.test(raw)) { return { v: Number(raw) }; }
    return null;
  }

  /* Walks "a.b.c" starting at window, installing accessors on missing links so that the leaf is
   * defined as soon as the page creates the parent objects. */
  function walkChain(chain, leaf) {
    var props = chain.split('.');
    (function walk(owner, i) {
      var prop = props[i];
      if (i === props.length - 1) { try { leaf(owner, prop); } catch (e) {} return; }
      var cur;
      try { cur = owner[prop]; } catch (e) { return; }
      if (cur !== null && (typeof cur === 'object' || typeof cur === 'function')) { walk(cur, i + 1); return; }
      var holder = cur;
      try {
        Object.defineProperty(owner, prop, {
          configurable: true,
          get: function () { return holder; },
          set: function (v) {
            holder = v;
            if (v !== null && (typeof v === 'object' || typeof v === 'function')) { walk(v, i + 1); }
          }
        });
      } catch (e) {}
    })(window, 0);
  }

  def(['set-constant', 'set'], function (chain, raw) {
    var c = constVal(raw === undefined ? '' : raw);
    if (!c || !chain) { return; }
    walkChain(chain, function (owner, prop) {
      Object.defineProperty(owner, prop, { configurable: true, get: function () { return c.v; }, set: function () {} });
    });
  });

  def(['abort-on-property-read', 'aopr'], function (chain) {
    if (!chain) { return; }
    var msg = rnd();
    walkChain(chain, function (owner, prop) {
      Object.defineProperty(owner, prop, { configurable: true, get: function () { throw new ReferenceError(msg); }, set: function () {} });
    });
  });

  def(['abort-on-property-write', 'aopw'], function (chain) {
    if (!chain) { return; }
    var msg = rnd();
    var value;
    walkChain(chain, function (owner, prop) {
      Object.defineProperty(owner, prop, { configurable: true, get: function () { return value; }, set: function () { throw new ReferenceError(msg); } });
    });
  });

  def(['abort-current-script', 'acs', 'abort-current-inline-script', 'acis'], function (chain, needle) {
    if (!chain) { return; }
    var m = toMatcher(needle);
    var msg = rnd();
    var value;
    function check() {
      var cs = document.currentScript;
      if (!cs) { return; }
      if (!m) { throw new ReferenceError(msg); }
      if (m.test(cs.textContent || cs.src || '')) { throw new ReferenceError(msg); }
    }
    walkChain(chain, function (owner, prop) {
      try { value = owner[prop]; } catch (e) {}
      Object.defineProperty(owner, prop, {
        configurable: true,
        get: function () { check(); return value; },
        set: function (v) { check(); value = v; }
      });
    });
  });

  function timerDefuser(name) {
    return function (needle, delay) {
      var m = toMatcher(needle);
      var d = (delay === undefined || delay === '') ? null : parseInt(delay, 10);
      if (!m && (d === null || isNaN(d))) { return; }
      var orig = window[name];
      window[name] = function (fn, t) {
        try {
          var text = typeof fn === 'function' ? Function.prototype.toString.call(fn) : String(fn);
          var delayOk = d === null || isNaN(d) || t === d;
          if ((!m || m.test(text)) && delayOk) { return 0; }
        } catch (e) {}
        return orig.apply(this, arguments);
      };
    };
  }
  def(['no-setTimeout-if', 'nostif', 'setTimeout-defuser'], timerDefuser('setTimeout'));
  def(['no-setInterval-if', 'nosiif', 'setInterval-defuser'], timerDefuser('setInterval'));

  def(['prevent-window-open', 'nowoif', 'no-window-open-if', 'window.open-defuser'], function (pattern) {
    var always = (pattern === undefined || pattern === '' || pattern === '1');
    var m = always ? null : toMatcher(pattern);
    var orig = window.open;
    window.open = function (url) {
      var hit = always || (m && m.test(url));
      if (hit) { return null; }
      return orig.apply(this, arguments);
    };
  });

  function onReady(fn) {
    if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', fn, { once: true }); } else { fn(); }
  }
  function observe(fn) {
    onReady(function () {
      var pending = false;
      function later() {
        if (pending) { return; }
        pending = true;
        setTimeout(function () { pending = false; fn(); }, 150);
      }
      fn();
      try { new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true, attributes: true }); } catch (e) {}
    });
  }

  def(['remove-attr', 'ra'], function (attrs, selector) {
    if (!attrs) { return; }
    var list = attrs.split('|');
    var sel = selector || '*';
    observe(function () {
      var els = document.querySelectorAll(sel);
      for (var i = 0; i < els.length; i++) {
        for (var j = 0; j < list.length; j++) { if (els[i].hasAttribute(list[j])) { els[i].removeAttribute(list[j]); } }
      }
    });
  });

  def(['remove-class', 'rc'], function (classes, selector) {
    if (!classes) { return; }
    var list = classes.split('|');
    var sel = selector || ('.' + list.join(',.'));
    observe(function () {
      var els = document.querySelectorAll(sel);
      for (var i = 0; i < els.length; i++) {
        for (var j = 0; j < list.length; j++) { els[i].classList.remove(list[j]); }
      }
    });
  });

  def(['no-fetch-if', 'nofetchif'], function (needle) {
    var m = toMatcher(needle);
    if (!m || !window.fetch) { return; }
    var orig = window.fetch;
    window.fetch = function (input) {
      try {
        var u = typeof input === 'string' ? input : ((input && input.url) || '');
        if (m.test(u)) { return Promise.resolve(new Response('', { status: 200 })); }
      } catch (e) {}
      return orig.apply(this, arguments);
    };
  });

  def(['no-xhr-if', 'noxhrif'], function (needle) {
    var m = toMatcher(needle);
    if (!m || !window.XMLHttpRequest) { return; }
    var open = XMLHttpRequest.prototype.open;
    var send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      this.__novaBlock = m.test(url);
      return open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      if (this.__novaBlock) {
        var x = this;
        setTimeout(function () { try { x.dispatchEvent(new Event('load')); } catch (e) {} }, 0);
        return;
      }
      return send.apply(this, arguments);
    };
  });

  function delPath(obj, parts, i) {
    if (obj === null || typeof obj !== 'object') { return; }
    var p = parts[i];
    if (i === parts.length - 1) {
      if (p === '*' || p === '[]') { return; }
      try { delete obj[p]; } catch (e) {}
      return;
    }
    if (p === '*' || p === '[]') { for (var k in obj) { delPath(obj[k], parts, i + 1); } return; }
    delPath(obj[p], parts, i + 1);
  }
  function hasPath(obj, parts, i) {
    if (obj === null || typeof obj !== 'object') { return false; }
    var p = parts[i];
    if (p === '*' || p === '[]') {
      for (var k in obj) { if (i === parts.length - 1 || hasPath(obj[k], parts, i + 1)) { return true; } }
      return false;
    }
    if (!(p in obj)) { return false; }
    return i === parts.length - 1 ? true : hasPath(obj[p], parts, i + 1);
  }

  def(['json-prune', 'jsonprune'], function (rawPrune, rawNeeded) {
    var prunes = (rawPrune || '').split(/\s+/).filter(Boolean);
    if (!prunes.length) { return; }
    var needed = (rawNeeded || '').split(/\s+/).filter(Boolean);
    function apply(r) {
      try {
        if (r && typeof r === 'object') {
          var ok = true;
          for (var n = 0; n < needed.length; n++) { if (!hasPath(r, needed[n].split('.'), 0)) { ok = false; break; } }
          if (ok) { for (var p = 0; p < prunes.length; p++) { delPath(r, prunes[p].split('.'), 0); } }
        }
      } catch (e) {}
      return r;
    }
    var origParse = JSON.parse;
    JSON.parse = function () { return apply(origParse.apply(this, arguments)); };
    if (window.Response && Response.prototype.json) {
      var origJson = Response.prototype.json;
      Response.prototype.json = function () { return origJson.apply(this, arguments).then(apply); };
    }
  });

  window.__novaScriptlets = {
    run: function (list) {
      for (var i = 0; i < list.length; i++) {
        var args = list[i];
        var fn = S[args[0]];
        if (fn) { try { fn.apply(null, args.slice(1)); } catch (e) {} }
      }
    },
    has: function (name) { return !!S[name]; }
  };
})();
