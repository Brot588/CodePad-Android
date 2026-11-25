/* CodePad 终端前端：xterm.js + 与 Kotlin PTY 的桥 */
(function () {
  'use strict';

  function b64ToBytes(b64) {
    var bin = atob(b64);
    var arr = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    return arr;
  }

  function bytesToB64(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }

  var term = new Terminal({
    fontFamily: 'JetBrains Mono, Menlo, Consolas, monospace',
    fontSize: 12.5,
    lineHeight: 1.25,
    cursorBlink: true,
    cursorStyle: 'bar',
    allowProposedApi: true,
    theme: {
      background: '#0e1015',
      foreground: '#C7CEDA',
      cursor: '#4EA1FF',
      selectionBackground: '#254A73',
      black: '#1B1F27',
      red: '#FF6B6B',
      green: '#7CE38B',
      yellow: '#E0B25C',
      blue: '#4EA1FF',
      magenta: '#C792EA',
      cyan: '#62D4E3',
      white: '#C7CEDA',
      brightBlack: '#5C6773',
      brightRed: '#FF8A80',
      brightGreen: '#A5E075',
      brightYellow: '#FFCB6B',
      brightBlue: '#82AAFF',
      brightMagenta: '#D7A9F5',
      brightCyan: '#89DDFF',
      brightWhite: '#EDF1F7'
    }
  });

  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('term'));

  function pushSize() {
    try {
      if (window.Android && window.Android.resize) {
        window.Android.resize(term.cols, term.rows);
      }
    } catch (e) {}
  }

  function syncSize() {
    try { fit.fit(); } catch (e) {}
    // fit() 失败也要把当前尺寸推过去，否则 PTY 会一直停在 24x80
    pushSize();
  }

  // xterm 自己算出新行列时同步给 PTY（fit / 字体变化都会触发）
  term.onResize(function () { pushSize(); });

  term.onData(function (data) {
    try {
      if (window.Android && window.Android.input) {
        window.Android.input(bytesToB64(new TextEncoder().encode(data)));
      }
    } catch (e) {}
  });

  window.CP = {
  setFontSize: function (px) {
    try {
      term.options.fontSize = px;
      if (typeof fit === 'function') fit();
    } catch (e) {}
  },
    term: {
      write: function (b64) { term.write(b64ToBytes(b64)); },
      // clear() 按 xterm 的语义会「保留当前行」，正好是提示符那一行；
      // 所以要整屏清干净（重启终端）必须用 reset()
      clear: function () { term.clear(); },
      reset: function () { term.reset(); },
      fit: syncSize,
      focus: function () { term.focus(); }
    }
  };

  window.addEventListener('resize', syncSize);
  if (window.ResizeObserver) {
    new ResizeObserver(syncSize).observe(document.getElementById('term'));
  }

  setTimeout(function () {
    syncSize();
    if (window.Android && window.Android.ready) window.Android.ready();
  }, 80);
  // WebView 刚挂上时可能还没尺寸，布局稳定后再对两次
  setTimeout(syncSize, 400);
  setTimeout(syncSize, 1200);
})();
