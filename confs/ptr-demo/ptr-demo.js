// ==UserScript==
// @name        PTR Demo
// @match       https://example.com/*
// @run-at      document-end
// ==/UserScript==

// Replaces the host page with a small UI that demonstrates the two ways the
// app can handle a pull-to-refresh gesture:
//   * JS control     - the page defines WebToApk.onPullToRefresh and returns true,
//                      so the app does NOT reload; the page owns the spinner.
//   * Native control - no hook, so the app just reloads the page.
// The chosen mode and the counters survive a reload via localStorage.

(function () {
  'use strict';

  var store = window.localStorage;
  var mode = store.getItem('ptr_demo_mode') || 'js';        // 'js' | 'native'
  var enabled = store.getItem('ptr_demo_enabled') !== '0';  // gesture on/off
  var jsPulls = parseInt(store.getItem('ptr_demo_pulls') || '0', 10);
  var loads = parseInt(store.getItem('ptr_demo_loads') || '0', 10) + 1;
  store.setItem('ptr_demo_loads', loads);

  if (typeof WebToApk === 'undefined') {
    document.body.innerHTML = '<h2 style="font:20px sans-serif;padding:16px">' +
      'WebToApk bridge not available</h2>';
    return;
  }

  document.body.innerHTML = [
    '<style>',
    'html,body{margin:0;padding:0;background:#101418;color:#e8eef5;',
    '  font-family:-apple-system,Roboto,sans-serif;-webkit-text-size-adjust:100%}',
    '#card{margin:16px;padding:16px;border-radius:14px;background:#1b232c}',
    'h1{font-size:21px;margin:0 0 12px}',
    '.row{display:flex;align-items:center;justify-content:space-between;',
    '  padding:9px 0;border-top:1px solid #2a3644}',
    '.row:first-of-type{border-top:0}',
    '.k{opacity:.7}',
    'button{font:inherit;padding:8px 14px;border:0;border-radius:9px;',
    '  background:#2f81f7;color:#fff}',
    'button.off{background:#3a4652}',
    '.big{font-size:18px;font-weight:700}',
    '.muted{opacity:.6;font-size:13px;margin-top:12px;line-height:1.45}',
    '#fill{height:2400px;margin:16px;border-radius:14px;',
    '  background:linear-gradient(#1b232c,#2f81f7)}',
    '</style>',
    '<div id="card">',
    '  <h1>Pull-to-Refresh Demo</h1>',
    '  <div class="row"><span class="k">Control</span>',
    '    <button id="mode"></button></div>',
    '  <div class="row"><span class="k">Gesture</span>',
    '    <button id="enabled"></button></div>',
    '  <div class="row"><span class="k">JS pulls</span>',
    '    <span class="big" id="pulls">0</span></div>',
    '  <div class="row"><span class="k">Page loads</span>',
    '    <span class="big" id="loads">0</span></div>',
    '  <div class="row"><span class="k">Last event</span>',
    '    <span id="last">-</span></div>',
    '  <div class="muted">Scroll to the top, then pull down.<br>',
    '    <b>JS control</b> &rarr; the page handles the refresh, no reload.<br>',
    '    <b>Native control</b> &rarr; the app reloads the page.',
    '  </div>',
    '</div>',
    '<div id="fill"></div>'
  ].join('\n');

  var $ = function (id) { return document.getElementById(id); };
  var stamp = function () { return new Date().toLocaleTimeString(); };

  function setLast(text) { $('last').textContent = text + ' (' + stamp() + ')'; }

  function render() {
    $('mode').textContent = mode === 'js' ? 'JS control' : 'Native control';
    $('mode').className = mode === 'js' ? '' : 'off';
    $('enabled').textContent = enabled ? 'enabled' : 'disabled';
    $('enabled').className = enabled ? '' : 'off';
    $('pulls').textContent = jsPulls;
    $('loads').textContent = loads;
  }

  function applyHook() {
    if (mode === 'js') {
      WebToApk.onPullToRefresh = function () {
        jsPulls++;
        store.setItem('ptr_demo_pulls', jsPulls);
        $('pulls').textContent = jsPulls;
        setLast('JS pull #' + jsPulls);
        WebToApk.showShortToast('JS refresh #' + jsPulls);
        // Hand the spinner back to the app after a moment.
        setTimeout(function () { WebToApk.setPullToRefreshRefreshing(false); }, 1000);
        return true; // handled by the page -> no reload
      };
    } else {
      // Not a function -> the app falls back to webview.reload().
      WebToApk.onPullToRefresh = null;
    }
  }

  $('mode').addEventListener('click', function () {
    mode = (mode === 'js') ? 'native' : 'js';
    store.setItem('ptr_demo_mode', mode);
    applyHook();
    setLast(mode === 'js' ? 'switched to JS control' : 'switched to native control');
    render();
  });

  $('enabled').addEventListener('click', function () {
    enabled = !enabled;
    store.setItem('ptr_demo_enabled', enabled ? '1' : '0');
    WebToApk.setPullToRefreshEnabled(enabled);
    setLast(enabled ? 'gesture enabled' : 'gesture disabled');
    render();
  });

  applyHook();
  WebToApk.setPullToRefreshEnabled(enabled);
  setLast('page loaded');
  render();
})();
