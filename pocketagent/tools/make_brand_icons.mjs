#!/usr/bin/env node
/**
 * PocketAgent brand exports. Requires Node.js, sharp and ImageMagick 6/7 (convert).
 * Run from any directory: node tools/make_brand_icons.mjs
 *
 * The approved full-square Play asset is retained verbatim. The SVG/VectorDrawable
 * paths are the scalable adaptation of its code brackets and four-point spark;
 * no old monitor, phone or pocket mark is used. Android applies launcher masks.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
const sharp = createRequire(import.meta.url)('sharp');

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const branding = join(root, 'branding');
const res = join(root, 'app/res');
const bg = '#191A1B';
const left = 'M218.6,133.4 C231.1,145.9 231.1,166.1 218.6,178.6 L147.3,249.9 C144.5,252.7 144.5,257.3 147.3,260.1 L218.6,331.4 C231.1,343.9 231.1,364.1 218.6,376.6 C206.1,389.1 185.9,389.1 173.4,376.6 L80.2,283.4 C64.5,267.7 64.5,242.3 80.2,226.6 L173.4,133.4 C185.9,120.9 206.1,120.9 218.6,133.4 Z';
const spark = 'M252,198 C253.2,194 257.8,194 259,198 C267,227 282,242 312,250.5 C317,252 317,257 312,258.5 C282,267 267,282 259,310 C257.8,315 253.2,315 252,310 C244,282 229,267 200,258.5 C194.5,257 194.5,252 200,250.5 C229,242 244,227 252,198 Z';
const blueStops = [[0, '#B5D8FF'], [.17, '#79AAFF'], [.46, '#4D7FF3'], [.83, '#315BDC'], [1, '#507BF6']];
const pearlStops = [[0, '#FFFFFF'], [.53, '#FAFBFF'], [1, '#D9E3F7']];
const xmlHeader = '<?xml version="1.0" encoding="utf-8"?>\n';
function put(path, value) { mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, value); }
function run(args) { execFileSync('convert', args, { stdio: 'inherit' }); }
function gradient(stops, attribute, x1, y1, x2, y2) {
  return `<aapt:attr name="android:${attribute}"><gradient android:type="linear" android:startX="${x1}" android:startY="${y1}" android:endX="${x2}" android:endY="${y2}">${stops.map(([offset, color]) => `<item android:offset="${offset}" android:color="${color}"/>`).join('')}</gradient></aapt:attr>`;
}
function androidPaths(mono = false) {
  const bracket = `<path android:pathData="${left}"${mono ? ' android:fillColor="#FFFFFFFF"' : ''}>${mono ? '' : gradient(blueStops, 'fillColor', 115, 125, 219, 384)}</path>`;
  return `${bracket}<group android:scaleX="-1" android:translateX="512">${bracket}</group><path android:pathData="${spark}"${mono ? ' android:fillColor="#FFFFFFFF"' : ''}>${mono ? '' : gradient(pearlStops, 'fillColor', 234, 204, 271, 309)}</path>`;
}
function vector(dp, viewport, scale, translate, mono = false) {
  return `${xmlHeader}<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="${dp}dp" android:height="${dp}dp" android:viewportWidth="${viewport}" android:viewportHeight="${viewport}">\n    <group android:scaleX="${scale}" android:scaleY="${scale}" android:translateX="${translate}" android:translateY="${translate}">${androidPaths(mono)}</group>\n</vector>\n`;
}
const defs = `<defs><linearGradient id="blue" gradientUnits="userSpaceOnUse" x1="115" y1="125" x2="219" y2="384">${blueStops.map(([offset,color]) => `<stop offset="${offset}" stop-color="${color}"/>`).join('')}</linearGradient><linearGradient id="pearl" gradientUnits="userSpaceOnUse" x1="234" y1="204" x2="271" y2="309">${pearlStops.map(([offset,color]) => `<stop offset="${offset}" stop-color="${color}"/>`).join('')}</linearGradient></defs>`;
const shapes = `<path d="${left}" fill="url(#blue)"/><g transform="translate(512 0) scale(-1 1)"><path d="${left}" fill="url(#blue)"/></g><path d="${spark}" fill="url(#pearl)"/>`;
const svg = (content, viewBox = '0 0 512 512', size = 512) => `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="${viewBox}">${defs}${content}</svg>\n`;
put(join(branding, 'pocketagent-mark.svg'), svg(shapes));
put(join(branding, 'adaptive-foreground.svg'), svg(`<g transform="translate(12 12) scale(0.1640625)">${shapes}</g>`, '0 0 108 108', 432));
put(join(branding, 'splash-foreground.svg'), svg(`<g transform="translate(21.12 21.12) scale(0.48)">${shapes}</g>`, '0 0 288 288', 864));
put(join(branding, 'notification.svg'), svg(`<g fill="white" transform="translate(-1.473684 -1.473684) scale(0.05263158)"><path d="${left}"/><g transform="translate(512 0) scale(-1 1)"><path d="${left}"/></g><path d="${spark}"/></g>`, '0 0 24 24', 96));

// Adaptive foreground: full 108dp layer. Every visible point is inside the
// centered 66dp safe circle (including the rounded ends of the brackets).
put(join(res, 'drawable/ic_launcher_foreground.xml'), vector(108, 108, 0.1640625, 12));
put(join(res, 'drawable/ic_launcher_monochrome.xml'), vector(108, 108, 0.1640625, 12, true));
put(join(res, 'drawable/pocketagent_mark.xml'), vector(48, 512, 1, 0));
put(join(res, 'drawable/pocketagent_mark_monochrome.xml'), vector(48, 512, 1, 0, true));
// Android 12 splash without an icon plate: 288dp canvas, artwork inside the
// 192dp safe circle. A 0.48 scale keeps this mark's widest point within 184dp.
put(join(res, 'drawable/ic_splash_pocketagent.xml'), vector(288, 288, 0.48, 21.12));
// White-only alpha, 24dp canvas with 2dp optical horizontal margins.
put(join(res, 'drawable/ic_stat_pocketagent.xml'), vector(24, 24, 0.05263158, -1.473684, true));
put(join(res, 'values/ic_launcher_background.xml'), `${xmlHeader}<resources><color name="ic_launcher_background">${bg}</color></resources>\n`);
for (const name of ['pocketagent_icon_bg', 'launcher_gradient']) {
  put(join(res, `drawable/${name}.xml`), `${xmlHeader}<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="@color/ic_launcher_background"/></shape>\n`);
}
for (const api of [26, 33]) for (const round of ['', '_round']) {
  put(join(res, `mipmap-anydpi-v${api}/ic_launcher${round}.xml`), `${xmlHeader}<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/ic_launcher_background"/>\n    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n${api === 33 ? '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n' : ''}</adaptive-icon>\n`);
}

const play = join(branding, 'play-store-icon.png');
const png32 = path => `PNG32:${path}`;
// Keep the approved artwork and its existing sRGB chunk; do not redraw it.
const png = readFileSync(play);
if (png.readUInt32BE(16) !== 512 || png.readUInt32BE(20) !== 512 || png[24] !== 8 || png[25] !== 6 || png.length > 1024 * 1024) throw new Error('Play icon must be 512×512 RGBA PNG, at most 1 MiB');
for (const [density, size] of Object.entries({ mdpi:48, hdpi:72, xhdpi:96, xxhdpi:144, xxxhdpi:192 })) {
  const dir = join(res, `mipmap-${density}`); mkdirSync(dir, { recursive: true });
  const mask = (shape) => ['(', '-size', `${size}x${size}`, 'xc:none', '-fill', 'white', '-draw', shape, ')', '-alpha', 'off', '-compose', 'CopyOpacity', '-composite'];
  run([play, '-resize', `${size}x${size}`, ...mask(`roundrectangle 0,0 ${size-1},${size-1} ${size*.21},${size*.21}`), '-strip', png32(join(dir, 'ic_launcher.png'))]);
  run([play, '-resize', `${size}x${size}`, ...mask(`circle ${size/2},${size/2} ${size/2},0`), '-strip', png32(join(dir, 'ic_launcher_round.png'))]);
}
// Transparent reusable art, never a second tile inside the launcher icon.
for (const destination of [join(res, 'drawable-nodpi/icon_in_app.png'), join(root, 'app/assets/pocketagent-mark.png')]) {
  await sharp(join(branding, 'pocketagent-mark.svg')).resize(512, 512).ensureAlpha().png().toFile(destination);
}
await sharp(join(branding, 'adaptive-foreground.svg')).resize(432, 432).ensureAlpha().png().toFile(join(res, 'drawable-nodpi/icon_foreground_art.png'));
// Match pcmanfm's fit-mode background, including all four image edges.
const wallpaper = `<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="1600" viewBox="0 0 1600 1600">${defs}<defs><radialGradient id="desktopGlow"><stop offset="0" stop-color="#203456"/><stop offset="1" stop-color="#0B1320"/></radialGradient></defs><rect width="1600" height="1600" fill="#0B1320"/><circle cx="800" cy="800" r="800" fill="url(#desktopGlow)"/><g transform="translate(498 415) scale(1.18)">${shapes}</g><text x="800" y="1130" text-anchor="middle" font-family="DejaVu Sans,sans-serif" font-weight="bold" font-size="76" fill="#E6ECF7">PocketAgent</text><text x="800" y="1215" text-anchor="middle" font-family="DejaVu Sans,sans-serif" font-size="34" fill="#93A8C9">Ubuntu 24.04 LTS · Linux</text></svg>\n`;
put(join(branding, 'wallpaper.svg'), wallpaper);
await sharp(Buffer.from(wallpaper)).jpeg({ quality:92, progressive:true }).toFile(join(root, 'app/assets/wallpaper.jpg'));
console.log('PocketAgent icon assets generated. Run node tests/brand-assets-test.mjs to verify.');
