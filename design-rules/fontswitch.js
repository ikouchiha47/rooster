/* ============================================================
   Personal Radar mockups — font-combination switcher.
   Prototype only. No build, no dependencies beyond Google Fonts.

   How it works:
   - The choice is a single data attribute on <html>
     (e.g. <html data-font="plex">). All family stacks live in
     style.css as one html[data-font="..."] block per combination.
     This script never sets fonts directly, it only sets the
     attribute (a data change, never per-page font juggling).
   - The choice persists in localStorage under "personalo-font".
     An inline snippet in each page <head> applies it before paint
     so navigating between mockups does not flash the default.
   - Loading strategy: LAZY per selection. Only the default
     (Noto Serif + Noto Sans, the app today) ships in the static
     stylesheet @import. Every other combination injects its own
     Google Fonts <link> the first time it is selected, so the
     first view costs one small request instead of ~15 families.
   - Pairings are two-family sets: one serif carries display +
     headlines, one sans covers body/labels. (plex also brings
     Plex Mono for the mono role; archivo-spectral and playfair-lora
     name a separate header face for the display role; fraunces-inter
     keeps Fraunces for headers only and uses Inter everywhere else.)
   ============================================================ */
(function () {
  "use strict";

  var KEY = "personalo-font";
  var DEFAULT = "noto";

  /* Value must match the html[data-font="..."] blocks in style.css.
     css:null means "already covered by the static @import" (noto). */
  var COMBOS = [
    {
      value: "noto",
      label: "Noto (app today) — Noto Serif + Noto Sans",
      css: null
    },
    {
      value: "source-serif",
      label: "Source Serif 4 + Inter",
      css: "https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,400;0,600;0,700;1,400;1,600&family=Source+Serif+4:ital,wght@0,400;0,600;0,700;1,400;1,600&display=swap"
    },
    {
      value: "newsreader-inter",
      label: "Newsreader + Inter",
      css: "https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,400;0,600;0,700;1,400;1,600&family=Newsreader:ital,wght@0,500;0,700;1,500;1,600&display=swap"
    },
    {
      value: "franklin-baskerville",
      label: "Libre Franklin + Libre Baskerville",
      css: "https://fonts.googleapis.com/css2?family=Libre+Baskerville:ital,wght@0,400;0,700;1,400&family=Libre+Franklin:wght@400;600;700&display=swap"
    },
    {
      value: "archivo-spectral",
      label: "Archivo Narrow (headers) + Spectral + Inter",
      css: "https://fonts.googleapis.com/css2?family=Archivo+Narrow:wght@500;600;700&family=Inter:wght@400;600;700&family=Spectral:ital,wght@0,400;0,600;0,700;1,400;1,600&display=swap"
    },
    {
      value: "literata-public",
      label: "Literata + Public Sans",
      css: "https://fonts.googleapis.com/css2?family=Literata:ital,wght@0,400;0,600;0,700;1,400;1,600&family=Public+Sans:wght@400;600;700&display=swap"
    },
    {
      value: "playfair-lora",
      label: "Playfair Display + Lora + Inter",
      css: "https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&family=Lora:ital,wght@0,400;0,600;0,700;1,400;1,600&family=Playfair+Display:ital,wght@0,600;0,700;1,600;1,700&display=swap"
    },
    {
      value: "plex",
      label: "IBM Plex Serif + Plex Sans + Plex Mono",
      css: "https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;600;700&family=IBM+Plex+Sans:wght@400;600;700&family=IBM+Plex+Serif:ital,wght@0,400;0,600;0,700;1,400;1,600&display=swap"
    },
    {
      value: "fraunces-inter",
      label: "Fraunces (headers only) + Inter",
      css: "https://fonts.googleapis.com/css2?family=Fraunces:ital,wght@0,700;1,700&family=Inter:ital,wght@0,400;0,600;0,700;1,400;1,600&display=swap"
    }
  ];

  function isKnown(v) {
    for (var i = 0; i < COMBOS.length; i++) {
      if (COMBOS[i].value === v) return true;
    }
    return false;
  }

  function cssFor(v) {
    for (var i = 0; i < COMBOS.length; i++) {
      if (COMBOS[i].value === v) return COMBOS[i].css;
    }
    return null;
  }

  function read() {
    try {
      var v = window.localStorage.getItem(KEY);
      return isKnown(v) ? v : DEFAULT;
    } catch (e) {
      return DEFAULT;
    }
  }

  /* Inject the combination's webfonts into the given document, once. */
  function ensureFontsIn(doc, value) {
    var css = cssFor(value);
    if (!css) return; /* default combo is in the static @import */
    if (!doc || !doc.head) return;
    if (doc.head.querySelector('link[data-font-link="' + value + '"]')) return;
    if (!doc.head.querySelector("link[data-font-preconnect]")) {
      var pre1 = doc.createElement("link");
      pre1.setAttribute("rel", "preconnect");
      pre1.setAttribute("href", "https://fonts.googleapis.com");
      pre1.setAttribute("data-font-preconnect", "1");
      doc.head.appendChild(pre1);
      var pre2 = doc.createElement("link");
      pre2.setAttribute("rel", "preconnect");
      pre2.setAttribute("href", "https://fonts.gstatic.com");
      pre2.setAttribute("crossorigin", "");
      pre2.setAttribute("data-font-preconnect", "1");
      doc.head.appendChild(pre2);
    }
    var link = doc.createElement("link");
    link.setAttribute("rel", "stylesheet");
    link.setAttribute("href", css);
    link.setAttribute("data-font-link", value);
    doc.head.appendChild(link);
  }

  /* Push the choice into viewer iframes so all phones follow. Same-origin
     only; cross-origin frames are skipped silently. Storage events cover
     the reverse direction (a change inside a phone reaches the viewer). */
  function propagate(value) {
    var frames = document.querySelectorAll("iframe");
    for (var i = 0; i < frames.length; i++) {
      try {
        var d = frames[i].contentDocument;
        if (!d || !d.documentElement) continue;
        d.documentElement.setAttribute("data-font", value);
        ensureFontsIn(d, value);
        var sel = d.querySelector(".fontswitch select");
        if (sel) sel.value = value;
      } catch (e) { /* ignore */ }
    }
  }

  function apply(value, persist) {
    if (!isKnown(value)) value = DEFAULT;
    document.documentElement.setAttribute("data-font", value);
    ensureFontsIn(document, value);
    if (persist) {
      try {
        window.localStorage.setItem(KEY, value);
      } catch (e) { /* private mode etc: theme still applies live */ }
    }
    var sel = document.querySelector(".fontswitch select");
    if (sel) sel.value = value;
    propagate(value);
  }

  function injectStyles() {
    if (document.getElementById("fontswitch-style")) return;
    var css =
      ".fontswitch{position:fixed;right:10px;z-index:9999;display:flex;" +
      "align-items:center;gap:6px;padding:4px 6px;background:#FAF8F2;" +
      "color:#171513;border:1px solid #171513;border-radius:3px;" +
      "box-shadow:2px 2px 0 rgba(23,21,19,.85);" +
      "font-family:-apple-system,BlinkMacSystemFont,\"Helvetica Neue\",Arial,sans-serif;" +
      "font-size:11px;line-height:1.2;}" +
      ".fontswitch__label{font-size:10px;font-weight:700;letter-spacing:.08em;" +
      "text-transform:uppercase;white-space:nowrap;}" +
      ".fontswitch select{max-width:210px;font:inherit;color:inherit;" +
      "background:#fff;border:1px solid #837B70;border-radius:2px;padding:1px 2px;}";
    var el = document.createElement("style");
    el.id = "fontswitch-style";
    el.textContent = css;
    document.head.appendChild(el);
  }

  function buildControl(current) {
    if (document.querySelector(".fontswitch")) return;
    injectStyles();
    var box = document.createElement("div");
    box.className = "fontswitch";
    /* Standalone mockups have a sticky bottom nav: float above it.
       Viewer pages (app.html) have none: hug the viewport corner. */
    box.style.bottom = document.querySelector(".bottomnav") ? "64px" : "10px";
    var label = document.createElement("span");
    label.className = "fontswitch__label";
    label.textContent = "Fonts";
    var select = document.createElement("select");
    select.setAttribute("aria-label", "Font combination");
    for (var i = 0; i < COMBOS.length; i++) {
      var opt = document.createElement("option");
      opt.value = COMBOS[i].value;
      opt.textContent = COMBOS[i].label;
      select.appendChild(opt);
    }
    select.value = current;
    select.addEventListener("change", function () {
      apply(select.value, true);
    });
    box.appendChild(label);
    box.appendChild(select);
    document.body.appendChild(box);
  }

  /* Another page (viewer or phone) changed the choice: follow it. */
  window.addEventListener("storage", function (e) {
    if (!e || e.key !== KEY || !e.newValue) return;
    apply(e.newValue, false);
  });

  var initial = read();
  apply(initial, false);
  if (document.body) {
    buildControl(initial);
  } else {
    document.addEventListener("DOMContentLoaded", function () {
      buildControl(read());
    });
  }
})();
