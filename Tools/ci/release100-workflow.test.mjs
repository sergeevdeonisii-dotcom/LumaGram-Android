import fs from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';

const workflow = fs.readFileSync(new URL('../../.github/workflows/lunagram-release100-apks.yml', import.meta.url), 'utf8');
const build = fs.readFileSync(new URL('./build-lunagram-release100.sh', import.meta.url), 'utf8');
const prepare = fs.readFileSync(new URL('./prepare-lunagram-release105.sh', import.meta.url), 'utf8');
const properties = fs.readFileSync(new URL('../../gradle.properties', import.meta.url), 'utf8');
const subscriptionUi = fs.readFileSync(new URL('../tests/run-subscription-ui-compile.ps1', import.meta.url), 'utf8');
const localGiftsUi = fs.readFileSync(new URL('../tests/run-local-gifts-ui102-regressions.ps1', import.meta.url), 'utf8');
const baselineScript = fs.readFileSync(new URL('./ensure-visual104-baseline.ps1', import.meta.url), 'utf8');
const visualBaseline = '8f60669bc4c257402d18bedd67e1ab71d0f59df8';

test('visual regressions fetch only their pinned baseline before tests without expanding checkout trust', () => {
  assert.ok(workflow.includes('fetch-depth: 1'));
  assert.ok(workflow.includes('run: ./Tools/ci/ensure-visual104-baseline.ps1'));
  assert.ok(workflow.indexOf('ensure-visual104-baseline.ps1') < workflow.indexOf('run-dialogs-canvas104-regressions.ps1'));
  assert.ok(workflow.indexOf('ensure-visual104-baseline.ps1') < workflow.indexOf('run-blur-capture104-regressions.ps1'));
  assert.ok(baselineScript.includes(`$BaselineCommit = '${visualBaseline}'`));
  assert.ok(baselineScript.includes('fetch --no-tags --depth=1 origin $BaselineCommit'));
  assert.ok(baselineScript.includes('$resolved -cne $BaselineCommit'));
  assert.ok(baselineScript.includes('$headAfter -cne $headBefore'));
  assert.ok(!/git[^\r\n]*(?:checkout|reset|push|--unshallow|--depth=0|--force)/.test(baselineScript));
  for (const runner of ['run-dialogs-canvas104-regressions.ps1', 'run-blur-capture104-regressions.ps1']) {
    assert.ok(fs.readFileSync(new URL(`../tests/${runner}`, import.meta.url), 'utf8').includes(visualBaseline), runner);
  }
});

test('the production baseline helper works with a fresh depth-1 clone and preserves HEAD', () => {
  const scratch = fs.mkdtempSync(path.join(process.env.LUMA_CI_TEST_OUTPUT_ROOT || os.tmpdir(), 'lunagram-baseline104-'));
  const origin = path.join(scratch, 'origin');
  const clone = path.join(scratch, 'shallow');
  fs.mkdirSync(origin);
  const run = (command, args, expectedCode = 0) => {
    const result = spawnSync(command, args, { encoding: 'utf8', timeout: 30000 });
    assert.ifError(result.error);
    assert.equal(result.status, expectedCode, result.stderr || result.stdout);
    return result.stdout.trim();
  };
  const git = (...args) => run('git', args);
  git('-C', origin, 'init', '-b', 'baseline-test');
  fs.writeFileSync(path.join(origin, 'fixture.txt'), 'baseline\n');
  git('-C', origin, 'add', 'fixture.txt');
  const commit = message => git('-C', origin, '-c', 'user.name=Lunagram regression', '-c', 'user.email=regression@example.invalid', 'commit', '-m', message);
  commit('baseline');
  const baseline = git('-C', origin, 'rev-parse', 'HEAD');
  fs.writeFileSync(path.join(origin, 'fixture.txt'), 'current\n');
  git('-C', origin, 'add', 'fixture.txt');
  commit('current');
  const head = git('-C', origin, 'rev-parse', 'HEAD');
  git('clone', '--no-tags', '--depth=1', pathToFileURL(origin).href, clone);
  assert.equal(git('-C', clone, 'rev-parse', '--is-shallow-repository'), 'true');
  assert.notEqual(spawnSync('git', ['-C', clone, 'cat-file', '-e', `${baseline}^{commit}`], { encoding: 'utf8' }).status, 0);
  assert.equal(baselineScript.split(visualBaseline).length, 2, 'replace only the pinned literal for this synthetic fixture');
  const helper = path.join(scratch, 'ensure-baseline-fixture.ps1');
  fs.writeFileSync(helper, baselineScript.replace(visualBaseline, baseline));
  const pwsh = process.env.LUMA_PWSH_PATH || 'pwsh';
  for (let attempt = 0; attempt < 2; attempt++) {
    run(pwsh, ['-NoProfile', '-File', helper, '-RepoRoot', clone]);
    assert.equal(git('-C', clone, 'rev-parse', `${baseline}^{commit}`), baseline);
    assert.equal(git('-C', clone, 'rev-parse', 'HEAD'), head);
    assert.equal(git('-C', clone, 'status', '--porcelain'), '');
  }
  fs.writeFileSync(helper, baselineScript.replace(visualBaseline, '0000000000000000000000000000000000000000'));
  run(pwsh, ['-NoProfile', '-File', helper, '-RepoRoot', clone], 1);
  assert.equal(git('-C', clone, 'rev-parse', 'HEAD'), head, 'unavailable baseline must fail closed without moving HEAD');
  assert.equal(git('-C', clone, 'status', '--porcelain'), '');
});

test('1.0.5 workflow and scripts enforce the owner repository, source commit and exact release identity', () => {
  for (const text of [workflow, build, prepare]) {
    assert.ok(text.includes('sergeevdeonisii-dotcom/LumaGram-Android'));
    assert.ok(text.includes('refs/heads/agent/lunagram-release100'));
  }
  for (const text of [build, prepare]) {
    assert.match(text, /APP_VERSION_NAME=.*1\.0\.5/);
    assert.match(text, /APP_VERSION_CODE=.*7167/);
    assert.ok(text.includes('GITHUB_SHA'));
  }
  assert.match(properties, /^APP_VERSION_NAME=1\.0\.5\r?$/m);
  assert.match(properties, /^APP_VERSION_CODE=7167\r?$/m);
  assert.match(build, /source_commit.*GITHUB_SHA/);
  assert.ok(!workflow.includes('pull_request'));
});
test('release105 builds and uploads only the genuine public and personal APK pair', () => {
  assert.ok(build.includes('editions=(full public)'));
  assert.ok(!build.includes('editions+=(friends)'));
  assert.ok(!build.includes('--include-friends'));
  assert.ok(!build.includes('friends_flag=true'));
  assert.match(build, /\[\[ "\$\{LUMA_BUILD_FRIENDS_EDITION:-false\}" == false \]\] \|\| fail/);
  assert.match(build, /\[\[ "\$edition" != public \]\] \|\| public_flag=true/);
  assert.ok(build.includes('-PlumaFriendsEdition=$friends_flag'));
  assert.ok(build.includes('-PlumaPublicEdition=$public_flag'));
  assert.ok(build.includes('Lunagram-1.0.5-$edition-temporary.apk'));
  assert.match(build, /--release 105 --verify-pair/);
  assert.ok(workflow.includes('lunagram-105-temporary-signed'));
  const uploads = workflow.split(/\r?\n/).filter(line => line.includes('${{ runner.temp }}/'));
  assert.deepEqual(uploads.map(line => line.trim()), [
    '${{ runner.temp }}/lunagram105-artifacts/Lunagram-1.0.5-full-temporary.apk',
    '${{ runner.temp }}/lunagram105-artifacts/Lunagram-1.0.5-public-temporary.apk',
    '${{ runner.temp }}/lunagram105-artifacts/build-metadata.json',
  ]);
  assert.ok(!workflow.includes('*.apk') && !workflow.includes('build_friends') && !workflow.includes('LUMA_BUILD_FRIENDS_EDITION:'));
  assert.ok(!/--release (102|103|104)\b/.test(build));
  assert.ok(!build.includes('for edition in full friends;'));
  assert.ok(!/gh\s+release|git\s+push|latest\.json/.test(build));
});
test('CI receives no production signing key and only uploads APKs and public provenance', () => {
  assert.ok(workflow.includes('contents: read'));
  assert.ok(workflow.includes('persist-credentials: false'));
  assert.ok(workflow.includes('include-hidden-files: false'));
  assert.equal([...workflow.matchAll(/secrets\.([A-Z0-9_]+)/g)].map(x => x[1]).sort().join(','), 'LUMA_API_HASH,LUMA_API_ID');
  assert.ok(build.includes('mktemp -d "$RUNNER_TEMP/lunagram105-signing.XXXXXX"'));
  assert.ok(build.includes('trap cleanup EXIT'));
  assert.ok(build.includes('unset LUMA_API_ID LUMA_API_HASH LUMA_CI_SIGNING_PASSWORD'));
  assert.ok(!/rm\s+-(?:rf|fr)/.test(build));
  for (const action of workflow.matchAll(/uses:\s+([^\s]+)/g)) assert.match(action[1], /@[0-9a-f]{40}$/);
});
test('SDK36 is prepared before credential-free subscription probes, and all three block assembly', () => {
  const sdkStep = workflow.indexOf('run: bash Tools/ci/prepare-lunagram-release105.sh');
  const buildStep = workflow.indexOf('      - name: Build personal and public APKs');
  assert.ok(sdkStep > 0 && sdkStep < buildStep);
  assert.ok(workflow.includes('bash -n Tools/ci/prepare-lunagram-release105.sh'));
  for (const script of ['run-subscription-auth-regressions.ps1', 'run-subscriptions-regressions.ps1', 'run-subscription-ui-compile.ps1']) {
    const offset = workflow.indexOf(script);
    assert.ok(offset > sdkStep && offset < buildStep, script);
    assert.equal(workflow.split(script).length, 2, `${script} executes exactly once`);
  }
  for (const sdkPackage of ['platforms;android-36', 'build-tools;36.0.0', 'ndk;27.2.12479018', 'cmake;3.22.1']) {
    assert.ok(prepare.includes(`'${sdkPackage}'`), sdkPackage);
  }
  assert.ok(!prepare.includes('LUMA_API_') && !prepare.includes('keytool') && !prepare.includes('GradleWrapperMain'));
  assert.ok(!workflow.slice(0, buildStep).includes('secrets.'));
  assert.ok(!build.includes('sdkmanager'), 'SDK provisioning must not inherit API credentials');
  assert.ok(workflow.includes("-Aapt2 (Join-Path $sdkRoot 'build-tools/36.0.0/aapt2')"));
  assert.ok(workflow.includes("-AndroidJar (Join-Path $sdkRoot 'platforms/android-36/android.jar')"));
  assert.ok(subscriptionUi.includes('[string]$OutputRoot='));
  assert.ok(subscriptionUi.includes('configured runner temporary storage'));
  assert.ok(subscriptionUi.includes('$run=Join-Path $output'));
});
test('release-blocking regression suites execute before either APK is built', () => {
  const buildStep = workflow.indexOf('run: bash Tools/ci/build-lunagram-release100.sh');
  for (const script of ['run-round-controls81-regressions.ps1', 'run-round-editions100-regressions.ps1',
      'run-round-concurrent102-regressions.ps1',
      'run-dialogs-canvas104-regressions.ps1', 'run-blur-capture104-regressions.ps1',
      'run-gift-geometry104-regressions.ps1',
      'run-shared-media-geometry104b-regressions.ps1',
      'run-layout-diagnostics104b-regressions.ps1',
      'run-local-gifts102-regressions.ps1',
      'run-local-gifts-ui102-regressions.ps1', 'run-logout102-regressions.ps1',
      'run-round-settings100-regressions.ps1', 'run-startup100-regressions.ps1', 'run-glass-contrast100-regressions.ps1',
      'run-round-limits100-regressions.ps1', 'run-round-stats82-regressions.ps1', 'run-round-export82-regressions.ps1',
      'check-release100-security.ps1', 'run-svg101-regressions.ps1', 'run-http-url100-regressions.ps1', 'run-media-browser100-regressions.ps1',
      'run-playback-privacy100-regressions.ps1', 'run-update-presentation100-regressions.ps1',
      'lunagram-support100.test.mjs', 'run-subscription-auth-regressions.ps1',
      'run-subscriptions-regressions.ps1', 'run-subscription-ui-compile.ps1']) {
    assert.ok(workflow.indexOf(script) > 0 && workflow.indexOf(script) < buildStep, script);
  }
  for (const line of workflow.split('\n').filter(x => x.includes('-JavaHome'))) {
    assert.ok(line.includes('-OutputRoot (Join-Path $env:RUNNER_TEMP'), 'CI tests cannot target a developer drive');
  }
  assert.ok(localGiftsUi.includes("& (Join-Path $PSScriptRoot 'run-local-gifts-state102-regressions.ps1') -JavaHome $JavaHome -OutputRoot (Join-Path $OutputRoot 'state')"),
    'The required local-gifts UI suite must also run state/clone checks with the supplied CI runtime/output root');
  assert.ok(!workflow.includes('./Tools/tests/run-local-gifts-state102-regressions.ps1'), 'State checks are nested in the UI runner and must not run twice');
});
