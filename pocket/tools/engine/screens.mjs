// The agent screens, as the app shows them: code-server and the port forwarder started inside the
// engine test's computer with the app's flags (ide/Ide.kt), a phone-sized browser holding the
// session cookie the app sets, and each screen asked for through the companion's request files,
// as the app asks. Each agent must show its own screen (its sign-in, with nobody signed in), and
// the sign-in terminal must show its command typed after the prompt. A picture of each is saved.
//
// Usage: node screens.mjs <out dir>
// Environment: ENGINE (work folder, with home/ bound at /root), GUEST (tools/engine/guest.sh),
//              CHROMIUM (optional: a Chromium to use instead of Playwright's own).
import { chromium } from 'playwright-core';
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';

const OUT = process.argv[2];
const ENGINE = process.env.ENGINE;
const GUEST = process.env.GUEST;
if (!OUT || !ENGINE || !GUEST) {
  console.error('usage: ENGINE=... GUEST=... node screens.mjs <out dir>');
  process.exit(2);
}
const HOME = path.join(ENGINE, 'home');
const G = '/root';
const PROJECT = `${G}/projects/demo`;
const START_MS = 8 * 60 * 1000;
const AGENT_MS = 4 * 60 * 1000;

// Each official agent, and words its signed-out screen shows today (a note, not a condition: the
// makers change their words; the condition is that the agent's own screen shows at all).
const AGENTS = [
  { id: 'anthropic.claude-code', name: 'claude', words: 'How do you want to log in?' },
  { id: 'openai.chatgpt', name: 'codex', words: 'Sign in with ChatGPT' },
  { id: 'google.google-antigravity', name: 'antigravity', words: 'Continue with Google' },
];
const SIGN_IN = { title: 'Sign in: Antigravity', text: 'agy' };

const log = (...parts) => console.log(`[${new Date().toISOString().slice(11, 19)}]`, ...parts);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const failures = [];
const notes = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

function status(url) {
  return new Promise((resolve) => {
    http.get(url, (response) => {
      response.resume();
      resolve(response.statusCode);
    }).on('error', () => resolve(0));
  });
}

const processes = [];
function start(args, name) {
  const logFile = fs.openSync(path.join(OUT, `${name}.log`), 'w');
  const child = spawn(GUEST, args, { stdio: ['ignore', logFile, logFile], detached: true });
  processes.push(child);
  return child;
}
function stopAll() {
  for (const child of processes) {
    try {
      process.kill(-child.pid, 'SIGTERM');
    } catch (gone) {
      // Already stopped.
    }
  }
}
process.on('exit', stopAll);

// A request file, written the way ide/Ide.kt writes them (named by time, renamed into place).
function request(body) {
  const folder = path.join(HOME, '.pocketide/requests');
  fs.mkdirSync(folder, { recursive: true });
  const name = `${Date.now()}-${crypto.randomUUID()}.json`;
  fs.writeFileSync(path.join(folder, `.${name}.tmp`), JSON.stringify(body));
  fs.renameSync(path.join(folder, `.${name}.tmp`), path.join(folder, name));
}

// The text of a frame and every frame inside it.
async function textWithin(frame) {
  let text = '';
  try {
    text = await frame.evaluate(() => (document.body ? document.body.innerText : ''));
  } catch (detached) {
    return '';
  }
  for (const child of frame.childFrames()) text += '\n' + (await textWithin(child));
  return text;
}

// The agent's own webview (VS Code names its extension in the frame's address) and its text.
async function agentText(page, id) {
  let text = '';
  for (const frame of page.frames()) {
    if (decodeURIComponent(frame.url()).toLowerCase().includes(`extensionid=${id}`)) text += await textWithin(frame);
  }
  return text.replace(/\s+/g, ' ').trim();
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const port = await freePort();
  let forwarderPort = await freePort();
  while (forwarderPort === port) forwarderPort = await freePort();
  const token = crypto.randomBytes(32).toString('hex');
  fs.mkdirSync(path.join(HOME, '.pocketide'), { recursive: true });
  fs.writeFileSync(path.join(HOME, '.pocketide/code-server.yaml'), `hashed-password: "${token}"\n`, { mode: 0o600 });

  start(['/opt/code-server/lib/node', '/opt/pocketide/forwarder.js', String(forwarderPort)], 'forwarder');
  start(['/usr/bin/env', 'BROWSER=/opt/pocketide/bin/xdg-open', '/opt/code-server/bin/code-server',
    '--bind-addr', `127.0.0.1:${port}`, '--auth', 'password', '--config', `${G}/.pocketide/code-server.yaml`,
    '--disable-telemetry', '--disable-update-check', '--disable-workspace-trust', '--disable-getting-started-override',
    '--proxy-domain', `{{port}}.localhost:${forwarderPort}`, '--reconnection-grace-time', '300',
    '--user-data-dir', `${G}/.local/share/code-server`, '--extensions-dir', `${G}/.local/share/code-server/extensions`,
    `${G}/projects`], 'code-server');

  const deadline = Date.now() + START_MS;
  while ((await status(`http://127.0.0.1:${port}/healthz`)) !== 200) {
    if (Date.now() > deadline) throw new Error('code-server did not answer /healthz within 8 minutes');
    await sleep(1000);
  }
  log('code-server is up');
  // Without the app's cookie, code-server asks for its password: another app on the phone gets nowhere.
  const bare = await status(`http://127.0.0.1:${port}/`);
  if (bare !== 302) failures.push(`code-server answered ${bare} without the cookie, not 302 to its password page`);

  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM || undefined });
  const context = await browser.newContext({
    viewport: { width: 393, height: 780 }, deviceScaleFactor: 2.75, isMobile: true, hasTouch: true,
    colorScheme: 'light', locale: 'en-US',
    userAgent: 'Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.0.0 Mobile Safari/537.36',
  });
  await context.addCookies([{ name: 'code-server-session', value: token, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);
  const page = await context.newPage();
  page.on('pageerror', (error) => notes.push(`page error: ${String(error).slice(0, 200)}`));
  await page.goto(`http://127.0.0.1:${port}/?folder=${encodeURIComponent(PROJECT)}`, { waitUntil: 'domcontentloaded', timeout: START_MS });
  await page.waitForSelector('.monaco-workbench', { timeout: START_MS });
  log('the workbench is up');

  for (const agent of AGENTS) {
    request({ do: 'show', agent: agent.id });
    const until = Date.now() + AGENT_MS;
    let text = '';
    while (Date.now() < until) {
      text = await agentText(page, agent.id);
      if (text.includes(agent.words)) break;
      await sleep(3000);
    }
    await page.screenshot({ path: path.join(OUT, `${agent.name}.png`) });
    if (text.length < 20) failures.push(`${agent.id}: its screen stayed empty for ${AGENT_MS / 60000} minutes`);
    else if (!text.includes(agent.words)) notes.push(`${agent.id}: shows its screen, without the words "${agent.words}"`);
    log(`${agent.name}: ${text.slice(0, 120)}`);
  }

  request({ do: 'terminal', title: SIGN_IN.title, text: SIGN_IN.text, cwd: PROJECT });
  const until = Date.now() + AGENT_MS;
  let rows = '';
  while (Date.now() < until) {
    rows = await page.evaluate(() => [...document.querySelectorAll('.xterm-rows')].map((r) => r.innerText).join('\n'));
    if (new RegExp(`[#$] ${SIGN_IN.text}\\s*$`, 'm').test(rows)) break;
    await sleep(2000);
  }
  await page.screenshot({ path: path.join(OUT, 'sign-in-terminal.png') });
  if (!new RegExp(`[#$] ${SIGN_IN.text}\\s*$`, 'm').test(rows)) {
    failures.push(`the sign-in terminal does not show "${SIGN_IN.text}" typed after its prompt: ${JSON.stringify(rows.slice(-200))}`);
  }
  await browser.close();

  const companion = path.join(HOME, '.pocketide/companion.log');
  if (fs.existsSync(companion)) fs.copyFileSync(companion, path.join(OUT, 'companion.log'));
  notes.forEach((note) => log(`note: ${note}`));
  if (failures.length > 0) {
    failures.forEach((failure) => console.error(`FAIL: ${failure}`));
    process.exitCode = 1;
  } else {
    log('every agent screen and the sign-in terminal showed as the app shows them');
  }
}

main().catch((error) => {
  console.error(`FAIL: ${error.stack || error}`);
  process.exitCode = 1;
}).finally(stopAll);
