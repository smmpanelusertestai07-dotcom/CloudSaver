// PocketIDE Companion: opens the room's agent full screen each time the window loads.
//
// The room tells it what to open through its environment (set by the app when it starts
// code-server): POCKETIDE_OPEN_COMMAND, the agent's own command that shows its view;
// POCKETIDE_OPEN_PLACE, where that view lives ("editor", "sidebar" or "auto"); and
// POCKETIDE_VIEW_TYPES, the view types of the agent's editor tab, so a tab the window restored
// after a reload is kept instead of a second one being opened.
'use strict';

const vscode = require('vscode');

const ATTEMPTS = 20;
const RETRY_MS = 1500;
const COMMAND_ID = /^[A-Za-z0-9_.-]{1,200}$/;
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
  openAgent(command, place, viewTypes, () => stopped);
}

function deactivate() {}

async function openAgent(command, place, viewTypes, stopped) {
  // The agent's extension may still be starting (a phone is slow), so its command is retried.
  for (let attempt = 0; attempt < ATTEMPTS && !stopped(); attempt++) {
    try {
      if (!hasAgentTab(viewTypes)) await vscode.commands.executeCommand(command);
    } catch (notReady) {
      await delay(RETRY_MS);
      continue;
    }
    // Already maximized is fine: there is nothing more to do either way.
    await vscode.commands.executeCommand(maximizeFor(place)).then(undefined, () => undefined);
    return;
  }
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

module.exports = { activate, deactivate };
