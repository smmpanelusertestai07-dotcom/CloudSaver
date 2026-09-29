// PocketIDE Companion: shows the agent the owner picked in PocketIDE full screen, and opens the
// sign-in terminal with its command already typed, so the owner only presses Enter.
//
// The app asks through small request files it drops into ~/.pocketide/requests:
//   {"do": "show", "agent": "<extension id>"}
//   {"do": "terminal", "title": "...", "text": "...", "cwd": "/root/projects/..."}
// ~/.pocketide/agents.json lists the agents and how each one opens. Only a window whose screen
// is connected takes a request: after a reload the old window lingers for a while without a
// screen, and a request it took would open where nobody sees it.
'use strict';

const fs = require('fs');
const path = require('path');
const vscode = require('vscode');

const HOME = process.env.HOME || '/root';
const REQUESTS = path.join(HOME, '.pocketide', 'requests');
const AGENTS = path.join(HOME, '.pocketide', 'agents.json');
// What the companion did, for Computer > Repair and for a bug report: no prompts, no file contents.
const LOG = path.join(HOME, '.pocketide', 'companion.log');
const MAX_LOG_BYTES = 64 * 1024;
const REQUEST_FILE = /^[0-9A-Za-z-]{1,64}\.json$/;
const COMMAND_ID = /^[A-Za-z0-9_.-]{1,200}$/;
const MAX_REQUEST_BYTES = 16 * 1024;
const MAX_REQUEST_AGE_MS = 2 * 60 * 1000;
const LIVE_PROBE_MS = 2500;
const ATTEMPTS = 20;
const RETRY_MS = 1500;
const CHECK_MS = 2000;
const SHELL_WAIT_MS = 8000;
const SPARE_CONTAINERS = ['pocketide-agent-1', 'pocketide-agent-2', 'pocketide-agent-3',
  'pocketide-agent-4', 'pocketide-agent-5', 'pocketide-agent-6'];

let busy = false;

function log(message) {
  try {
    if (fs.existsSync(LOG) && fs.statSync(LOG).size > MAX_LOG_BYTES) fs.renameSync(LOG, LOG + '.old');
    fs.appendFileSync(LOG, `${new Date().toISOString()} [${process.pid}] ${message}\n`);
  } catch (unwritable) {
    // The log is a convenience only.
  }
}

function activate(context) {
  log('activated');
  let stopped = false;
  context.subscriptions.push({ dispose: () => { stopped = true; } });
  const isStopped = () => stopped;
  try {
    fs.mkdirSync(REQUESTS, { recursive: true });
  } catch (unwritable) {
    return;
  }
  const check = () => { takeRequests(isStopped).catch(() => undefined); };
  check();
  try {
    const watcher = fs.watch(REQUESTS, check);
    watcher.on('error', () => watcher.close());
    context.subscriptions.push({ dispose: () => watcher.close() });
  } catch (unwatchable) {
    // The timer below still finds every request.
  }
  const timer = setInterval(check, CHECK_MS);
  context.subscriptions.push({ dispose: () => clearInterval(timer) });
}

function deactivate() {}

// The agents PocketIDE knows, from the file the app keeps up to date.
function readAgents() {
  try {
    const list = JSON.parse(fs.readFileSync(AGENTS, 'utf8'));
    return Array.isArray(list) ? list.filter((agent) => agent && typeof agent.id === 'string') : [];
  } catch (unreadable) {
    return [];
  }
}

// An agent whose view lives in the activity bar (hidden on a phone) moves to the secondary side
// bar, where the others already are, into a container of its own, so it fills the screen. VS Code
// knows a container an extension adds by this longer name, remembers the move, and opens the
// container. It says nothing when it cannot move a view, so the names are checked first.
async function placeViews(agent, stopped) {
  const movable = readAgents().filter((entry) => Array.isArray(entry.views) && entry.views.length > 0);
  const index = movable.findIndex((entry) => entry.id.toLowerCase() === agent.id.toLowerCase());
  const views = (Array.isArray(agent.views) ? agent.views : []).filter((id) => COMMAND_ID.test(id));
  if (index < 0 || index >= SPARE_CONTAINERS.length || views.length === 0) return;
  const destinationId = `workbench.view.extension.${SPARE_CONTAINERS[index]}`;
  const known = new Set(await vscode.commands.getCommands(true));
  const missing = [destinationId].concat(views.map((id) => `${id}.focus`)).filter((id) => !known.has(id));
  if (missing.length > 0) {
    log(`cannot move ${views.join(',')}: ${missing.join(',')} not found`);
    return;
  }
  const moved = await retried(() => vscode.commands.executeCommand('vscode.moveViews', { viewIds: views, destinationId }), stopped);
  log(`move ${views.join(',')} to ${destinationId}: ${moved ? 'done' : 'failed'}`);
}

// True when this window's screen answers: a window whose page was reloaded or closed keeps
// running for a while, and every call to its screen waits unanswered.
async function connected() {
  const probe = vscode.commands.getCommands(false).then(() => true, () => false);
  const timeout = new Promise((resolve) => setTimeout(() => resolve(false), LIVE_PROBE_MS));
  return Promise.race([probe, timeout]);
}

async function takeRequests(stopped) {
  if (busy || stopped()) return;
  busy = true;
  try {
    const names = pending();
    if (names.length === 0 || !(await connected())) return;
    for (const name of names) {
      const request = claim(name);
      if (request) await handle(request, stopped);
    }
  } finally {
    busy = false;
  }
}

function pending() {
  try {
    return fs.readdirSync(REQUESTS).filter((name) => REQUEST_FILE.test(name)).sort();
  } catch (unreadable) {
    return [];
  }
}

// A request is claimed by renaming it, so of several windows exactly one takes it.
function claim(name) {
  const file = path.join(REQUESTS, name);
  const taken = file + '.taken-' + process.pid;
  try {
    fs.renameSync(file, taken);
  } catch (takenByAnother) {
    return null;
  }
  try {
    const info = fs.lstatSync(taken);
    if (!info.isFile() || info.size > MAX_REQUEST_BYTES || Date.now() - info.mtimeMs > MAX_REQUEST_AGE_MS) return null;
    const request = JSON.parse(fs.readFileSync(taken, 'utf8'));
    return request && typeof request === 'object' ? request : null;
  } catch (unreadable) {
    return null;
  } finally {
    try {
      fs.unlinkSync(taken);
    } catch (alreadyGone) {
      // Nothing to clean up.
    }
  }
}

async function handle(request, stopped) {
  log(`request ${request.do} ${typeof request.agent === 'string' ? request.agent : ''}`);
  switch (request.do) {
    case 'show':
      return show(String(request.agent || ''), stopped);
    case 'terminal':
      return terminal(request);
    default:
      return undefined;
  }
}

async function show(agentId, stopped) {
  const agent = readAgents().find((entry) => entry.id.toLowerCase() === agentId.toLowerCase());
  if (!agent) return;
  await placeViews(agent, stopped);
  const commands = (Array.isArray(agent.open) ? agent.open : [])
    .concat((Array.isArray(agent.views) ? agent.views : []).map((view) => view + '.focus'))
    .filter((id) => COMMAND_ID.test(id));
  // The agent's extension may still be starting (a phone is slow), so its command is retried.
  for (const command of commands.slice(0, 1)) {
    const opened = await retried(() => vscode.commands.executeCommand(command), stopped);
    log(`show ${agent.id} with ${command}: ${opened ? 'done' : 'failed'}`);
  }
  await run('workbench.action.maximizeAuxiliaryBar');
}

// A terminal where the editor goes, with every side bar closed so it fills the screen, and the
// command typed but not run.
async function terminal(request) {
  const title = typeof request.title === 'string' ? request.title.slice(0, 80) : 'Terminal';
  const text = typeof request.text === 'string' ? request.text.slice(0, 2000) : '';
  const cwd = typeof request.cwd === 'string' && path.isAbsolute(request.cwd) && fs.existsSync(request.cwd) ? request.cwd : HOME;
  for (const command of ['workbench.action.closeAuxiliaryBar', 'workbench.action.closeSidebar', 'workbench.action.closePanel']) {
    await run(command);
  }
  const shell = vscode.window.createTerminal({ name: title, cwd, location: vscode.TerminalLocation.Editor });
  shell.show(false);
  if (text) {
    // Typed once the shell shows its prompt, so the command appears after it, not before.
    await shellReady(shell);
    shell.sendText(text, false);
  }
}

// Resolves when [shell]'s shell integration starts (its prompt is up), or after a few seconds.
function shellReady(shell) {
  if (shell.shellIntegration) return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer);
      listener.dispose();
      resolve();
    };
    const timer = setTimeout(done, SHELL_WAIT_MS);
    const listener = vscode.window.onDidChangeTerminalShellIntegration((event) => {
      if (event.terminal === shell) done();
    });
  });
}

async function retried(action, stopped) {
  let last = null;
  for (let attempt = 0; attempt < ATTEMPTS && !stopped(); attempt++) {
    try {
      await action();
      return true;
    } catch (notReady) {
      last = notReady;
      await delay(RETRY_MS);
    }
  }
  if (last) log(`gave up: ${String(last && last.message ? last.message : last).slice(0, 200)}`);
  return false;
}

function run(command) {
  return vscode.commands.executeCommand(command).then(undefined, () => undefined);
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = { activate, deactivate, claim, pending };
