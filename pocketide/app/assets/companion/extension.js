// PocketIDE Companion: the one door from the app into the editor.
//
// The app cannot reach into a running editor -- there is no API for "open a terminal and run
// this" from outside -- so it leaves a request in a folder only it and this Linux can see,
// and this extension acts on it. One request is one file, read once and deleted before it is
// acted on, so a request is never run twice. The folder is bound into the editor's PRoot by
// the app (/run/pocketide, the same place the phone bridge's socket lives).
//
// Anything in this Linux could write a request too; that gains it nothing, because anything
// in this Linux can already run a command. The app never reads anything back.
'use strict';

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');

const INBOX = '/run/pocketide/editor-inbox';
const EVERY_MS = 1500;

function activate(context) {
  const timer = setInterval(drain, EVERY_MS);
  context.subscriptions.push({ dispose: () => clearInterval(timer) });
  drain();
}

function deactivate() {}

function drain() {
  let names;
  try {
    names = fs.readdirSync(INBOX);
  } catch (absent) {
    return; // No inbox: the app has not asked for anything, or this is not PocketIDE.
  }
  for (const name of names.sort()) {
    if (!name.endsWith('.json')) continue;
    const file = path.join(INBOX, name);
    let request = null;
    try {
      request = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (unreadable) {
      request = null;
    }
    try {
      fs.unlinkSync(file);
    } catch (gone) {
      // Already taken by another editor window; nothing to do.
    }
    if (request) act(request);
  }
}

function act(request) {
  if (request.action !== 'terminal' || typeof request.command !== 'string') return;
  const terminal = vscode.window.createTerminal({
    name: typeof request.name === 'string' && request.name ? request.name : 'PocketIDE',
    cwd: typeof request.cwd === 'string' && request.cwd ? request.cwd : undefined,
  });
  terminal.show(true);
  terminal.sendText(request.command, true);
}

module.exports = { activate, deactivate };
