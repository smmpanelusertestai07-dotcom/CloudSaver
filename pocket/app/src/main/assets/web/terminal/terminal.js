// The `>_` terminal page: xterm.js connected to the room's shell over a WebSocket.
//
// Messages to the server are JSON text frames: {"t":"i","d":<typed text>} and
// {"t":"r","c":<columns>,"r":<rows>}. The shell's output comes back as binary frames.
// The app calls three functions: window.pocketKey(seq) types keys the phone keyboard lacks,
// window.pocketText() returns the text to copy, and window.pocketFontSize(px) sets the text size.
(function () {
  'use strict';

  var MIN_FONT = 10;
  var MAX_FONT = 24;

  var LIGHT = {
    background: '#fdfcff', foreground: '#1a1c1e', cursor: '#1a1c1e', cursorAccent: '#fdfcff',
    selectionBackground: '#c4d7f5'
  };
  var DARK = {
    background: '#121316', foreground: '#e3e2e6', cursor: '#e3e2e6', cursorAccent: '#121316',
    selectionBackground: '#3a4a63'
  };
  var darkScheme = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  var term = new Terminal({
    fontSize: 14,
    fontFamily: 'ui-monospace, "Droid Sans Mono", "Roboto Mono", monospace',
    cursorBlink: true,
    scrollback: 5000,
    theme: darkScheme && darkScheme.matches ? DARK : LIGHT
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('terminal'));

  var notice = document.getElementById('notice');
  var socket = null;

  function send(message) {
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
  }

  function resize() {
    try {
      fit.fit();
    } catch (notLaidOut) {
      return;
    }
    send({ t: 'r', c: term.cols, r: term.rows });
  }

  function say(text) {
    notice.textContent = text || '';
    notice.hidden = !text;
  }

  function connect() {
    if (socket) return;
    say(null);
    var scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var opened = new WebSocket(scheme + '//' + location.host + '/ws');
    opened.binaryType = 'arraybuffer';
    opened.onopen = function () {
      resize();
      term.focus();
    };
    opened.onmessage = function (event) {
      if (typeof event.data !== 'string') term.write(new Uint8Array(event.data));
    };
    opened.onclose = function (event) {
      socket = null;
      say(event.reason === 'The shell ended'
        ? 'The shell ended. Tap here to start a new one.'
        : 'The terminal was disconnected. Tap here to connect again.');
    };
    socket = opened;
  }

  term.onData(function (data) {
    send({ t: 'i', d: data });
  });

  window.pocketKey = function (sequence) {
    if (typeof sequence !== 'string' || !sequence) return;
    send({ t: 'i', d: sequence });
    term.focus();
  };

  // The text for the app's Copy: the selection when there is one, else the scrollback and screen.
  window.pocketText = function () {
    if (term.hasSelection()) return term.getSelection();
    var buffer = term.buffer.active;
    var lines = [];
    for (var i = 0; i < buffer.length; i++) {
      var line = buffer.getLine(i);
      lines.push(line ? line.translateToString(true) : '');
    }
    while (lines.length && !lines[lines.length - 1]) lines.pop();
    return lines.join('\n');
  };

  window.pocketFontSize = function (px) {
    var size = Math.round(Number(px));
    if (!isFinite(size)) return;
    term.options.fontSize = Math.min(MAX_FONT, Math.max(MIN_FONT, size));
    resize();
  };

  notice.addEventListener('click', connect);
  if (window.ResizeObserver) {
    new ResizeObserver(resize).observe(document.getElementById('terminal'));
  } else {
    window.addEventListener('resize', resize);
  }
  if (darkScheme && darkScheme.addEventListener) {
    darkScheme.addEventListener('change', function (event) {
      term.options.theme = event.matches ? DARK : LIGHT;
    });
  }

  connect();
})();
