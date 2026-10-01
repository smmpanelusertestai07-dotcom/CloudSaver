// PocketIDE's script in the agents' VS Code pages (only there; see AgentPages.kt). It changes
// how VS Code fits a phone and adds the helpers PocketIDE's own bar calls; it changes nothing else
// and can call nothing in the app (the page has no JavaScript interface).
//
// - Fit: on a phone's narrow screen everything VS Code floats (dialogs, notices, palettes, menus,
//   hovers) stays inside the screen; editors that need more width (VS Code's settings) are drawn
//   smaller; the secondary side bar's own maximize/restore button goes, because PocketIDE's layout
//   extension keeps one thing full screen at a time.
// - run(name): PocketIDE's commands in VS Code (back, agent, terminal, files, commands, vsix, tools,
//   ide), pressed as keys no phone keyboard has (F13 to F19), which only PocketIDE's layout
//   extension listens to. Android's own key events reach the page without the key's code, which VS
//   Code needs for a shortcut, so they are pressed here, in the page, with it.
// - escape(), backTarget(): what Back closes first (a menu, a dialog, a notice), then what covers
//   the agent, else nothing ('none': PocketIDE's Back leaves the screen).
// - fit(zoom, widthDp): the page drawn smaller on a short screen; type(text): the Paste key.
// - Phone files: an agent's own "add files" opens Android's picker on the same tap. Codex and Cline
//   ask their extension for files over their webview's message port; that request is answered here
//   with the files picked on the phone, so VS Code's dialog never opens. Claude Code's own file
//   input opens the picker by itself. Any other extension's VS Code file dialog opens the picker with it. Files
//   go to Cloud Shell's file drop (files.py) into the agent's ~/projects/<agent>/uploads, through
//   this page's own address (/__pocketide/drop/), which PocketIDE's door passes to the drop: VS
//   Code's own security policy lets a page connect only to its own address. Nothing is added to the
//   screen; asking for a folder or a save stays VS Code's, with Cloud Shell's folders.
(() => {
  if (window.__pocketide || window.top !== window) return;
  const style = `
.monaco-workbench .sash-container > .monaco-sash { display: none !important; }
.monaco-workbench .part.auxiliarybar > .title .action-item:has(> .codicon-auxiliarybar-maximize),
.monaco-workbench .part.auxiliarybar > .title .action-item:has(> .codicon-auxiliarybar-restore) { display: none !important; }
.monaco-workbench .part.auxiliarybar > .title .action-item { min-width: 44px !important; }
.monaco-workbench .editor-group-container:has(> .editor-container > .editor-instance > [id^="webview-editor-element-"]) > .title { display: none !important; }
.monaco-workbench .editor-group-container:has(> .editor-container > .editor-instance > [id^="webview-editor-element-"]) > .editor-container { height: 100% !important; }
@media (max-width: 499px) {
  .monaco-workbench .editor-instance > .settings-editor, .monaco-workbench .editor-instance > .keybindings-editor { zoom: 0.7; }
}
@media (max-width: 349px) {
  .monaco-workbench .editor-instance > .settings-editor, .monaco-workbench .editor-instance > .keybindings-editor { zoom: 0.6; }
}
.monaco-dialog-modal-block .monaco-dialog-box { min-width: 0 !important; width: calc(100vw - 16px) !important; max-width: calc(100vw - 16px) !important; box-sizing: border-box !important; }
.monaco-dialog-box .dialog-message-row, .monaco-dialog-box .dialog-message-container { min-width: 0 !important; max-width: 100% !important; }
.monaco-dialog-box .dialog-message, .monaco-dialog-box .dialog-message-text, .monaco-dialog-box .dialog-message-detail { white-space: normal !important; overflow-wrap: anywhere !important; }
.monaco-dialog-box .dialog-buttons-row { flex-wrap: wrap !important; }
.monaco-dialog-box .dialog-buttons-row > .dialog-buttons { flex-wrap: wrap !important; justify-content: flex-end !important; gap: 8px !important; max-width: 100% !important; overflow: visible !important; }
.monaco-dialog-box .dialog-buttons-row > .dialog-buttons > .monaco-button { margin: 0 !important; max-width: 100% !important; white-space: normal !important; }
.monaco-workbench > .notifications-toasts, .monaco-workbench > .notifications-center { width: calc(100vw - 16px) !important; max-width: calc(100vw - 16px) !important; right: 8px !important; }
.monaco-workbench .notification-list-item .notification-list-item-buttons-container { overflow-x: auto !important; scrollbar-width: none !important; max-width: 100% !important; }
.monaco-workbench .notification-list-item .notification-list-item-buttons-container .monaco-button { flex: 0 0 auto !important; font-size: 11px !important; padding: 2px 6px !important; margin-left: 4px !important; }
.quick-input-widget { width: calc(100vw - 16px) !important; max-width: calc(100vw - 16px) !important; left: 8px !important; margin-left: 0 !important; }
.context-view .monaco-menu-container, .context-view .monaco-menu .monaco-action-bar.vertical { max-width: calc(100vw - 16px) !important; }
.monaco-hover, .workbench-hover, .workbench-hover-container .workbench-hover { max-width: calc(100vw - 16px) !important; }
.monaco-editor .suggest-widget, .monaco-editor .find-widget, .monaco-editor .parameter-hints-widget, .monaco-editor .rename-box { max-width: calc(100vw - 16px) !important; }
`;
  const addStyle = () => {
    if (document.getElementById('pocketide-style')) return;
    const node = document.createElement('style');
    node.id = 'pocketide-style';
    node.textContent = style;
    (document.head || document.documentElement).appendChild(node);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addStyle, { once: true });
  else addStyle();
  const shown = (el) => el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
  const any = (selector) => [...document.querySelectorAll(selector)].some(shown);
  const wide = (selector) => [...document.querySelectorAll(selector)].some((el) => shown(el) && el.getBoundingClientRect().width > 40);
  const overlayOpen = () => any('.quick-input-widget') || any('.monaco-dialog-box') || any('.context-view .monaco-menu') ||
    any('.notifications-toasts .notification-toast') || any('.notifications-center.visible');
  // The layout extension's keys (pocket/cloudshell/pocketide-cloudshell.sh, its package.json).
  const KEYS = {
    back: ['F13'], agent: ['F14'], terminal: ['F15'], commands: ['F17'], vsix: ['F18'], tools: ['F19'],
    files: ['F13', 'ctrl'], ide: ['F14', 'ctrl'],
  };
  const CODES = { F13: 124, F14: 125, F15: 126, F16: 127, F17: 128, F18: 129, F19: 130, Escape: 27, Enter: 13 };
  const press = (key, modifier, target) => {
    const at = target || document.querySelector('.monaco-workbench') || document.body;
    const init = {
      key, code: key, keyCode: CODES[key], which: CODES[key], ctrlKey: modifier === 'ctrl',
      bubbles: true, cancelable: true, composed: true,
    };
    at.dispatchEvent(new KeyboardEvent('keydown', init));
    at.dispatchEvent(new KeyboardEvent('keyup', init));
  };
  // The element with the cursor, inside frames of the same origin too (an agent's chat box).
  const focused = () => {
    let doc = document;
    let el = doc.activeElement;
    for (let depth = 0; el && el.tagName === 'IFRAME' && depth < 4; depth++) {
      try { doc = el.contentDocument; el = doc && doc.activeElement; } catch (e) { return null; }
    }
    return el ? { el, doc } : null;
  };
  // Each agent's VS Code port (pocket/cloudshell/pocketide-cloudshell.sh): this page's agent, from
  // its address through PocketIDE's door (<port>-<key>.localhost), else Cloud Shell's own (tests).
  // This agent's VS Code port in Cloud Shell (8080 to 8099: each agent has its own), from the door's
  // address for it; 0 for any other page. The file drop knows which agent each port is.
  const pagePort = () => {
    const door = location.host.match(/^(\d+)-[0-9a-f]{32}\.localhost(?::\d+)?$/);
    const port = Number(door ? door[1] : location.port);
    return port >= 8080 && port <= 8099 ? port : 0;
  };
  // VS Code's own file dialog: a quick input with a title and a path from the root, and its buttons;
  // [files] unless its title says it picks a folder or saves.
  const fileDialog = () => {
    const widget = document.querySelector('.quick-input-widget');
    if (!widget || !shown(widget)) return null;
    const title = widget.querySelector('.quick-input-title');
    const input = widget.querySelector('.quick-input-box input');
    const actions = widget.querySelectorAll('.quick-input-header .quick-input-action');
    if (!title || !title.textContent.trim() || !input || !input.value.startsWith('/') || !actions.length) return null;
    const files = !/folder|directory|save/i.test(title.textContent);
    return { widget, input, actions, files };
  };
  // Files picked on the phone go to Cloud Shell's file drop, into this agent's
  // ~/projects/<agent>/uploads, and come back as VS Code names them there.
  const send = async (files) => {
    const sent = [];
    for (const file of files) {
      const answer = await fetch(`/__pocketide/drop/upload?port=${pagePort()}&name=${encodeURIComponent(file.name)}`, { method: 'POST', body: file });
      const result = await answer.json();
      if (!answer.ok || !result.path) throw new Error('not sent');
      sent.push({ label: result.name, path: result.path, fsPath: result.path });
    }
    return sent;
  };
  // Android's picker, on the tap that asked for it: the files picked, or none when it was closed.
  const pick = (options) => new Promise((resolve) => {
    const chooser = document.createElement('input');
    chooser.type = 'file';
    chooser.multiple = options.allowMultiple !== false;
    if (options.imagesOnly) chooser.accept = 'image/*';
    else if (Array.isArray(options.acceptedFileExtensions) && options.acceptedFileExtensions.length) {
      chooser.accept = options.acceptedFileExtensions.map((extension) => '.' + String(extension).replace(/^\./, '')).join(',');
    }
    chooser.style.display = 'none';
    let done = false;
    const finish = (files) => {
      if (done) return;
      done = true;
      chooser.remove();
      resolve(files);
    };
    chooser.addEventListener('change', () => finish([...(chooser.files || [])]), { once: true });
    chooser.addEventListener('cancel', () => finish([]), { once: true });
    (document.body || document.documentElement).appendChild(chooser);
    chooser.click();
  });
  // A picture picked on the phone, as a data: URL (an agent that takes pictures in the chat itself).
  const dataUrl = (file) => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
  const options = (body) => {
    try {
      return JSON.parse(body || '{}') || {};
    } catch (e) {
      return {};
    }
  };
  // An agent's own "add files" asks its extension for files over its webview's message port. Those
  // requests are answered here, on the same tap, as the extension would answer them: the phone's
  // picker opens, the files go to Cloud Shell, and VS Code's dialog never opens. Asking for a folder
  // stays VS Code's, with Cloud Shell's folders. [answer] gets the files picked (none when closed).
  const REQUESTS = [
    // Codex: a fetch of vscode://codex/pick-files (or pick-file).
    {
      asks: (m) => m.type === 'fetch' && typeof m.url === 'string' && /^vscode:\/\/codex\/pick-files?$/.test(m.url),
      options: (m) => options(m.body),
      answer: async (m, picked) => {
        const files = picked.length ? await send(picked) : [];
        return {
          type: 'fetch-response', requestId: m.requestId, responseType: 'success', status: 200, headers: {},
          bodyJsonString: JSON.stringify(m.url.endsWith('/pick-file') ? { file: files[0] || null } : { files }),
        };
      },
    },
    // Cline (and its forks): FileService's selectFiles. Pictures come back as data: URLs when its
    // model takes pictures (the request's value), other files as paths.
    {
      asks: (m) => m.type === 'grpc_request' && !!m.grpc_request && m.grpc_request.method === 'selectFiles' &&
        /\.FileService$/.test(String(m.grpc_request.service)),
      options: () => ({ allowMultiple: true }),
      answer: async (m, picked) => {
        const takesPictures = !!(m.grpc_request.message && m.grpc_request.message.value);
        const pictures = takesPictures ? picked.filter((file) => /\.(png|jpe?g|webp)$/i.test(file.name)) : [];
        const others = picked.filter((file) => !pictures.includes(file));
        const values1 = await Promise.all(pictures.map(dataUrl));
        const values2 = (others.length ? await send(others) : []).map((file) => file.path);
        return { type: 'grpc_response', grpc_response: { message: { values1, values2 }, request_id: m.grpc_request.request_id } };
      },
    },
  ];
  const fromAgent = (port, event) => {
    const data = event.data;
    const message = data && data.channel === 'onmessage' && data.data && data.data.message;
    const request = message && typeof message === 'object' && REQUESTS.find((each) => each.asks(message));
    if (!request || !pagePort() || !(navigator.userActivation && navigator.userActivation.isActive)) return;
    const wanted = request.options(message);
    if (wanted.kind === 'directory') return;
    event.stopImmediatePropagation();
    const reply = (answer) => port.postMessage({ channel: 'message', args: { message: answer, transfer: [] } });
    pick(wanted)
      .then((picked) => request.answer(message, picked))
      .catch(() => request.answer(message, []))
      .then(reply);
  };
  // Each webview's port, as VS Code gets it, before VS Code listens on it.
  window.addEventListener('message', (event) => {
    const port = event.data && event.data.channel === 'webview-ready' && event.ports && event.ports[0];
    if (port) port.addEventListener('message', (e) => fromAgent(port, e));
  }, true);
  // Any other extension's "add files" that opens VS Code's file dialog: the phone's picker opens with
  // it while the tap that opened it still counts, and the dialog takes the file sent.
  const fromPhone = (dialog) => {
    pick({ allowMultiple: false }).then(async (files) => {
      if (!files.length) return;
      try {
        const [sent] = await send(files.slice(0, 1));
        dialog.input.focus();
        dialog.input.value = sent.path;
        dialog.input.dispatchEvent(new Event('input', { bubbles: true }));
        setTimeout(() => press('Enter', undefined, dialog.input), 400);
      } catch (e) {
        // Not sent (the connection dropped): the dialog stays, with Cloud Shell's files.
      }
    });
  };
  // Each file dialog once, as it opens. A dialog not opened by a tap is left as it is.
  let seen = null;
  let checking = false;
  new MutationObserver(() => {
    if (checking) return;
    checking = true;
    requestAnimationFrame(() => {
      checking = false;
      const dialog = fileDialog();
      if (!dialog) {
        seen = null;
        return;
      }
      if (seen === dialog.input) return;
      seen = dialog.input;
      if (dialog.files && pagePort() && navigator.userActivation && navigator.userActivation.isActive) fromPhone(dialog);
    });
  // `document` itself: the script may run before the page has any element (a document-start script).
  }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
  window.__pocketide = {
    overlayOpen,
    run(name) {
      const key = KEYS[name];
      if (!key) return false;
      press(key[0], key[1]);
      return true;
    },
    escape() {
      const el = document.activeElement;
      press('Escape', undefined, el && el !== document.body && el.tagName !== 'IFRAME' ? el : undefined);
      return true;
    },
    backTarget() {
      if (overlayOpen()) return 'overlay';
      const agentAlone = wide('.monaco-workbench .part.auxiliarybar') && !wide('.monaco-workbench .part.editor') &&
        !wide('.monaco-workbench .part.sidebar') && !wide('.monaco-workbench .part.panel');
      return agentAlone ? 'none' : 'vscode';
    },
    // Draws the page at [zoom] (0.5 to 1) of a WebView [widthDp] wide.
    fit(zoom, widthDp) {
      const meta = document.querySelector('meta[name="viewport"]');
      if (!meta) return false;
      const z = Math.min(1, Math.max(0.5, Number(zoom) || 1));
      const width = Math.round((Number(widthDp) || window.innerWidth) / z);
      const content = z >= 1
        ? 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no'
        : `width=${width}, initial-scale=${z}, minimum-scale=${z}, maximum-scale=${z}, user-scalable=no`;
      if (meta.getAttribute('content') !== content) meta.setAttribute('content', content);
      return true;
    },
    // Types [text] where the cursor is, as the keyboard would.
    type(text) {
      const at = focused();
      if (!at || !at.el || at.el === at.doc.body) return false;
      return at.doc.execCommand('insertText', false, String(text));
    },
  };
})();
