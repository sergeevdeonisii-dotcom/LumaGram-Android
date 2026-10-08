import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'org.luma.liquid.web';
const RELEASES = {
  '79': { version: '12.10.6-lunagram.79', code: 71589 },
  '80': { version: '12.10.6-lunagram.80', code: 71599 },
};

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

export function expectedArtifact(edition, release = '79') {
  requireValue(edition === 'full' || edition === 'friends', 'Unknown APK edition.');
  requireValue(Object.hasOwn(RELEASES, release), 'Unknown APK release.');
  const { version, code } = RELEASES[release];
  return {
    edition,
    fileName: `Lunagram-${version}-${edition}-temporary.apk`,
    versionName: version + (edition === 'friends' ? '-friends' : ''),
    versionCode: code,
    friendsEdition: edition === 'friends',
  };
}

export function validateOutputMetadata(metadata, edition, release = '79') {
  const expected = expectedArtifact(edition, release);
  requireValue(metadata.applicationId === PACKAGE, 'Output metadata has an unexpected package.');
  requireValue(Array.isArray(metadata.elements) && metadata.elements.length === 1, 'Expected one standalone arm64 APK.');
  const element = metadata.elements[0];
  requireValue(element.versionName === expected.versionName && element.versionCode === expected.versionCode, 'Output APK version does not match the requested edition.');
  requireValue(typeof element.outputFile === 'string' && element.outputFile.endsWith('.apk')
    && path.basename(element.outputFile) === element.outputFile
    && !element.outputFile.includes('\\') && !element.outputFile.includes('/'), 'Unexpected APK output path.');
  return element.outputFile;
}

export function validateGeneratedFlag(source, edition) {
  const flags = [...source.matchAll(/^\s*public static final boolean LUMA_FRIENDS_EDITION = (true|false);\s*$/gm)];
  requireValue(flags.length === 1 && (flags[0][1] === 'true') === expectedArtifact(edition).friendsEdition, 'Generated library edition flag is missing or incorrect.');
  // Never print any part of BuildConfig: adjacent fields contain API credentials.
}

export function validateApkBadging(badging, edition, release = '79') {
  const expected = expectedArtifact(edition, release);
  const name = badging.match(/^package: name='([^']+)'/m)?.[1];
  const versionCode = Number(badging.match(/^package:.*\bversionCode='([0-9]+)'/m)?.[1]);
  const versionName = badging.match(/^package:.*\bversionName='([^']+)'/m)?.[1];
  requireValue(name === PACKAGE && versionCode === expected.versionCode && versionName === expected.versionName, 'Packaged APK identity/version does not match its metadata.');
  const abiLine = badging.match(/^native-code:\s*(.*)$/m)?.[1];
  requireValue(abiLine?.trim() === "'arm64-v8a'", 'Packaged APK must contain arm64-v8a native libraries only.');
}

async function sha256(file) {
  const hash = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}

function safeArtifactPath(directory, fileName) {
  requireValue(path.basename(fileName) === fileName, 'Unsafe artifact filename.');
  return path.join(directory, fileName);
}

async function collect(args) {
  const expected = expectedArtifact(args.edition, args.release);
  const repo = path.resolve(args['repo-root']);
  const directory = path.resolve(args['artifact-dir']);
  const outputDirectory = path.join(repo, 'TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone');
  const outputMetadata = JSON.parse(fs.readFileSync(path.join(outputDirectory, 'output-metadata.json'), 'utf8'));
  const sourceApk = path.join(outputDirectory, validateOutputMetadata(outputMetadata, args.edition, args.release));
  const buildConfig = path.join(repo, 'TMessagesProj/build/generated/source/buildConfig/standalone/org/telegram/messenger/BuildConfig.java');
  validateGeneratedFlag(fs.readFileSync(buildConfig, 'utf8'), args.edition);
  const badging = spawnSync(args.aapt, ['dump', 'badging', sourceApk], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  requireValue(!badging.error && badging.status === 0, 'Android APK inspection failed.');
  validateApkBadging(badging.stdout, args.edition, args.release);
  const revision = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' });
  const commit = revision.stdout?.trim();
  requireValue(revision.status === 0 && /^[0-9a-f]{40}$/.test(commit), 'Source commit is unavailable.');
  requireValue(commit === process.env.GITHUB_SHA, 'Source commit differs from the workflow commit.');
  const destination = safeArtifactPath(directory, expected.fileName);
  fs.copyFileSync(sourceApk, destination, fs.constants.COPYFILE_EXCL);
  const hash = await sha256(destination);
  const metadataPath = path.join(directory, 'build-metadata.json');
  let metadata = { schemaVersion: 1, sourceCommit: commit, packageName: PACKAGE, architecture: 'arm64-v8a', artifacts: [] };
  if (fs.existsSync(metadataPath)) {
    metadata = JSON.parse(fs.readFileSync(metadataPath, 'utf8'));
    requireValue(args.edition === 'friends' && metadata.sourceCommit === commit
      && metadata.schemaVersion === 1 && metadata.packageName === PACKAGE
      && metadata.architecture === 'arm64-v8a'
      && metadata.artifacts?.length === 1 && metadata.artifacts[0].edition === 'full', 'Existing build metadata is inconsistent.');
  } else {
    requireValue(args.edition === 'full', 'Full APK must be collected before Friends.');
  }
  metadata.artifacts.push({ ...expected, sha256: hash });
  fs.writeFileSync(metadataPath, JSON.stringify(metadata, null, 2) + '\n', { mode: 0o600 });
  console.log(`Collected ${args.edition} APK; public SHA-256 ${hash}.`);
}

async function verifyPair(directory, release) {
  const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'build-metadata.json'), 'utf8'));
  requireValue(metadata.schemaVersion === 1 && metadata.packageName === PACKAGE && metadata.architecture === 'arm64-v8a'
    && metadata.sourceCommit === process.env.GITHUB_SHA && metadata.artifacts?.length === 2, 'Incomplete APK pair metadata.');
  for (const edition of ['full', 'friends']) {
    const entry = metadata.artifacts.find(item => item.edition === edition);
    const expected = expectedArtifact(edition, release);
    requireValue(entry && Object.entries(expected).every(([key, value]) => entry[key] === value), 'APK pair has an inconsistent edition.');
    requireValue(/^[0-9a-f]{64}$/.test(entry.sha256)
      && await sha256(safeArtifactPath(directory, entry.fileName)) === entry.sha256, 'Artifact SHA-256 does not match.');
  }
  console.log('Verified Full/Friends APK pair and public metadata.');
}

async function main() {
  const args = {};
  for (let i = 2; i < process.argv.length; i++) {
    const key = process.argv[i];
    requireValue(key.startsWith('--'), 'Unexpected collector argument.');
    if (key === '--verify-pair') args['verify-pair'] = true;
    else args[key.slice(2)] = process.argv[++i];
  }
  requireValue(typeof args['artifact-dir'] === 'string', 'Artifact directory is required.');
  if (args['verify-pair']) return verifyPair(path.resolve(args['artifact-dir']), args.release);
  requireValue(typeof args['repo-root'] === 'string' && typeof args.aapt === 'string', 'Repository and Android inspection tool are required.');
  return collect(args);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
