import fs from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  expectedArtifact, expectedEditions, validateArtifactMetadata, validateOutputMetadata,
  validateGeneratedFlag, validateCompiledPublicFlags, validatePackagedPublicEdition, validateApkBadging,
} from './collect-lunagram-apk.mjs';

const workflow = fs.readFileSync(new URL('../../.github/workflows/lunagram-300-test-apk.yml', import.meta.url), 'utf8');
const build = fs.readFileSync(new URL('./build-lunagram300.sh', import.meta.url), 'utf8');
const buildPath = fileURLToPath(new URL('./build-lunagram300.sh', import.meta.url));
const commit = 'a'.repeat(40);
const flags = '  public static final boolean LUMA_FRIENDS_EDITION = false;\n  public static final boolean LUMA_PUBLIC_EDITION = true;\n';
const manifest = `E: manifest (line=1)
  E: application (line=4)
    E: meta-data (line=6)
      A: android:name(0x01010003)="org.lunagram.edition" (Raw: "org.lunagram.edition")
      A: android:value(0x01010024)="public" (Raw: "public")
    E: activity (line=10)
      A: android:name(0x01010003)="org.telegram.ui.LaunchActivity"
`;
const badging = "package: name='org.luma.liquid.web' versionCode='72009' versionName='3.0.0'\nnative-code: 'arm64-v8a'\n";
function metadata() {
  return { schemaVersion: 2, release: '300', sourceCommit: commit, packageName: 'org.luma.liquid.web', architecture: 'arm64-v8a',
    temporarySigning: true, productionSigningRequired: true,
    verification: { compiledLibraryEdition: 'public', packagedEdition: 'public', debuggable: false },
    artifacts: [{ ...expectedArtifact('public', '300'), sha256: 'b'.repeat(64) }] };
}

test('300 is exactly one public 3.0.0/72009 APK, never a personal pair or a historical relabel', () => {
  assert.deepEqual(expectedEditions('300'), ['public']);
  assert.throws(() => expectedEditions('300', true));
  for (const edition of ['full', 'friends', 'personal', '', null]) assert.throws(() => expectedArtifact(edition, '300'));
  const expected = expectedArtifact('public', '300');
  assert.deepEqual(expected, { edition: 'public', fileName: 'Lunagram-3.0.0-public-temporary.apk', versionName: '3.0.0', versionCode: 72009, friendsEdition: false, publicEdition: true });
  const output = { applicationId: 'org.luma.liquid.web', elements: [{ versionName: '3.0.0', versionCode: 72009, outputFile: 'app.apk' }] };
  assert.equal(validateOutputMetadata(output, 'public', '300'), 'app.apk');
  validateApkBadging(badging, 'public', '300');
  for (const stale of [badging.replace('72009', '71869'), badging.replace('3.0.0', '2.0.6'), badging + 'application-debuggable\n', badging.replace("'arm64-v8a'", "'arm64-v8a' 'x86_64'")]) {
    assert.throws(() => validateApkBadging(stale, 'public', '300'));
  }
});

test('generated and compiled flags fail closed without printing neighboring API constants', () => {
  validateGeneratedFlag(flags, 'public', '300');
  validateCompiledPublicFlags('Compiled from "BuildConfig.java"\npublic final class org.telegram.messenger.BuildConfig {\n' + flags + '}');
  for (const wrong of [flags.replace('PUBLIC_EDITION = true', 'PUBLIC_EDITION = false'), flags.replace('FRIENDS_EDITION = false', 'FRIENDS_EDITION = true'),
    flags + flags, flags.split('\n')[0], 'public static final String APP_HASH = "SECRET_FIXTURE_NOT_FOR_LOGS";']) {
    assert.throws(() => validateCompiledPublicFlags(wrong), error => !error.message.includes('SECRET_FIXTURE'));
  }
});

test('packaged edition comes from exactly one correct metadata node, not an unrelated or nested value', () => {
  validatePackagedPublicEdition(manifest);
  for (const wrong of [manifest.replace('"public"', '"personal"'), manifest.replace('org.lunagram.edition', 'other.setting'), manifest + manifest,
    manifest.replace('      A: android:value', '      E: injected\n        A: android:value'),
    manifest.replace('      A: android:value(0x01010024)="public" (Raw: "public")\n', '')]) {
    assert.throws(() => validatePackagedPublicEdition(wrong));
  }
});

test('provenance requires source SHA, APK SHA, public flags and explicit local re-signing requirement', () => {
  validateArtifactMetadata(metadata(), '300', commit, expectedEditions('300'));
  for (const mutate of [m => m.sourceCommit = 'c'.repeat(40), m => m.release = '150', m => m.artifacts.push({ ...m.artifacts[0] }),
    m => m.artifacts[0].publicEdition = false, m => m.artifacts[0].friendsEdition = true, m => m.artifacts[0].sha256 = 'bad',
    m => m.artifacts[0].versionCode = 71869, m => m.artifacts[0].fileName = '../candidate.apk',
    m => m.temporarySigning = false, m => delete m.productionSigningRequired, m => delete m.verification,
    m => m.verification.compiledLibraryEdition = 'personal', m => m.verification.packagedEdition = 'unknown', m => m.verification.debuggable = true]) {
    const broken = metadata(); mutate(broken);
    assert.throws(() => validateArtifactMetadata(broken, '300', commit, expectedEditions('300')));
  }
});

test('workflow is owner test-branch push/manual only, with pinned official actions and read-only permissions', () => {
  assert.ok(workflow.includes('branches: ["agent/lunagram-300-test"]'));
  assert.ok(workflow.includes('workflow_dispatch:'));
  assert.ok(workflow.includes("github.repository == 'sergeevdeonisii-dotcom/LumaGram-Android' && github.ref == 'refs/heads/agent/lunagram-300-test'"));
  assert.ok(!/pull_request|pull_request_target|schedule:|workflow_run:/.test(workflow));
  assert.ok(workflow.includes('contents: read') && !/\bwrite\b/.test(workflow));
  assert.ok(workflow.includes('persist-credentials: false'));
  const actions = [...workflow.matchAll(/uses:\s+([^\s]+)/g)].map(match => match[1]);
  assert.deepEqual(actions, ['actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803',
    'actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961', 'actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02']);
});

test('only API credentials reach build; exact temporary outputs uploaded for three days, never a release', () => {
  assert.deepEqual([...workflow.matchAll(/secrets\.([A-Z0-9_]+)/g)].map(x => x[1]).sort(), ['LUMA_API_HASH', 'LUMA_API_ID']);
  const buildStep = workflow.indexOf('      - name: Build public test APK');
  assert.ok(buildStep > workflow.indexOf('--prepare-sdk'));
  assert.ok(!workflow.slice(0, buildStep).includes('secrets.'));
  assert.deepEqual(workflow.split(/\r?\n/).filter(line => line.includes('${{ runner.temp }}/')).map(line => line.trim()), [
    '${{ runner.temp }}/lunagram300-artifacts/Lunagram-3.0.0-public-temporary.apk',
    '${{ runner.temp }}/lunagram300-artifacts/build-metadata.json',
  ]);
  for (const value of ['retention-days: 3', 'include-hidden-files: false', 'if-no-files-found: error']) assert.ok(workflow.includes(value));
  assert.ok(!/\*\.apk|\.jks|\.properties/.test(workflow));
  assert.ok(!/gh\s+release|git\s+push|latest\.json|wrangler\s+deploy/.test(build));
});

test('builder has exact edition/version, bounded resources, pinned SDK and own-file secret cleanup', () => {
  for (const value of ['source_commit', 'GITHUB_SHA', 'git diff --quiet', 'git diff --cached --quiet',
    "'-PAPP_VERSION_NAME=3.0.0'", "'-PAPP_VERSION_CODE=7200'", "'-PlumaFriendsEdition=false'", "'-PlumaPublicEdition=true'",
    '-Xmx4g', '--no-parallel --max-workers=2', 'CMAKE_BUILD_PARALLEL_LEVEL=2',
    "'platforms;android-36'", "'build-tools;36.0.0'", "'ndk;27.2.12479018'", "'cmake;3.22.1'",
    'mktemp -d "$RUNNER_TEMP/lunagram300-signing.XXXXXX"', 'trap cleanup EXIT', "trap 'exit 143' TERM HUP",
    'unset LUMA_API_ID LUMA_API_HASH LUMA_CI_SIGNING_PASSWORD', '--release 300 --verify-pair', '--edition public', '--javap "$JAVA_HOME/bin/javap"']) assert.ok(build.includes(value), value);
  assert.ok(!/rm\s+-(?:rf|fr)|set\s+-x|--debug|--scan|editions=|PlumaPublicEdition=false|PlumaFriendsEdition=true/.test(build));
  assert.ok(build.includes('[[ ! -e local-luma.properties && ! -L local-luma.properties && ! -e local-signing && ! -L local-signing ]]'));
  assert.ok(build.includes('git check-ignore -q local-luma.properties'));
  const standalone = fs.readFileSync(new URL('../../TMessagesProj_AppStandalone/build.gradle', import.meta.url), 'utf8');
  assert.ok(standalone.includes('output.versionCodeOverride = defaultConfig.versionCode * 10 + variant.productFlavors.get(0).abiVersionCode'));
  assert.equal(7200 * 10 + Number(standalone.match(/abiVersionCode = (\d+)/)?.[1]), 72009);
});

test('all focused tests are credential-free and explicitly portable, with no historical broad suite', () => {
  const buildStep = workflow.indexOf('      - name: Build public test APK');
  for (const script of ['run-feature-catalog150-regressions.ps1', 'run-subscription-screen150-regressions.ps1',
    'run-subscription-layout204-regressions.ps1', 'run-lunagram20-swipe-regressions.ps1', 'run-lunagram-media-access-regressions.ps1',
    'run-round-startup-latency-regressions.ps1', 'run-round-concurrent102-regressions.ps1',
    'run-productivity-regressions.ps1', 'run-media-tools-regressions.ps1', 'run-local-labels-regressions.ps1']) {
    assert.ok(workflow.indexOf(script) > 0 && workflow.indexOf(script) < buildStep, script);
    const runner = fs.readFileSync(new URL(`../tests/${script}`, import.meta.url), 'utf8');
    assert.ok(runner.includes('$OutputRoot') && runner.includes('$JavaHome'), script);
    assert.ok(runner.includes('Windows_NT') && !/Join-Path \$JavaHome 'bin\/(java|javac)\.exe'/.test(runner), script);
  }
  for (const line of workflow.split('\n').filter(x => x.includes('-JavaHome'))) assert.ok(line.includes('-OutputRoot (Join-Path $env:RUNNER_TEMP'));
  assert.ok(!/release100-workflow|ensure-visual104|lunagram-billing|run-lunagram20-experience-regressions/.test(workflow));
});

test('new 3.0.0 provenance never accepts the delivered 2.0.6 artifact and preserves historical defaults', () => {
  assert.ok(workflow.includes('run-lunagram210-surface-contracts.ps1 -Aapt2 (Join-Path $env:ANDROID_HOME'));
  assert.equal(expectedArtifact('public', '206').versionCode, 71869);
  assert.equal(expectedArtifact('public', '206').versionName, '2.0.6');
  assert.equal(expectedArtifact('public').versionName, '1.5');
  const old = metadata(); old.release = '206'; old.artifacts = [{ ...expectedArtifact('public', '206'), sha256: 'b'.repeat(64) }];
  validateArtifactMetadata(old, '206', commit, expectedEditions('206'));
  assert.throws(() => validateArtifactMetadata(old, '300', commit, expectedEditions('300')));
  assert.throws(() => validateArtifactMetadata(metadata(), '206', commit, expectedEditions('206')));
  for (const script of ['run-productivity-regressions.ps1', 'run-media-tools-regressions.ps1', 'run-local-labels-regressions.ps1']) {
    const runner = fs.readFileSync(new URL(`../tests/${script}`, import.meta.url), 'utf8');
    assert.ok(runner.includes('-J-Xmx128m') && runner.includes('-Xmx128m'), script);
    assert.ok(!/Start-Process|Invoke-WebRequest|curl\b|Invoke-RestMethod/.test(runner), script);
  }
});

test('actual shell parses and rejects unsafe invocation contexts before network, credentials or build actions', () => {
  const bash = process.env.LUMA_BASH_PATH || 'bash';
  let result = spawnSync(bash, ['-n', buildPath], { encoding: 'utf8', timeout: 15000 });
  assert.ifError(result.error); assert.equal(result.status, 0, result.stderr);
  const common = { ...process.env, GITHUB_ACTIONS: 'true', GITHUB_REPOSITORY: 'sergeevdeonisii-dotcom/LumaGram-Android',
    GITHUB_REF: 'refs/heads/agent/lunagram-300-test', GITHUB_EVENT_NAME: 'push' };
  for (const [args, override, reason] of [
    [[], {}, 'Choose exactly'], [['--release'], {}, 'Choose exactly'],
    [['--build'], { GITHUB_ACTIONS: 'false' }, 'GitHub Actions only'],
    [['--build'], { GITHUB_REPOSITORY: 'other/repo' }, 'Unexpected repository'],
    [['--build'], { GITHUB_REF: 'refs/heads/main' }, 'Unexpected source branch'],
    [['--build'], { GITHUB_EVENT_NAME: 'pull_request' }, 'Unexpected workflow event'],
  ]) {
    result = spawnSync(bash, [buildPath, ...args], { env: { ...common, ...override }, encoding: 'utf8', timeout: 15000 });
    assert.ifError(result.error); assert.notEqual(result.status, 0); assert.ok(result.stderr.includes(reason), result.stderr);
  }
});

test('300 upgrades delivered 210 and runs reviewed feed and search regressions before credentials', () => {
  const old = expectedArtifact('public', '210');
  const current = expectedArtifact('public', '300');
  assert.equal(old.versionName, '2.1.0');
  assert.equal(old.versionCode, 71909);
  assert.ok(current.versionCode > old.versionCode);
  assert.throws(() => validateApkBadging("package: name='org.luma.liquid.web' versionCode='71909' versionName='2.1.0'\nnative-code: 'arm64-v8a'\n", 'public', '300'));
  const buildStep = workflow.indexOf('      - name: Build public test APK');
  assert.ok(workflow.indexOf('check-release100-security.ps1') > 0 && workflow.indexOf('check-release100-security.ps1') < buildStep);
  for (const name of ['run-search-header-regressions.ps1', 'run-feed-chip-regressions.ps1', 'run-feed-ultra-regressions.ps1']) {
    assert.ok(workflow.indexOf(name) > 0 && workflow.indexOf(name) < buildStep, name);
    const runner = fs.readFileSync(new URL(`../tests/${name}`, import.meta.url), 'utf8');
    assert.ok(runner.includes('$JavaHome') && runner.includes('$OutputRoot'), name);
    assert.ok(!/Join-Path \$JavaHome 'bin\/(java|javac)\.exe'/.test(runner), name);
  }
  const properties = fs.readFileSync(new URL('../../gradle.properties', import.meta.url), 'utf8');
  assert.match(properties, /^APP_VERSION_CODE=7200$/m);
  assert.match(properties, /^APP_VERSION_NAME=3\.0\.0$/m);
});
