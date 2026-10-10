import test from 'node:test';
import assert from 'node:assert/strict';
import { expectedArtifact, expectedEditions, validateArtifactMetadata, validateOutputMetadata, validateGeneratedFlag, validateApkBadging } from './collect-lunagram-apk.mjs';

function output(edition) {
  const expected = expectedArtifact(edition);
  return { applicationId: 'org.luma.liquid.web', elements: [{ versionName: expected.versionName, versionCode: 71589, outputFile: 'app.apk' }] };
}
function badging(edition) {
  return `package: name='org.luma.liquid.web' versionCode='71589' versionName='${expectedArtifact(edition).versionName}'\nnative-code: 'arm64-v8a'\n`;
}

test('1.0.0 keeps one public version name, a monotonic code and distinct verified editions', () => {
  for (const edition of ['full', 'friends']) {
    const expected = expectedArtifact(edition, '100');
    assert.equal(expected.versionName, '1.0.0');
    assert.equal(expected.versionCode, 71629);
    assert.equal(expected.friendsEdition, edition === 'friends');
    assert.ok(expected.fileName.includes(`-${edition}-`));
    validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.0.0', versionCode: 71629, outputFile: 'app.apk' }] }, edition, '100');
    validateApkBadging("package: name='org.luma.liquid.web' versionCode='71629' versionName='1.0.0'\nnative-code: 'arm64-v8a'\n", edition, '100');
    validateGeneratedFlag(`public static final boolean LUMA_FRIENDS_EDITION = ${edition === 'friends'};`, edition);
    assert.throws(() => validateGeneratedFlag(`public static final boolean LUMA_FRIENDS_EDITION = ${edition !== 'friends'};`, edition));
    assert.throws(() => validateApkBadging("package: name='org.luma.liquid.web' versionCode='71619' versionName='1.0.0'\nnative-code: 'arm64-v8a'\n", edition, '100'));
  }
});
for (const edition of ['full', 'friends']) {
  test(`${edition}: output metadata, library flag and APK manifest must agree`, () => {
    assert.equal(validateOutputMetadata(output(edition), edition), 'app.apk');
    validateGeneratedFlag(`package org.telegram.messenger;\n  public static final boolean LUMA_FRIENDS_EDITION = ${edition === 'friends'};\n`, edition);
    validateApkBadging(badging(edition), edition);
  });
  test(`${edition}: opposite flag or manifest rejected`, () => {
    assert.throws(() => validateGeneratedFlag(`public static final boolean LUMA_FRIENDS_EDITION = ${edition !== 'friends'};`, edition));
    assert.throws(() => validateApkBadging(badging(edition === 'full' ? 'friends' : 'full'), edition));
  });
}

test('1.0.1 rejects old APKs and retains distinct public/personal flags', () => {
  for (const edition of ['full', 'friends']) {
    const expected = expectedArtifact(edition, '101');
    assert.equal(expected.versionName, '1.0.1');
    assert.equal(expected.versionCode, 71639);
    assert.equal(expected.friendsEdition, edition === 'friends');
    validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.0.1', versionCode: 71639, outputFile: 'app.apk' }] }, edition, '101');
    validateApkBadging("package: name='org.luma.liquid.web' versionCode='71639' versionName='1.0.1'\nnative-code: 'arm64-v8a'\n", edition, '101');
    assert.throws(() => validateApkBadging("package: name='org.luma.liquid.web' versionCode='71629' versionName='1.0.0'\nnative-code: 'arm64-v8a'\n", edition, '101'));
  }
});

const commit102 = 'c'.repeat(40);
function metadata102(editions = ['full', 'public']) {
  return { schemaVersion: 2, release: '102', sourceCommit: commit102, packageName: 'org.luma.liquid.web', architecture: 'arm64-v8a',
    artifacts: editions.map(edition => ({ ...expectedArtifact(edition, '102'), sha256: 'a'.repeat(64) })) };
}
function flags102(edition) {
  return `public static final boolean LUMA_FRIENDS_EDITION = ${edition === 'friends'};\npublic static final boolean LUMA_PUBLIC_EDITION = ${edition === 'public'};\n`;
}
test('1.0.2 genuinely separates personal, general release and restricted Friends', () => {
  assert.deepEqual(expectedEditions('102'), ['full', 'public']);
  assert.deepEqual(expectedEditions('102', true), ['full', 'public', 'friends']);
  for (const edition of ['full', 'public', 'friends']) {
    const expected = expectedArtifact(edition, '102');
    assert.equal(expected.versionName, '1.0.2');
    assert.equal(expected.versionCode, 71649);
    assert.equal(expected.friendsEdition, edition === 'friends');
    assert.equal(expected.publicEdition, edition === 'public');
    assert.equal(expected.fileName, `Lunagram-1.0.2-${edition}-temporary.apk`);
    validateGeneratedFlag(flags102(edition), edition, '102');
    validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.0.2', versionCode: 71649, outputFile: 'app.apk' }] }, edition, '102');
    validateApkBadging("package: name='org.luma.liquid.web' versionCode='71649' versionName='1.0.2'\nnative-code: 'arm64-v8a'\n", edition, '102');
    for (const other of ['full', 'public', 'friends'].filter(value => value !== edition)) {
      assert.throws(() => validateGeneratedFlag(flags102(other), edition, '102'));
    }
  }
  assert.throws(() => expectedArtifact('public', '101'));
  assert.throws(() => expectedEditions('101', true));
  assert.throws(() => expectedEditions('102', 'false'));
  assert.throws(() => validateApkBadging("package: name='org.luma.liquid.web' versionCode='71639' versionName='1.0.1'\nnative-code: 'arm64-v8a'\n", 'public', '102'));
});
test('1.0.2 requires both edition flags; contradictory, missing and duplicate flags fail closed', () => {
  for (const source of [
    'public static final boolean LUMA_FRIENDS_EDITION = false;',
    'public static final boolean LUMA_PUBLIC_EDITION = true;',
    flags102('public') + 'public static final boolean LUMA_PUBLIC_EDITION = true;',
    flags102('public').replace('LUMA_FRIENDS_EDITION = false', 'LUMA_FRIENDS_EDITION = true'),
    flags102('public').replace('LUMA_PUBLIC_EDITION = true', 'LUMA_PUBLIC_EDITION = false'),
  ]) assert.throws(() => validateGeneratedFlag(source, 'public', '102'));
});
test('1.0.2 validates exact provenance, both flags and all selected editions', () => {
  validateArtifactMetadata(metadata102(), '102', commit102, expectedEditions('102'));
  validateArtifactMetadata(metadata102(['full']), '102', commit102, ['full']);
  validateArtifactMetadata(metadata102(['full', 'public', 'friends']), '102', commit102, expectedEditions('102', true));
  for (const mutate of [
    m => m.schemaVersion = 1, m => m.release = '101', m => delete m.release,
    m => m.sourceCommit = 'd'.repeat(40), m => m.packageName = 'org.telegram.messenger', m => m.architecture = 'x86',
    m => m.artifacts.pop(), m => m.artifacts.push({ ...m.artifacts[1] }),
    m => m.artifacts[1] = { ...m.artifacts[0] },
    m => m.artifacts[1] = { ...expectedArtifact('friends', '102'), sha256: 'b'.repeat(64) },
    m => m.artifacts[1].friendsEdition = true, m => m.artifacts[1].publicEdition = false,
    m => delete m.artifacts[1].publicEdition, m => m.artifacts[1].publicEdition = 'true',
    m => m.artifacts[1].fileName = '../friends.apk', m => m.artifacts[1].versionCode = 71639,
    m => m.artifacts[1].sha256 = 'fixture', m => m.artifacts[1].sha256 = 'A'.repeat(64),
  ]) {
    const metadata = metadata102();
    mutate(metadata);
    assert.throws(() => validateArtifactMetadata(metadata, '102', commit102, expectedEditions('102')));
  }
  assert.throws(() => validateArtifactMetadata(metadata102(), '102', 'main', expectedEditions('102')));
  assert.throws(() => validateArtifactMetadata(metadata102(['full', 'public', 'friends']), '102', commit102, expectedEditions('102')));
  assert.throws(() => validateArtifactMetadata(metadata102(), '102', commit102, expectedEditions('102', true)));
});
test('wrong package, version, ABI, multiple outputs and path traversal rejected', () => {
  const metadata = output('full');
  metadata.applicationId = 'org.telegram.messenger';
  assert.throws(() => validateOutputMetadata(metadata, 'full'));
  metadata.applicationId = 'org.luma.liquid.web';
  metadata.elements[0].versionCode = 71579;
  assert.throws(() => validateOutputMetadata(metadata, 'full'));
  metadata.elements[0].versionCode = 71589;
  metadata.elements[0].outputFile = '../app.apk';
  assert.throws(() => validateOutputMetadata(metadata, 'full'));
  metadata.elements[0].outputFile = 'app.apk';
  metadata.elements.push({ ...metadata.elements[0] });
  assert.throws(() => validateOutputMetadata(metadata, 'full'));
  assert.throws(() => validateApkBadging(badging('full').replace("'arm64-v8a'", "'arm64-v8a' 'x86'"), 'full'));
});
test('missing or duplicate edition declarations rejected without printing BuildConfig', () => {
  assert.throws(() => validateGeneratedFlag('public static final String APP_HASH = "private fixture";', 'full'));
  assert.throws(() => validateGeneratedFlag('public static final boolean LUMA_FRIENDS_EDITION = false;\npublic static final boolean LUMA_FRIENDS_EDITION = false;', 'full'));
});
test('personal .80 has its own version and rejects .79 or Friends metadata', () => {
  const expected = expectedArtifact('full', '80');
  assert.equal(expected.versionCode, 71599);
  assert.equal(expected.versionName, '12.10.6-lunagram.80');
  const metadata = output('full');
  assert.throws(() => validateOutputMetadata(metadata, 'full', '80'));
  metadata.elements[0].versionCode = expected.versionCode;
  metadata.elements[0].versionName = expected.versionName;
  assert.equal(validateOutputMetadata(metadata, 'full', '80'), 'app.apk');
  const manifest = `package: name='org.luma.liquid.web' versionCode='71599' versionName='12.10.6-lunagram.80'\nnative-code: 'arm64-v8a'\n`;
  validateApkBadging(manifest, 'full', '80');
  assert.throws(() => validateApkBadging(manifest, 'friends', '80'));
  assert.throws(() => validateApkBadging(manifest, 'full'));
  assert.throws(() => expectedArtifact('full', '999'));
  assert.throws(() => expectedArtifact('full', 'toString'));
});

test('personal .81 rejects previous release and Friends APKs', () => {
  const expected = expectedArtifact('full', '81');
  assert.equal(expected.versionCode, 71609);
  assert.equal(expected.versionName, '12.10.6-lunagram.81');
  const metadata = output('full');
  assert.throws(() => validateOutputMetadata(metadata, 'full', '81'));
  metadata.elements[0].versionCode = 71609;
  metadata.elements[0].versionName = expected.versionName;
  assert.equal(validateOutputMetadata(metadata, 'full', '81'), 'app.apk');
  const manifest = "package: name='org.luma.liquid.web' versionCode='71609' versionName='12.10.6-lunagram.81'\nnative-code: 'arm64-v8a'\n";
  validateApkBadging(manifest, 'full', '81');
  assert.throws(() => validateApkBadging(manifest, 'full', '80'));
  assert.throws(() => validateApkBadging(manifest, 'friends', '81'));
});

test('personal .82 rejects previous release and Friends APKs', () => {
  const expected = expectedArtifact('full', '82');
  assert.equal(expected.versionCode, 71619);
  assert.equal(expected.versionName, '12.10.6-lunagram.82');
  const metadata = output('full');
  assert.throws(() => validateOutputMetadata(metadata, 'full', '82'));
  metadata.elements[0].versionCode = expected.versionCode;
  metadata.elements[0].versionName = expected.versionName;
  assert.equal(validateOutputMetadata(metadata, 'full', '82'), 'app.apk');
  const manifest = "package: name='org.luma.liquid.web' versionCode='71619' versionName='12.10.6-lunagram.82'\nnative-code: 'arm64-v8a'\n";
  validateApkBadging(manifest, 'full', '82');
  assert.throws(() => validateApkBadging(manifest, 'full', '81'));
  assert.throws(() => validateApkBadging(manifest, 'friends', '82'));
});
