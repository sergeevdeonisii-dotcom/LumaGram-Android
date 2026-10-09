import fs from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';

const workflow = fs.readFileSync(new URL('../../.github/workflows/lunagram-release100-apks.yml', import.meta.url), 'utf8');
const build = fs.readFileSync(new URL('./build-lunagram-release100.sh', import.meta.url), 'utf8');

test('1.0.0 workflow and build script enforce the owner repository and exact release branch', () => {
  for (const text of [workflow, build]) {
    assert.ok(text.includes('sergeevdeonisii-dotcom/LumaGram-Android'));
    assert.ok(text.includes('refs/heads/agent/lunagram-release100'));
  }
  assert.match(build, /APP_VERSION_NAME=.*1\.0\.0/);
  assert.match(build, /APP_VERSION_CODE=.*7162/);
  assert.match(build, /source_commit.*GITHUB_SHA/);
  assert.ok(!workflow.includes('pull_request'));
});
test('both edition artifacts use the release100 collector and are verified before publication', () => {
  assert.match(build, /for edition in full friends;/);
  assert.ok(build.includes('-PlumaFriendsEdition=$friends_flag'));
  assert.ok(build.includes('Lunagram-1.0.0-$edition-temporary.apk'));
  assert.match(build, /--release 100 --verify-pair/);
  assert.ok(workflow.includes('lunagram-100-temporary-signed'));
  assert.ok(!/gh\s+release|git\s+push|latest\.json/.test(build));
});
test('CI receives no production signing key and only uploads APKs and public provenance', () => {
  assert.ok(workflow.includes('contents: read'));
  assert.ok(workflow.includes('persist-credentials: false'));
  assert.ok(workflow.includes('include-hidden-files: false'));
  assert.equal([...workflow.matchAll(/secrets\.([A-Z0-9_]+)/g)].map(x => x[1]).sort().join(','), 'LUMA_API_HASH,LUMA_API_ID');
  assert.ok(build.includes('mktemp -d "$RUNNER_TEMP/lunagram100-signing.XXXXXX"'));
  assert.ok(build.includes('trap cleanup EXIT'));
  assert.ok(build.includes('unset LUMA_API_ID LUMA_API_HASH LUMA_CI_SIGNING_PASSWORD'));
  assert.ok(!/rm\s+-(?:rf|fr)/.test(build));
  for (const action of workflow.matchAll(/uses:\s+([^\s]+)/g)) assert.match(action[1], /@[0-9a-f]{40}$/);
});
test('release-blocking regression suites execute before either APK is built', () => {
  const buildStep = workflow.indexOf('run: bash Tools/ci/build-lunagram-release100.sh');
  for (const script of ['run-round-controls81-regressions.ps1', 'run-round-editions100-regressions.ps1',
      'run-round-settings100-regressions.ps1', 'run-startup100-regressions.ps1', 'run-glass-contrast100-regressions.ps1',
      'run-round-limits100-regressions.ps1', 'run-round-stats82-regressions.ps1', 'run-round-export82-regressions.ps1',
      'check-release100-security.ps1', 'run-http-url100-regressions.ps1', 'run-media-browser100-regressions.ps1',
      'run-playback-privacy100-regressions.ps1', 'run-update-presentation100-regressions.ps1']) {
    assert.ok(workflow.indexOf(script) > 0 && workflow.indexOf(script) < buildStep, script);
  }
  for (const line of workflow.split('\n').filter(x => x.includes('-JavaHome'))) {
    assert.ok(line.includes('-OutputRoot (Join-Path $env:RUNNER_TEMP'), 'CI tests cannot target a developer drive');
  }
});
