#!/usr/bin/env node
/** Asset bounds and packaging checks; run after tools/make_brand_icons.mjs. */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const sharp = createRequire(import.meta.url)('sharp');
const root = dirname(dirname(fileURLToPath(import.meta.url)));
const res = join(root, 'app/res');
const brand = join(root, 'branding');

const png = readFileSync(join(brand, 'play-store-icon.png'));
assert.equal(png.readUInt32BE(16), 512);
assert.equal(png.readUInt32BE(20), 512);
assert.equal(png[24], 8, '8 bits per channel');
assert.equal(png[25], 6, 'RGBA, 32-bit PNG');
assert.ok(png.length <= 1024 * 1024, 'Play file under 1 MiB');
const chunks = [];
for (let offset = 8; offset < png.length; offset += 12 + png.readUInt32BE(offset)) chunks.push(png.toString('ascii', offset + 4, offset + 8));
assert.ok(chunks.includes('sRGB'), 'Play PNG explicitly tagged sRGB');

for (const [density, size] of Object.entries({ mdpi:48, hdpi:72, xhdpi:96, xxhdpi:144, xxxhdpi:192 })) {
  for (const round of ['', '_round']) {
    const meta = await sharp(join(res, `mipmap-${density}/ic_launcher${round}.png`)).metadata();
    assert.deepEqual([meta.width, meta.height, meta.channels], [size, size, 4]);
  }
}
async function checkCircle(path, dimension, diameterDp, canvasDp) {
  const { data, info } = await sharp(path).resize(dimension, dimension).ensureAlpha().raw().toBuffer({ resolveWithObject:true });
  let visible = 0;
  const radius = diameterDp / canvasDp * dimension / 2;
  for (let y=0; y<info.height; y++) for (let x=0; x<info.width; x++) {
    if (data[(y*info.width+x)*4+3] > 8) {
      visible++;
      assert.ok(Math.hypot(x+.5-dimension/2, y+.5-dimension/2) <= radius, `${path}: pixel outside safe circle`);
    }
  }
  assert.ok(visible > dimension*dimension*.05, 'Brand mark must remain visible');
}
await checkCircle(join(brand, 'adaptive-foreground.svg'), 432, 66, 108);
await checkCircle(join(brand, 'splash-foreground.svg'), 864, 192, 288);

const mono = readFileSync(join(res, 'drawable/ic_stat_pocketagent.xml'), 'utf8');
assert.ok(mono.includes('android:width="24dp"'));
assert.ok(!mono.includes('<gradient'), 'Notification icon must have no colour gradient');
assert.ok([...mono.matchAll(/android:fillColor="([^"]+)"/g)].every(m => m[1] === '#FFFFFFFF'));
const { data: n } = await sharp(join(brand, 'notification.svg')).resize(96,96).ensureAlpha().raw().toBuffer({ resolveWithObject:true });
let whitePixels=0, clearPixels=0;
for (let i=0; i<n.length; i+=4) {
  if (!n[i+3]) { clearPixels++; continue; }
  assert.deepEqual([n[i],n[i+1],n[i+2]], [255,255,255], 'Visible notification pixels are white');
  whitePixels++;
}
assert.ok(whitePixels>100 && clearPixels>100, 'White silhouette on transparent background');
for (const name of ['ic_launcher', 'ic_launcher_round']) {
  const adaptive = readFileSync(join(res, `mipmap-anydpi-v33/${name}.xml`), 'utf8');
  assert.ok(adaptive.includes('@drawable/ic_launcher_monochrome'));
  assert.ok(adaptive.includes('@drawable/ic_launcher_foreground'));
  assert.ok(!adaptive.includes('play-store-icon'));
}
console.log('Brand assets passed: Play PNG, all density sizes, adaptive/splash safe circles, white notification alpha, Android 13 monochrome.');
