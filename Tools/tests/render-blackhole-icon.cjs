// Render the actual vector paths for a visual mask/centering check, not an Android screenshot.
const fs = require('node:fs');
const path = require('node:path');
const [resources, output, modules] = process.argv.slice(2);
if (!resources || !output || !modules) throw new Error('Usage: node render-blackhole-icon.cjs <res directory> <preview.png> <node_modules directory>');
const sharp = require(path.join(modules, 'sharp'));
function attributes(tag) {
  return Object.fromEntries([...tag.matchAll(/android:([A-Za-z]+)="([^"]*)"/g)].map(m => [m[1], m[2]]));
}
function color(value) {
  if (!/^#[a-fA-F0-9]{6}(?:[a-fA-F0-9]{2})?$/.test(value)) throw new Error(`Unsupported vector color: ${value}`);
  return value.length === 9 ? [ '#' + value.slice(3), parseInt(value.slice(1, 3), 16) / 255 ] : [value, 1];
}
function vector(filename, tint) {
  const xml = fs.readFileSync(path.join(resources, 'drawable', filename), 'utf8');
  const root = attributes(xml.match(/<vector\b[^>]*>/)[0]);
  if (root.viewportWidth !== '108' || root.viewportHeight !== '108') throw new Error('Unexpected icon viewport');
  return [...xml.matchAll(/<\/?(?:group|path)\b[^>]*>/g)].map(([tag]) => {
    if (tag.startsWith('</group')) return '</g>';
    const a = attributes(tag);
    if (tag.startsWith('<group')) {
      const px = Number(a.pivotX || 0), py = Number(a.pivotY || 0);
      return `<g transform="translate(${Number(a.translateX || 0) + px} ${Number(a.translateY || 0) + py}) rotate(${Number(a.rotation || 0)}) scale(${Number(a.scaleX || 1)} ${Number(a.scaleY || 1)}) translate(${-px} ${-py})">`;
    }
    if (!a.pathData || /[<>"&]/.test(a.pathData)) throw new Error('Invalid path data');
    const [fill, fillOpacity] = color(a.fillColor || '#000000');
    const [stroke, strokeOpacity] = color(a.strokeColor || '#00000000');
    return `<path d="${a.pathData}" fill="${tint || fill}" fill-opacity="${fillOpacity * Number(a.fillAlpha || 1)}" stroke="${tint || stroke}" stroke-opacity="${strokeOpacity * Number(a.strokeAlpha || 1)}" stroke-width="${a.strokeWidth || 0}" stroke-linecap="${a.strokeLineCap || 'butt'}" stroke-linejoin="${a.strokeLineJoin || 'miter'}"/>`;
  }).join('');
}
const foreground = vector('bhg_icon_blackhole_foreground.xml');
const monochrome = vector('bhg_icon_blackhole_monochrome.xml', '#1F3341');
const background = attributes(fs.readFileSync(path.join(resources, 'drawable/bhg_icon_blackhole_background.xml'), 'utf8'));
const [start] = color(background.startColor), [middle] = color(background.centerColor), [end] = color(background.endColor);
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="218" viewBox="0 0 512 218">
<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop stop-color="${start}"/><stop offset=".5" stop-color="${middle}"/><stop offset="1" stop-color="${end}"/></linearGradient>
<clipPath id="square"><rect width="144" height="144" rx="34"/></clipPath><clipPath id="round"><circle cx="72" cy="72" r="72"/></clipPath></defs>
<rect width="512" height="218" fill="#10131B"/>
<g transform="translate(20 18)" clip-path="url(#square)"><rect width="144" height="144" fill="url(#bg)"/><g transform="scale(2) translate(-18 -18)">${foreground}</g></g>
<g transform="translate(184 18)" clip-path="url(#round)"><rect width="144" height="144" fill="url(#bg)"/><g transform="scale(2) translate(-18 -18)">${foreground}</g></g>
<g transform="translate(348 18)" clip-path="url(#square)"><rect width="144" height="144" fill="#B7C9D8"/><g transform="scale(2) translate(-18 -18)">${monochrome}</g></g>
<g fill="#D9DEEA" font-family="Segoe UI, sans-serif" font-size="16" text-anchor="middle"><text x="92" y="193">Чёрная дыра</text><text x="256" y="193">Круглая</text><text x="420" y="193">Тематическая</text></g></svg>`;
sharp(Buffer.from(svg)).png().toFile(output).then(() => console.log(output)).catch(error => { console.error(error); process.exitCode = 1; });
