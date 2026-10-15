import test from 'node:test';
import assert from 'node:assert/strict';
import { expectedArtifact, expectedEditions, validateArtifactMetadata, validateOutputMetadata, validateGeneratedFlag, validateApkBadging } from './collect-lunagram-apk.mjs';

function output(edition) {
  const expected = expectedArtifact(edition, '79');
  return { applicationId: 'org.luma.liquid.web', elements: [{ versionName: expected.versionName, versionCode: 71589, outputFile: 'app.apk' }] };
}
function badging(edition) {
  return `package: name='org.luma.liquid.web' versionCode='71589' versionName='${expectedArtifact(edition, '79').versionName}'\nnative-code: 'arm64-v8a'\n`;
}

test('3.0.1 is public-only, upgrades 3.0.0 and cannot relabel a historical APK or provenance', () => {
  const commit = 'a'.repeat(40);
  function releaseMetadata(release) {
    return { schemaVersion: 2, release, sourceCommit: commit, packageName: 'org.luma.liquid.web', architecture: 'arm64-v8a',
      temporarySigning: true, productionSigningRequired: true,
      verification: { compiledLibraryEdition: 'public', packagedEdition: 'public', debuggable: false },
      artifacts: [{ ...expectedArtifact('public', release), sha256: 'b'.repeat(64) }] };
  }
  const expected = expectedArtifact('public', '301');
  assert.deepEqual(expected, { edition: 'public', fileName: 'Lunagram-3.0.1-public-temporary.apk', versionName: '3.0.1',
    versionCode: 72019, friendsEdition: false, publicEdition: true });
  assert.deepEqual(expectedEditions('301'), ['public']);
  assert.throws(() => expectedEditions('301', true));
  for (const edition of ['full', 'friends', 'personal']) assert.throws(() => expectedArtifact(edition, '301'));
  validateGeneratedFlag(flags102('public'), 'public', '301');
  assert.equal(validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '3.0.1', versionCode: 72019, outputFile: 'app.apk' }] }, 'public', '301'), 'app.apk');
  validateApkBadging("package: name='org.luma.liquid.web' versionCode='72019' versionName='3.0.1'\nnative-code: 'arm64-v8a'\n", 'public', '301');
  validateArtifactMetadata(releaseMetadata('301'), '301', commit, ['public']);
  for (const older of ['206', '210', '300']) {
    const prior = expectedArtifact('public', older);
    assert.ok(expected.versionCode > prior.versionCode);
    validateArtifactMetadata(releaseMetadata(older), older, commit, ['public']);
    assert.throws(() => validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: prior.versionName, versionCode: prior.versionCode, outputFile: 'app.apk' }] }, 'public', '301'));
    assert.throws(() => validateApkBadging(`package: name='org.luma.liquid.web' versionCode='${prior.versionCode}' versionName='${prior.versionName}'\nnative-code: 'arm64-v8a'\n`, 'public', '301'));
    assert.throws(() => validateArtifactMetadata(releaseMetadata(older), '301', commit, ['public']));
    assert.throws(() => validateArtifactMetadata(releaseMetadata('301'), older, commit, ['public']));
  }
});

test('1.0.0 keeps one public version name, a monotonic code and distinct verified editions', () => {
  for (const edition of ['full', 'friends']) {
    const expected = expectedArtifact(edition, '100');
    assert.equal(expected.versionName, '1.0.0');
    assert.equal(expected.versionCode, 71629);
    assert.equal(expected.friendsEdition, edition === 'friends');
    assert.ok(expected.fileName.includes(`-${edition}-`));
    validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.0.0', versionCode: 71629, outputFile: 'app.apk' }] }, edition, '100');
    validateApkBadging("package: name='org.luma.liquid.web' versionCode='71629' versionName='1.0.0'\nnative-code: 'arm64-v8a'\n", edition, '100');
    validateGeneratedFlag(`public static final boolean LUMA_FRIENDS_EDITION = ${edition === 'friends'};`, edition, '100');
    assert.throws(() => validateGeneratedFlag(`public static final boolean LUMA_FRIENDS_EDITION = ${edition !== 'friends'};`, edition, '100'));
    assert.throws(() => validateApkBadging("package: name='org.luma.liquid.web' versionCode='71619' versionName='1.0.0'\nnative-code: 'arm64-v8a'\n", edition, '100'));
  }
});
for (const edition of ['full', 'friends']) {
  test(`${edition}: output metadata, library flag and APK manifest must agree`, () => {
    assert.equal(validateOutputMetadata(output(edition), edition, '79'), 'app.apk');
    validateGeneratedFlag(`package org.telegram.messenger;\n  public static final boolean LUMA_FRIENDS_EDITION = ${edition === 'friends'};\n`, edition, '79');
    validateApkBadging(badging(edition), edition, '79');
  });
  test(`${edition}: opposite flag or manifest rejected`, () => {
    assert.throws(() => validateGeneratedFlag(`public static final boolean LUMA_FRIENDS_EDITION = ${edition !== 'friends'};`, edition, '79'));
    assert.throws(() => validateApkBadging(badging(edition === 'full' ? 'friends' : 'full'), edition, '79'));
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
function metadata103(editions = ['full', 'public']) {
  return { schemaVersion: 2, release: '103', sourceCommit: commit102, packageName: 'org.luma.liquid.web', architecture: 'arm64-v8a',
    artifacts: editions.map(edition => ({ ...expectedArtifact(edition, '103'), sha256: 'b'.repeat(64) })) };
}
test('1.0.3 hotfix keeps all three edition contracts and rejects 1.0.2 outputs', () => {
  assert.deepEqual(expectedEditions('103'), ['full', 'public']);
  assert.deepEqual(expectedEditions('103', true), ['full', 'public', 'friends']);
  for (const edition of ['full', 'public', 'friends']) {
    const expected = expectedArtifact(edition, '103');
    assert.equal(expected.versionName, '1.0.3');
    assert.equal(expected.versionCode, 71659);
    assert.equal(expected.fileName, `Lunagram-1.0.3-${edition}-temporary.apk`);
    assert.equal(expected.friendsEdition, edition === 'friends');
    assert.equal(expected.publicEdition, edition === 'public');
    validateGeneratedFlag(flags102(edition), edition, '103');
    validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.0.3', versionCode: 71659, outputFile: 'app.apk' }] }, edition, '103');
    validateApkBadging("package: name='org.luma.liquid.web' versionCode='71659' versionName='1.0.3'\nnative-code: 'arm64-v8a'\n", edition, '103');
    assert.throws(() => validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.0.2', versionCode: 71649, outputFile: 'app.apk' }] }, edition, '103'));
    assert.throws(() => validateApkBadging("package: name='org.luma.liquid.web' versionCode='71649' versionName='1.0.2'\nnative-code: 'arm64-v8a'\n", edition, '103'));
    for (const other of ['full', 'public', 'friends'].filter(value => value !== edition)) {
      assert.throws(() => validateGeneratedFlag(flags102(other), edition, '103'));
    }
  }
  assert.throws(() => validateGeneratedFlag('public static final boolean LUMA_FRIENDS_EDITION = false;', 'public', '103'));
  assert.throws(() => validateGeneratedFlag(flags102('public') + 'public static final boolean LUMA_PUBLIC_EDITION = true;', 'public', '103'));
});
test('1.0.3 has exact schema-2 release provenance; historical metadata is never relabelled', () => {
  validateArtifactMetadata(metadata103(), '103', commit102, expectedEditions('103'));
  validateArtifactMetadata(metadata103(['full']), '103', commit102, ['full']);
  validateArtifactMetadata(metadata103(['full', 'public', 'friends']), '103', commit102, expectedEditions('103', true));
  assert.throws(() => validateArtifactMetadata(metadata102(), '103', commit102, expectedEditions('103')));
  assert.throws(() => validateArtifactMetadata(metadata103(), '102', commit102, expectedEditions('102')));
  for (const mutate of [
    m => m.schemaVersion = 1, m => m.release = '102', m => m.release = 103, m => delete m.release,
    m => m.artifacts[1] = { ...expectedArtifact('friends', '103'), sha256: 'b'.repeat(64) },
    m => m.artifacts[1].friendsEdition = true, m => m.artifacts[1].publicEdition = false,
    m => m.artifacts[1].publicEdition = 'true', m => delete m.artifacts[1].publicEdition,
    m => m.artifacts[1].versionCode = 71649, m => m.artifacts[1].fileName = 'Lunagram-1.0.2-public-temporary.apk',
  ]) {
    const metadata = metadata103();
    mutate(metadata);
    assert.throws(() => validateArtifactMetadata(metadata, '103', commit102, expectedEditions('103')));
  }
  assert.throws(() => validateArtifactMetadata(metadata103(['full', 'public', 'friends']), '103', commit102, expectedEditions('103')));
  assert.throws(() => validateArtifactMetadata(metadata103(), '103', commit102, expectedEditions('103', true)));
  assert.throws(() => validateArtifactMetadata({ schemaVersion: 1, sourceCommit: commit102, packageName: 'org.luma.liquid.web', architecture: 'arm64-v8a', artifacts: [] }, '999', commit102, []));
});
function metadata104(editions = ['full', 'public']) {
  return { schemaVersion: 2, release: '104', sourceCommit: commit102, packageName: 'org.luma.liquid.web', architecture: 'arm64-v8a',
    artifacts: editions.map(edition => ({ ...expectedArtifact(edition, '104'), sha256: 'd'.repeat(64) })) };
}
test('historical 1.0.4 keeps personal/public/Friends contracts with its immutable APK identity', () => {
  assert.deepEqual(expectedEditions('104'), ['full', 'public']);
  assert.deepEqual(expectedEditions('104', true), ['full', 'public', 'friends']);
  for (const edition of ['full', 'public', 'friends']) {
    const expected = expectedArtifact(edition, '104');
    assert.equal(expected.versionName, '1.0.4');
    assert.equal(expected.versionCode, 71669);
    assert.equal(expected.fileName, `Lunagram-1.0.4-${edition}-temporary.apk`);
    assert.equal(expected.friendsEdition, edition === 'friends');
    assert.equal(expected.publicEdition, edition === 'public');
    validateGeneratedFlag(flags102(edition), edition, '104');
    validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.0.4', versionCode: 71669, outputFile: 'app.apk' }] }, edition, '104');
    validateApkBadging("package: name='org.luma.liquid.web' versionCode='71669' versionName='1.0.4'\nnative-code: 'arm64-v8a'\n", edition, '104');
    assert.throws(() => validateApkBadging("package: name='org.luma.liquid.web' versionCode='71659' versionName='1.0.3'\nnative-code: 'arm64-v8a'\n", edition, '104'));
  }
  assert.throws(() => validateGeneratedFlag(flags102('friends'), 'public', '104'));
});
test('1.0.4 accepts only its exact schema-2 provenance and preserves explicit 1.0.3 validation', () => {
  validateArtifactMetadata(metadata104(), '104', commit102, expectedEditions('104'));
  validateArtifactMetadata(metadata104(['full', 'public', 'friends']), '104', commit102, expectedEditions('104', true));
  validateArtifactMetadata(metadata103(), '103', commit102, expectedEditions('103'));
  assert.throws(() => validateArtifactMetadata(metadata103(), '104', commit102, expectedEditions('104')));
  assert.throws(() => validateArtifactMetadata(metadata104(), '103', commit102, expectedEditions('103')));
  for (const mutate of [m => m.release = '103', m => m.release = 104, m => delete m.release,
    m => m.artifacts[1].versionCode = 71659, m => m.artifacts[1].versionName = '1.0.3',
    m => m.artifacts[1].fileName = 'Lunagram-1.0.3-public-temporary.apk',
    m => m.artifacts[1].friendsEdition = true, m => m.artifacts[1].publicEdition = false,
    m => delete m.artifacts[1].publicEdition, m => m.artifacts[1].publicEdition = 'true']) {
    const metadata = metadata104(); mutate(metadata);
    assert.throws(() => validateArtifactMetadata(metadata, '104', commit102, expectedEditions('104')));
  }
});
function metadata105(editions = ['full', 'public']) {
  return { schemaVersion: 2, release: '105', sourceCommit: commit102, packageName: 'org.luma.liquid.web', architecture: 'arm64-v8a',
    artifacts: editions.map(edition => ({ ...expectedArtifact(edition, '105'), sha256: 'e'.repeat(64) })) };
}
test('historical 1.0.5 keeps its immutable personal/public pair and disallows Friends', () => {
  assert.deepEqual(expectedEditions('105'), ['full', 'public']);
  assert.deepEqual(expectedEditions('105'), ['full', 'public']);
  assert.throws(() => expectedEditions('105', true));
  assert.throws(() => expectedArtifact('friends', '105'));
  assert.throws(() => validateGeneratedFlag(flags102('friends'), 'friends', '105'));
  for (const edition of ['full', 'public']) {
    const expected = expectedArtifact(edition, '105');
    assert.equal(expected.versionName, '1.0.5');
    assert.equal(expected.versionCode, 71679);
    assert.equal(expected.fileName, `Lunagram-1.0.5-${edition}-temporary.apk`);
    assert.equal(expected.friendsEdition, false);
    assert.equal(expected.publicEdition, edition === 'public');
    validateGeneratedFlag(flags102(edition), edition, '105');
    validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.0.5', versionCode: 71679, outputFile: 'app.apk' }] }, edition, '105');
    validateApkBadging("package: name='org.luma.liquid.web' versionCode='71679' versionName='1.0.5'\nnative-code: 'arm64-v8a'\n", edition, '105');
    assert.throws(() => validateGeneratedFlag(flags102('friends'), edition, '105'));
    assert.throws(() => validateGeneratedFlag(flags102(edition === 'full' ? 'public' : 'full'), edition, '105'));
    assert.throws(() => validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.0.4', versionCode: 71669, outputFile: 'app.apk' }] }, edition, '105'));
    assert.throws(() => validateApkBadging("package: name='org.luma.liquid.web' versionCode='71669' versionName='1.0.4'\nnative-code: 'arm64-v8a'\n", edition, '105'));
  }
});
test('1.0.5 requires exact pair provenance and never relabels historical or restricted APKs', () => {
  validateArtifactMetadata(metadata105(), '105', commit102, expectedEditions('105'));
  validateArtifactMetadata(metadata105(['full']), '105', commit102, ['full']);
  assert.throws(() => validateArtifactMetadata(metadata104(), '105', commit102, expectedEditions('105')));
  assert.throws(() => validateArtifactMetadata(metadata105(), '104', commit102, expectedEditions('104')));
  for (const mutate of [m => m.schemaVersion = 1, m => m.release = '104', m => m.release = 105, m => delete m.release,
    m => m.artifacts[1].versionCode = 71669, m => m.artifacts[1].versionName = '1.0.4',
    m => m.artifacts[1].fileName = 'Lunagram-1.0.4-public-temporary.apk',
    m => m.artifacts[1] = { ...expectedArtifact('friends', '104'), sha256: 'e'.repeat(64) },
    m => m.artifacts[1].friendsEdition = true, m => m.artifacts[1].publicEdition = false,
    m => delete m.artifacts[1].publicEdition, m => m.artifacts[1].publicEdition = 'true',
    m => m.artifacts.push({ ...m.artifacts[0] }), m => m.artifacts.pop()]) {
    const metadata = metadata105(); mutate(metadata);
    assert.throws(() => validateArtifactMetadata(metadata, '105', commit102, expectedEditions('105')));
  }
});
function metadata150(editions = ['full', 'public']) {
  return { schemaVersion: 2, release: '150', sourceCommit: commit102, packageName: 'org.luma.liquid.web', architecture: 'arm64-v8a',
    artifacts: editions.map(edition => ({ ...expectedArtifact(edition, '150'), sha256: 'f'.repeat(64) })) };
}
test('1.5 is the active exact two-part version and strictly upgrades every historical release', () => {
  assert.deepEqual(expectedEditions(), ['full', 'public']);
  assert.deepEqual(expectedArtifact('public'), expectedArtifact('public', '150'));
  assert.throws(() => expectedEditions('150', true));
  assert.throws(() => expectedArtifact('friends'));
  for (const edition of ['full', 'public']) {
    const expected = expectedArtifact(edition);
    assert.equal(expected.versionName, '1.5');
    assert.equal(expected.versionCode, 71699);
    assert.ok(expected.versionCode > 71689, 'Fix build must update the already installed 1.5 candidate');
    assert.ok(expected.versionCode > expectedArtifact(edition, '105').versionCode);
    assert.equal(expected.fileName, `Lunagram-1.5-${edition}-temporary.apk`);
    assert.equal(expected.friendsEdition, false);
    assert.equal(expected.publicEdition, edition === 'public');
    validateGeneratedFlag(flags102(edition), edition);
    validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: '1.5', versionCode: 71699, outputFile: 'app.apk' }] }, edition);
    validateApkBadging("package: name='org.luma.liquid.web' versionCode='71699' versionName='1.5'\nnative-code: 'arm64-v8a'\n", edition);
    for (const other of ['full', 'public', 'friends'].filter(value => value !== edition)) {
      assert.throws(() => validateGeneratedFlag(flags102(other), edition));
    }
    for (const [name, code] of [['1.0.5', 71679], ['1.5.0', 71699], ['1.5', 71689], ['1.5', 71679], ['2.0', 71699]]) {
      assert.throws(() => validateOutputMetadata({ applicationId: 'org.luma.liquid.web', elements: [{ versionName: name, versionCode: code, outputFile: 'app.apk' }] }, edition));
      assert.throws(() => validateApkBadging(`package: name='org.luma.liquid.web' versionCode='${code}' versionName='${name}'\nnative-code: 'arm64-v8a'\n`, edition));
    }
  }
});
test('release150 preserves exact provenance, rejects renamed105 and never permits Friends substitution', () => {
  validateArtifactMetadata(metadata150(), '150', commit102, expectedEditions());
  validateArtifactMetadata(metadata150(['full']), '150', commit102, ['full']);
  validateArtifactMetadata(metadata105(), '105', commit102, expectedEditions('105'));
  assert.throws(() => validateArtifactMetadata(metadata105(), '150', commit102, expectedEditions()));
  assert.throws(() => validateArtifactMetadata(metadata150(), '105', commit102, expectedEditions('105')));
  for (const mutate of [m => m.schemaVersion = 1, m => m.release = '105', m => m.release = 150, m => delete m.release,
    m => m.sourceCommit = 'b'.repeat(40), m => m.architecture = 'x86', m => m.packageName = 'org.telegram.messenger',
    m => m.artifacts[1].versionCode = 71679, m => m.artifacts[1].versionName = '1.0.5',
    m => m.artifacts[1].versionName = '1.5.0', m => m.artifacts[1].fileName = 'Lunagram-1.0.5-public-temporary.apk',
    m => m.artifacts[1] = { ...expectedArtifact('friends', '104'), sha256: 'f'.repeat(64) },
    m => m.artifacts[1].friendsEdition = true, m => m.artifacts[1].publicEdition = false,
    m => delete m.artifacts[1].publicEdition, m => m.artifacts[1].publicEdition = 'true',
    m => m.artifacts[1].sha256 = 'not-a-hash', m => m.artifacts[1].fileName = '../public.apk',
    m => m.artifacts.push({ ...m.artifacts[0] }), m => m.artifacts.pop()]) {
    const metadata = metadata150(); mutate(metadata);
    assert.throws(() => validateArtifactMetadata(metadata, '150', commit102, expectedEditions()));
  }
});
test('wrong package, version, ABI, multiple outputs and path traversal rejected', () => {
  const metadata = output('full');
  metadata.applicationId = 'org.telegram.messenger';
  assert.throws(() => validateOutputMetadata(metadata, 'full', '79'));
  metadata.applicationId = 'org.luma.liquid.web';
  metadata.elements[0].versionCode = 71579;
  assert.throws(() => validateOutputMetadata(metadata, 'full', '79'));
  metadata.elements[0].versionCode = 71589;
  metadata.elements[0].outputFile = '../app.apk';
  assert.throws(() => validateOutputMetadata(metadata, 'full', '79'));
  metadata.elements[0].outputFile = 'app.apk';
  metadata.elements.push({ ...metadata.elements[0] });
  assert.throws(() => validateOutputMetadata(metadata, 'full', '79'));
  assert.throws(() => validateApkBadging(badging('full').replace("'arm64-v8a'", "'arm64-v8a' 'x86'"), 'full', '79'));
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
