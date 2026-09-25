// PocketIDE Companion: opens the room's agent full screen each time the window loads.
//
// The room tells it what to open through its environment (set by the app when it starts
// code-server): POCKETIDE_OPEN_COMMAND, the agent's own command that shows its view;
// POCKETIDE_OPEN_PLACE, where that view lives ("editor", "sidebar" or "auto"); and
// POCKETIDE_VIEW_TYPES, the view types of the agent's editor tab, so a tab the window restored
// after a reload is kept instead of a second one being opened.
//
// A first prompt (a hand-off note) arrives as a file the app drops into POCKETIDE_PROMPT_DIR,
// the room's bridge folder: .prompt-<id>.json holding {"prompt": "..."}. The companion takes
// it (one window only), deletes it, and runs POCKETIDE_PROMPT_COMMAND(undefined, prompt), which
// opens the agent with the prompt in its composer, not sent.
'use strict';

const fs = require('fs');
const path = require('path');
const vscode = require('vscode');

const ATTEMPTS = 20;
const RETRY_MS = 1500;
const COMMAND_ID = /^[A-Za-z0-9_.-]{1,200}$/;
const PROMPT_FILE = /^\.prompt-[0-9a-f]{1,64}\.json$/;
const PROMPT_MAX_BYTES = 256 * 1024;
const PROMPT_MAX_AGE_MS = 10 * 60 * 1000;
const MAXIMIZE = {
  editor: 'workbench.action.maximizeEditorHideSidebar',
  sidebar: 'workbench.action.maximizeAuxiliaryBar',
};

function activate(context) {
  const command = process.env.POCKETIDE_OPEN_COMMAND || '';
  if (!COMMAND_ID.test(command)) return;
  const place = ['editor', 'sidebar', 'auto'].includes(process.env.POCKETIDE_OPEN_PLACE)
    ? process.env.POCKETIDE_OPEN_PLACE
    : 'auto';
  const viewTypes = (process.env.POCKETIDE_VIEW_TYPES || '').split(',').filter(Boolean);
  let stopped = false;
  context.subscriptions.push({ dispose: () => { stopped = true; } });
  const isStopped = () => stopped;
  openAgent(command, place, viewTypes, isStopped).then(() => watchPrompts(context, place, isStopped));
}

function deactivate() {}

async function openAgent(command, place, viewTypes, stopped) {
  const opened = await retried(() => (hasAgentTab(viewTypes) ? undefined : vscode.commands.executeCommand(command)), stopped);
  // Already maximized is fine: there is nothing more to do either way.
  if (opened) await vscode.commands.executeCommand(maximizeFor(place)).then(undefined, () => undefined);
}

// The agent's extension may still be starting (a phone is slow), so its command is retried.
async function retried(run, stopped) {
  for (let attempt = 0; attempt < ATTEMPTS && !stopped(); attempt++) {
    try {
      await run();
      return true;
    } catch (notReady) {
      await delay(RETRY_MS);
    }
  }
  return false;
}

function watchPrompts(context, place, stopped) {
  const command = process.env.POCKETIDE_PROMPT_COMMAND || '';
  const folder = process.env.POCKETIDE_PROMPT_DIR || '';
  if (!COMMAND_ID.test(command) || !path.isAbsolute(folder)) return;
  const check = () => {
    for (const prompt of takePrompts(folder)) openWithPrompt(command, prompt, place, stopped);
  };
  check();
  try {
    const watcher = fs.watch(folder, check);
    watcher.on('error', () => watcher.close());
    context.subscriptions.push({ dispose: () => watcher.close() });
  } catch (unwatchable) {
    // A prompt that was already there has been taken; later ones wait for the next window.
  }
}

// Each prompt file is claimed by renaming it, so of several windows exactly one opens it.
function takePrompts(folder) {
  let names;
  try {
    names = fs.readdirSync(folder);
  } catch (unreadable) {
    return [];
  }
  const prompts = [];
  for (const name of names) {
    if (!PROMPT_FILE.test(name)) continue;
    const claimed = path.join(folder, name + '.taken');
    try {
      fs.renameSync(path.join(folder, name), claimed);
    } catch (takenByAnother) {
      continue;
    }
    const prompt = readPrompt(claimed);
    try {
      fs.unlinkSync(claimed);
    } catch (alreadyGone) {
      // Nothing to clean up.
    }
    if (prompt) prompts.push(prompt);
  }
  return prompts;
}

// A plain, recent, small file with a text prompt; anything else is dropped.
function readPrompt(file) {
  try {
    const info = fs.lstatSync(file);
    if (!info.isFile() || info.size > PROMPT_MAX_BYTES || Date.now() - info.mtimeMs > PROMPT_MAX_AGE_MS) return null;
    const request = JSON.parse(fs.readFileSync(file, 'utf8'));
    return request && typeof request.prompt === 'string' && request.prompt.trim() ? request.prompt : null;
  } catch (unreadable) {
    return null;
  }
}

async function openWithPrompt(command, prompt, place, stopped) {
  const opened = await retried(() => vscode.commands.executeCommand(command, undefined, prompt), stopped);
  if (opened) await vscode.commands.executeCommand(maximizeFor(place)).then(undefined, () => undefined);
}

function hasAgentTab(viewTypes) {
  if (viewTypes.length === 0) return false;
  return vscode.window.tabGroups.all.some((group) => group.tabs.some((tab) => {
    const input = tab.input;
    const viewType = input && typeof input.viewType === 'string' ? input.viewType : '';
    // Webview panels carry a prefix before the extension's own view type.
    return viewTypes.some((wanted) => viewType === wanted || viewType.endsWith('-' + wanted));
  }));
}

function maximizeFor(place) {
  if (place !== 'auto') return MAXIMIZE[place];
  const group = vscode.window.tabGroups.activeTabGroup;
  return group && group.activeTab ? MAXIMIZE.editor : MAXIMIZE.sidebar;
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = { activate, deactivate, takePrompts };
