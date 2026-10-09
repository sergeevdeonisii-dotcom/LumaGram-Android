// Render existing Android vector resources for visual review, not a device screenshot.
const fs = require('node:fs');
const path = require('node:path');
const [resources, output, modules] = process.argv.slice(2);
if (!resources || !output || !modules) throw new Error('Usage: node render-original-luma-icon.cjs <res> <preview.png> <node_modules>');
const sharp = require(path.join(modules, 'sharp'));
const attrs = tag => Object.fromEntries([...tag.matchAll(/android:([A-Za-z]+)="([^"]*)"/g)].map(m => [m[1], m[2]]));
function color(value) {
  if (!/^#[a-fA-F0-9]{6}(?:[a-fA-F0-9]{2})?$/.test(value)) throw new Error('Unsupported color');
  return value.length === 9 ? ['#' + value.slice(3), parseInt(value.slice(1, 3), 16) / 255] : [value, 1];
}
let defs = '';
function vector(filename) {
  const xml = fs.readFileSync(path.join(resources, 'drawable', filename), 'utf8');
  const root = attrs(xml.match(/<vector\b[^>]*>/)[0]);
  const ratio = 108 / Number(root.viewportWidth);
  let paths = '';
  for (const [tag] of xml.matchAll(/<path\b[^>]*(?:\/>|>[\s\S]*?<\/path>)/g)) {
    const a = attrs(tag.match(/<path\b[^>]*>/)[0]);
    if (!a.pathData || /[<>"&]/.test(a.pathData)) throw new Error('Invalid vector path');
    let fill, opacity;
    if (tag.includes('<gradient')) {
      const gradient = attrs(tag.match(/<gradient\b[^>]*>/)[0]);
      const id = 'original-gradient';
      const stops = [...tag.matchAll(/<item\b[^>]*>/g)].map(([item]) => {
        const p = attrs(item), [c, alpha] = color(p.color);
        return `<stop offset="${p.offset}" stop-color="${c}" stop-opacity="${alpha}"/>`;
      }).join('');
      defs += `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${gradient.startX}" y1="${gradient.startY}" x2="${gradient.endX}" y2="${gradient.endY}">${stops}</linearGradient>`;
      fill = `url(#${id})`; opacity = 1;
    } else [fill, opacity] = color(a.fillColor || '#000000');
    paths += `<path d="${a.pathData}" fill="${fill}" fill-opacity="${opacity}" fill-rule="${a.fillType === 'evenOdd' ? 'evenodd' : 'nonzero'}"/>`;
  }
  return `<g transform="scale(${ratio})">${paths}</g>`;
}
const background = vector('luma_launcher_background.xml');
const foreground = vector('icon_plane.xml');
const icon = `<g transform="scale(2) translate(-18 -18)">${background}${foreground}</g>`;
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="344" height="210" viewBox="0 0 344 210">
<defs>${defs}<clipPath id="rounded"><rect width="144" height="144" rx="36"/></clipPath><clipPath id="circle"><circle cx="72" cy="72" r="72"/></clipPath></defs>
<rect width="344" height="210" fill="#11131B"/>
<g transform="translate(20 16)" clip-path="url(#rounded)">${icon}</g>
<g transform="translate(180 16)" clip-path="url(#circle)">${icon}</g>
<g fill="#E7ECF6" font-family="Segoe UI,sans-serif" font-size="16" text-anchor="middle"><text x="92" y="187">Lunagram</text><text x="252" y="187">Lunagram</text></g></svg>`;
sharp(Buffer.from(svg)).png().toFile(output).then(() => console.log(output)).catch(error => { console.error(error); process.exitCode = 1; });
