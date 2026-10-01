// PocketIDE's script in the agents' VS Code pages (only there; see AgentPages.kt). It changes
// how VS Code fits a phone and adds the helpers PocketIDE's own bar calls; it changes nothing else
// and can call nothing in the app (the page has no JavaScript interface).
//
// - Fit: on a phone's narrow screen everything VS Code floats (dialogs, notices, palettes, menus,
//   hovers) stays inside the screen; editors that need more width (VS Code's settings) are drawn
//   smaller; the secondary side bar's own maximize/restore button goes, because PocketIDE's layout
//   extension keeps one thing full screen at a time.
// - run(name): PocketIDE's commands in VS Code (back, agent, terminal, settings, files, commands,
//   vsix, tools), pressed as keys no phone keyboard has (F13 to F19), which only PocketIDE's layout
//   extension listens to. Android's own key events reach the page without the key's code, which VS
//   Code needs for a shortcut, so they are pressed here, in the page, with it.
// - escape(), backTarget(): what Back closes first (a menu, a dialog, a notice), then what covers
//   the agent, else nothing ('none': PocketIDE's Back leaves the screen).
// - fit(zoom, widthDp): the page drawn smaller on a short screen; type(text): the Paste key.
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
    back: ['F13'], agent: ['F14'], terminal: ['F15'], settings: ['F16'], commands: ['F17'], vsix: ['F18'], tools: ['F19'],
    files: ['F13', 'ctrl'],
  };
  const CODES = { F13: 124, F14: 125, F15: 126, F16: 127, F17: 128, F18: 129, F19: 130, Escape: 27 };
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
