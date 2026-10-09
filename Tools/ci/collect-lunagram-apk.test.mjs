import test from 'node:test';
import assert from 'node:assert/strict';
import { expectedArtifact, validateOutputMetadata, validateGeneratedFlag, validateApkBadging } from './collect-lunagram-apk.mjs';

function output(edition) {
  const expected = expectedArtifact(edition);
  return { applicationId: 'org.luma.liquid.web', elements: [{ versionName: expected.versionName, versionCode: 71589, outputFile: 'app.apk' }] };
}
function badging(edition) {
  return `package: name='org.luma.liquid.web' versionCode='71589' versionName='${expectedArtifact(edition).versionName}'\nnative-code: 'arm64-v8a'\n`;
}
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
  assert.throws(() => expectedArtifact('full', '82'));
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
