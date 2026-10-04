// Deterministic production-size conversion of the image-tool output; no redesign or cropping.
const fs = require('node:fs');
const path = require('node:path');
const [source, resources, modules] = process.argv.slice(2);
if (!source || !resources || !modules) throw new Error('Usage: node prepare-blackhole-photo.cjs <source.png> <res directory> <node_modules directory>');
const sharp = require(path.join(modules, 'sharp'));
(async () => {
  const metadata = await sharp(source).metadata();
  if (metadata.width !== metadata.height) throw new Error('Launcher artwork must be square');
  const output = path.join(resources, 'drawable-nodpi', 'bhg_icon_blackhole_photo.png');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  await sharp(source).resize(512, 512, { kernel: 'lanczos3' }).flatten({ background: '#000000' }).removeAlpha().png({ compressionLevel: 9 }).toFile(output);
  const result = await sharp(output).metadata();
  if (result.width !== 512 || result.height !== 512 || result.hasAlpha) throw new Error('Unexpected launcher asset format');
  console.log(`PASS: opaque centered artwork at ${output} (${fs.statSync(output).size} bytes)`);
})().catch(error => { console.error(error); process.exitCode = 1; });
