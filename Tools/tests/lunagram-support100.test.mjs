import fs from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';

const read = path => fs.readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8');
const support = read('TMessagesProj/src/main/java/org/telegram/ui/LunagramSupport.java');
const settings = read('TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java');
const address = support.match(/public static final String TON_ADDRESS = "([^"]+)";/)?.[1];
const expected = 'UQDwZfLQqfQakdFG642jd24xjILmNKfXoIbjsoEuGMoLX-7G';
const locales = Object.fromEntries(['values', 'values-ru'].map(name => [name, read(`TMessagesProj/src/main/res/${name}/strings.xml`)]));

function maskJava(text) {
  return text.replace(/\/\/[^\r\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g,
    token => token.replace(/[^\r\n]/g, ' '));
}
function block(text, signature) {
  const start = text.indexOf(signature);
  assert.ok(start >= 0, `missing production signature: ${signature}`);
  const masked = maskJava(text);
  const open = masked.indexOf('{', start);
  let depth = 0;
  for (let end = open; end < masked.length; end++) {
    if (masked[end] === '{') depth++;
    if (masked[end] === '}' && --depth === 0) return text.slice(open + 1, end);
  }
  throw new Error(`unterminated production block: ${signature}`);
}
function labels(xml, key) {
  return [...xml.matchAll(new RegExp(`<string\\s+name="${key}"[^>]*>([\\s\\S]*?)<\\/string>`, 'g'))].map(match => match[1]);
}
function crc16(bytes) {
  let crc = 0;
  for (const byte of bytes) {
    crc ^= byte << 8;
    for (let bit = 0; bit < 8; bit++) crc = ((crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0)) & 0xffff;
  }
  return crc;
}
function validFriendly(value) {
  if (!/^[A-Za-z0-9_-]{48}$/.test(value)) return false;
  const bytes = Buffer.from(value, 'base64url');
  return bytes.length === 36 && bytes.toString('base64url') === value
    && crc16(bytes.subarray(0, 34)) === bytes.readUInt16BE(34);
}

test('the production constant is exactly the requested canonical TON address with valid checksum', () => {
  assert.equal(address, expected);
  assert.equal(validFriendly(address), true);
  const bytes = Buffer.from(address, 'base64url');
  assert.equal(bytes[0], 0x51, 'non-bounceable mainnet tag');
  assert.equal(bytes.readInt8(1), 0, 'base workchain');
  assert.equal(bytes.subarray(2, 34).length, 32);
  assert.equal(bytes.readUInt16BE(34), 0xeec6);
  for (let index = 0; index < address.length; index++) {
    const changed = address.slice(0, index) + (address[index] === 'A' ? 'B' : 'A') + address.slice(index + 1);
    assert.equal(validFriendly(changed), false, `single-character mutation ${index} must fail`);
  }
  for (const bad of [address.slice(1), `${address}=`, ` ${address}`, `${address}\n`, 'not-a-wallet']) {
    assert.equal(validFriendly(bad), false);
  }
});

test('support is directly below the advanced row, uses a distinct ID and is shared by both editions', () => {
  const row = settings.match(/private static final int LUNAGRAM_SUPPORT_ROW = (\d+);/);
  assert.equal(row?.[1], '1003');
  const constantRows = [...settings.matchAll(/private static final int ((?:BHG|LUNAGRAM)_\w+_ROW) = (\d+);/g)];
  assert.equal(new Set(constantRows.map(match => match[2])).size, constantRows.length);
  assert.equal([...settings.matchAll(/case LUNAGRAM_SUPPORT_ROW:/g)].length, 1);
  assert.doesNotMatch(settings, /case 1003\s*:/);
  const fill = block(settings, 'private void fillItems(');
  assert.match(fill, /items\.add\(SettingCell\.Factory\.of\(BHG_ADVANCED_ROW,[^\r\n]+\);\s*items\.add\(SettingCell\.Factory\.of\(LUNAGRAM_SUPPORT_ROW,/);
  const beforeRow = maskJava(fill).slice(0, fill.indexOf('items.add(SettingCell.Factory.of(LUNAGRAM_SUPPORT_ROW'));
  assert.equal([...beforeRow].reduce((depth, char) => depth + (char === '{' ? 1 : char === '}' ? -1 : 0), 0), 0,
    'support must not be nested in a personal/debug/premium branch');
  assert.match(settings, /case LUNAGRAM_SUPPORT_ROW:\s*LunagramSupport\.show\(this\);\s*break;/);
  assert.doesNotMatch(support, /BuildConfig|LumaBuildPolicy|isPremium|DEBUG_VERSION/);
});

test('copy is explicit and uses the exact constant, not formatted or selected display text', () => {
  const copy = block(support, '.setPositiveButton(');
  assert.match(support, /\.setPositiveButton\(getString\(R\.string\.LunagramSupportCopy\), \(dialog, which\) ->/);
  assert.equal([...support.matchAll(/AndroidUtilities\.addToClipboard\(/g)].length, 1);
  assert.match(copy, /if \(AndroidUtilities\.addToClipboard\(TON_ADDRESS\)\)/);
  assert.match(copy, /createCopyBulletin\(getString\(R\.string\.WalletAddressCopied\)\)/);
  assert.match(copy, /else\s*\{\s*Toast\.makeText\(context, getString\(R\.string\.ErrorOccurred\)/);
  assert.doesNotMatch(copy, /getText\(|getSelection|replace\(|substring\(|trim\(/);
  assert.match(support, /address\.setText\(TON_ADDRESS\)/);
  assert.match(support, /\.setNegativeButton\(getString\(R\.string\.Close\), null\)/);
});

test('dialog is local, themed and safe to invoke after its activity has gone away', () => {
  const show = block(support, 'public static void show(');
  assert.match(show, /Context context = fragment\.getParentActivity\(\);\s*if \(context == null\) return;/);
  assert.match(show, /Theme\.ResourcesProvider resources = fragment\.getResourceProvider\(\)/);
  assert.match(show, /fragment\.showDialog\(new AlertDialog\.Builder\(context, resources\)/);
  assert.equal([...show.matchAll(/Theme\.getColor\(Theme\.key_dialogTextBlack, resources\)/g)].length, 2);
  assert.doesNotMatch(show, /Browser|Intent|startActivity|ConnectionsManager|sendRequest|Http|https?:|ton:\/\/|WalletConnect|TonConnect|TONIntroActivity|SharedPreferences|\.edit\(|postDelayed|Timer|Thread/);
  assert.doesNotMatch(show, /setAutoLinkMask|Linkify|setMovementMethod/);
});

test('address is selectable, LTR and multiline without inserted hyphens or ellipsizing', () => {
  for (const expected of ['address.setTextIsSelectable(true)', 'address.setLayoutDirection(View.LAYOUT_DIRECTION_LTR)',
    'address.setTextDirection(View.TEXT_DIRECTION_LTR)', 'address.setTypeface(Typeface.MONOSPACE)',
    'address.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)', 'address.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)']) {
    assert.ok(support.includes(expected), expected);
  }
  assert.match(support, /content\.addView\(address, LayoutHelper\.createLinear\(LayoutHelper\.MATCH_PARENT, LayoutHelper\.WRAP_CONTENT,/);
  assert.doesNotMatch(support, /address\.set(?:SingleLine|MaxLines|Lines|Ellipsize)\(/);
  assert.match(support, /description\.setGravity\(LocaleController\.isRTL \? Gravity\.RIGHT : Gravity\.LEFT\)/);
});

test('API23 text layout calls are guarded so the dialog remains safe on the minimum API21', () => {
  const api23 = block(support, 'if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)');
  assert.match(support, /import android\.os\.Build;/);
  for (const method of ['setBreakStrategy', 'setHyphenationFrequency']) {
    assert.equal([...support.matchAll(new RegExp(`address\\.${method}\\(`, 'g'))].length, 1);
    assert.ok(api23.includes(`address.${method}(`), `${method} must be inside the API23 guard`);
  }
  assert.match(read('TMessagesProj_AppStandalone/build.gradle'), /minSdkVersion 21/);
});

test('both locale packs have exactly one nonempty support label with correct title and TON network explanation', () => {
  const keys = ['LunagramSupportTitle', 'LunagramSupportInfo', 'LunagramSupportDescription', 'LunagramSupportCopy'];
  for (const [locale, xml] of Object.entries(locales)) {
    for (const key of keys) {
      const values = labels(xml, key);
      assert.equal(values.length, 1, `${locale}/${key} must be unique`);
      assert.ok(values[0].trim(), `${locale}/${key} cannot be empty`);
    }
    assert.match(labels(xml, 'LunagramSupportDescription')[0], /TON/);
  }
  assert.equal(labels(locales['values-ru'], 'LunagramSupportTitle')[0], 'Поддержка автора');
  assert.equal(labels(locales.values, 'LunagramSupportTitle')[0], 'Support the author');
  for (const match of support.matchAll(/R\.string\.(\w+)/g)) {
    assert.equal(labels(locales.values, match[1]).length, 1, `missing default R.string.${match[1]}`);
  }
  assert.ok(fs.existsSync(new URL('../../TMessagesProj/src/main/res/drawable/settings_gram_24.xml', import.meta.url)));
});

test('the shared support text is an input to the existing generated localization assets', () => {
  const plugin = read('buildSrc/src/main/kotlin/org/telegram/plugin/TelegramBuildAppPlugin.kt');
  const generator = read('buildSrc/src/main/kotlin/org/telegram/tasks/TelegramStringsTask.kt');
  assert.match(plugin, /telegramModule\.fileTree\("src\/main\/res\/values"\)/);
  assert.match(plugin, /include\("values-\*\/strings\.xml"\)/);
  assert.match(plugin, /TelegramStringsTask::assetsOutputDir/);
  assert.match(generator, /for \(string in strings\["string"\] as NodeList\)/);
  assert.match(generator, /value = normalizeXmlString\(node\.text\(\)\)/);
  assert.doesNotMatch(plugin, /LunagramSupport|LUMA_FRIENDS_EDITION/);
});
