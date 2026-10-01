// Files from the phone, as PocketIDE's app sends them: VS Code's own file dialog (an agent's "add
// files", File: Open File) in an agent's VS Code at a phone's size, with the app's page script
// (pocket/app/src/main/assets/workspace/pagescript.js). CI runs it after the Cloud Shell set-up, with
// that set-up's home folder (CLOUD_SHELL_HOME; else this one's):
//   CLOUD_SHELL_HOME=<home> node phone-files-check.js <folder for the screenshots>
// The tap that opens the dialog must open the phone's picker by itself, and a file picked must
// arrive in that agent's ~/projects/<agent>/uploads through Cloud Shell's file drop and be taken by
// the dialog. A folder dialog must stay as it is, with no picker. PocketIDE's door, which passes
// /__pocketide/drop/ on a page's own address to the drop, is played here by Playwright. Any miss is
// an error, and the screenshots show it.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { chromium } = require('playwright');

const out = process.argv[2] || 'phone-screens';
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/workspace/pagescript.js'), 'utf8');
const VS_CODE = 'http://127.0.0.1:8081/'; // Codex's, whose "add files" uses this dialog
const DROP = 'http://127.0.0.1:6081/';
const PHONE = { viewport: { width: 360, height: 700 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true };

const fail = (message) => {
  throw new Error(message);
};

(async () => {
  fs.mkdirSync(out, { recursive: true });
  const name = `from-phone-${process.pid}.txt`;
  const sample = path.join(os.tmpdir(), name);
  fs.writeFileSync(sample, 'Sent from the phone\n');
  const browser = await chromium.launch();
  const page = await browser.newPage(PHONE);
  await page.addInitScript(script);
  await page.route('**/__pocketide/drop/**', async (route) => {
    const url = route.request().url().replace(/^http:\/\/127\.0\.0\.1:\d+\/__pocketide\/drop\//, DROP);
    await route.fulfill({ response: await route.fetch({ url }) });
  });
  await page.goto(VS_CODE);
  await page.waitForSelector('.monaco-workbench', { timeout: 90000 });
  await page.waitForTimeout(5000);

  const palette = async (command) => {
    await page.keyboard.press('Control+Shift+P');
    await page.waitForSelector('.quick-input-widget input', { timeout: 15000 });
    await page.waitForTimeout(800);
    await page.keyboard.type(command);
    await page.waitForTimeout(800);
  };
  // A folder dialog stays Cloud Shell's: no picker.
  let pickerOpened = false;
  page.on('filechooser', () => { pickerOpened = true; });
  await palette('File: Open Folder');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(3000);
  if (pickerOpened) fail('A folder dialog opened the phone\'s picker.');
  await page.screenshot({ path: path.join(out, 'files-0-folder-dialog.png') });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
  // The tap (here a key) that opens the file dialog opens the phone's picker by itself.
  await palette('File: Open File');
  const picker = page.waitForEvent('filechooser', { timeout: 15000 });
  await page.keyboard.press('Enter');
  const chooser = await picker.catch(() => fail('The file dialog did not open the phone\'s picker by itself.'));
  await page.screenshot({ path: path.join(out, 'files-1-dialog.png') });

  await chooser.setFiles(sample);
  await page.waitForFunction((file) => document.title.includes(file.replace(/\.txt$/, '')), name, { timeout: 20000 })
    .catch(() => fail('The dialog did not take the file sent from the phone.'));
  const arrived = path.join(process.env.CLOUD_SHELL_HOME || os.homedir(), 'projects', 'codex', 'uploads', name);
  if (!fs.existsSync(arrived) || fs.readFileSync(arrived, 'utf8') !== 'Sent from the phone\n') fail(`${arrived} did not arrive whole.`);
  console.log(`From phone: ${name} arrived in ~/projects/codex/uploads and the dialog took it.`);
  await page.screenshot({ path: path.join(out, 'files-2-taken.png') });

  await browser.close();
})().catch((error) => {
  console.log(`::error::${error.message.split('\n')[0]}`);
  process.exit(1);
});
