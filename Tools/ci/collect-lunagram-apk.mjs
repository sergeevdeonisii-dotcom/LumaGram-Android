import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'org.luma.liquid.web';
const RELEASES = {
  '79': { version: '12.10.6-lunagram.79', code: 71589 },
  '80': { version: '12.10.6-lunagram.80', code: 71599 },
  '81': { version: '12.10.6-lunagram.81', code: 71609 },
  '82': { version: '12.10.6-lunagram.82', code: 71619 },
  '100': { version: '1.0.0', code: 71629, unifiedVersionName: true },
  '101': { version: '1.0.1', code: 71639, unifiedVersionName: true },
  '102': { version: '1.0.2', code: 71649, unifiedVersionName: true, separatePublicEdition: true },
  '103': { version: '1.0.3', code: 71659, unifiedVersionName: true, separatePublicEdition: true },
  '104': { version: '1.0.4', code: 71669, unifiedVersionName: true, separatePublicEdition: true },
  '105': { version: '1.0.5', code: 71679, unifiedVersionName: true, separatePublicEdition: true, friendsEditionAllowed: false },
  '150': { version: '1.5', code: 71699, unifiedVersionName: true, separatePublicEdition: true, friendsEditionAllowed: false },
  '206': { version: '2.0.6', code: 71869, unifiedVersionName: true, separatePublicEdition: true, friendsEditionAllowed: false, publicOnly: true },
  '210': { version: '2.1.0', code: 71909, unifiedVersionName: true, separatePublicEdition: true, friendsEditionAllowed: false, publicOnly: true },
  '300': { version: '3.0.0', code: 72009, unifiedVersionName: true, separatePublicEdition: true, friendsEditionAllowed: false, publicOnly: true },
};

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

export function expectedArtifact(edition, release = '150') {
  requireValue(Object.hasOwn(RELEASES, release), 'Unknown APK release.');
  const { version, code, unifiedVersionName, separatePublicEdition, friendsEditionAllowed = true, publicOnly = false } = RELEASES[release];
  requireValue(!publicOnly || edition === 'public', 'This test release allows the public edition only.');
  requireValue(edition === 'full' || (friendsEditionAllowed && edition === 'friends') || (separatePublicEdition && edition === 'public'), 'Unknown APK edition for this release.');
  return {
    edition,
    fileName: `Lunagram-${version}-${edition}-temporary.apk`,
    versionName: version + (edition === 'friends' && !unifiedVersionName ? '-friends' : ''),
    versionCode: code,
    friendsEdition: edition === 'friends',
    ...(separatePublicEdition ? { publicEdition: edition === 'public' } : {}),
  };
}

export function validateOutputMetadata(metadata, edition, release = '150') {
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

export function validateGeneratedFlag(source, edition, release = '150') {
  const expected = expectedArtifact(edition, release);
  const flags = [...source.matchAll(/^\s*public static final boolean LUMA_FRIENDS_EDITION = (true|false);\s*$/gm)];
  requireValue(flags.length === 1 && (flags[0][1] === 'true') === expected.friendsEdition, 'Generated library Friends flag is missing or incorrect.');
  if (Object.hasOwn(expected, 'publicEdition')) {
    const publicFlags = [...source.matchAll(/^\s*public static final boolean LUMA_PUBLIC_EDITION = (true|false);\s*$/gm)];
    requireValue(publicFlags.length === 1 && (publicFlags[0][1] === 'true') === expected.publicEdition, 'Generated library Public flag is missing or incorrect.');
  }
  // Never print any part of BuildConfig: adjacent fields contain API credentials.
}

export function expectedEditions(release = '150', includeFriends = false) {
  requireValue(Object.hasOwn(RELEASES, release), 'Unknown APK release.');
  requireValue(typeof includeFriends === 'boolean', 'Invalid Friends build selection.');
  requireValue(!includeFriends || RELEASES[release].friendsEditionAllowed !== false, 'Friends edition is not available for this release.');
  if (RELEASES[release].publicOnly) return ['public'];
  const separatePublicEdition = RELEASES[release].separatePublicEdition === true;
  requireValue(!includeFriends || separatePublicEdition, 'Optional Friends builds require separate Public edition support.');
  return separatePublicEdition ? ['full', 'public', ...(includeFriends ? ['friends'] : [])] : ['full', 'friends'];
}

export function validateArtifactMetadata(metadata, release, commit, editions) {
  requireValue(Object.hasOwn(RELEASES, release), 'Unknown APK release.');
  const schemaVersion = RELEASES[release].separatePublicEdition ? 2 : 1;
  requireValue(typeof commit === 'string' && /^[0-9a-f]{40}$/.test(commit), 'An exact source commit is required.');
  requireValue(metadata?.schemaVersion === schemaVersion && metadata.packageName === PACKAGE
    && metadata.architecture === 'arm64-v8a' && metadata.sourceCommit === commit
    && Array.isArray(metadata.artifacts) && metadata.artifacts.length === editions.length
    && (schemaVersion !== 2 || metadata.release === release), 'APK artifact metadata is inconsistent.');
  if (RELEASES[release].publicOnly) {
    requireValue(metadata.temporarySigning === true && metadata.productionSigningRequired === true
      && metadata.verification?.compiledLibraryEdition === 'public'
      && metadata.verification?.packagedEdition === 'public'
      && metadata.verification?.debuggable === false, 'Public test artifact verification is incomplete.');
  }
  for (const edition of editions) {
    const entries = metadata.artifacts.filter(item => item?.edition === edition);
    const expected = expectedArtifact(edition, release);
    requireValue(entries.length === 1 && Object.entries(expected).every(([key, value]) => entries[0][key] === value)
      && typeof entries[0].sha256 === 'string' && /^[0-9a-f]{64}$/.test(entries[0].sha256), 'APK artifact metadata has an inconsistent edition.');
  }
}

export function validateApkBadging(badging, edition, release = '150') {
  const expected = expectedArtifact(edition, release);
  const name = badging.match(/^package: name='([^']+)'/m)?.[1];
  const versionCode = Number(badging.match(/^package:.*\bversionCode='([0-9]+)'/m)?.[1]);
  const versionName = badging.match(/^package:.*\bversionName='([^']+)'/m)?.[1];
  requireValue(name === PACKAGE && versionCode === expected.versionCode && versionName === expected.versionName, 'Packaged APK identity/version does not match its metadata.');
  const abiLine = badging.match(/^native-code:\s*(.*)$/m)?.[1];
  requireValue(abiLine?.trim() === "'arm64-v8a'", 'Packaged APK must contain arm64-v8a native libraries only.');
  if (RELEASES[release].publicOnly) requireValue(!/^application-debuggable(?:\s|$)/m.test(badging), 'A debug APK is not a public test candidate.');
}

/** Inspect the actual merged binary manifest, not a source placeholder or another metadata node. */
export function validatePackagedPublicEdition(xmlTree) {
  const entries = [];
  let node = null;
  const finish = () => { if (node) entries.push(node.lines.join('\n')); node = null; };
  for (const line of xmlTree.split(/\r?\n/)) {
    const element = /^(\s*)E:\s/.exec(line);
    if (node && element && element[1].length <= node.indent) finish();
    if (/^\s*E: meta-data\b/.test(line)) node = { indent: element[1].length, lines: [] };
    else if (node && /^\s*A: /.test(line) && /^\s*/.exec(line)[0].length === node.indent + 2) node.lines.push(line);
  }
  finish();
  const editionNodes = entries.filter(entry => /A: android:name(?:\([^)]*\))?="org\.lunagram\.edition"(?:\s|$)/.test(entry));
  requireValue(editionNodes.length === 1, 'Packaged edition declaration is missing or duplicated.');
  const values = [...editionNodes[0].matchAll(/A: android:value(?:\([^)]*\))?="([^"]*)"(?:\s|$)/g)];
  requireValue(values.length === 1 && values[0][1] === 'public', 'Packaged edition is not public.');
}

export function validateCompiledPublicFlags(javapOutput) {
  // javap is captured in memory only: adjacent fields may contain API credentials.
  validateGeneratedFlag(javapOutput, 'public', '206');
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
  validateGeneratedFlag(fs.readFileSync(buildConfig, 'utf8'), args.edition, args.release);
  const badging = spawnSync(args.aapt, ['dump', 'badging', sourceApk], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  requireValue(!badging.error && badging.status === 0, 'Android APK inspection failed.');
  validateApkBadging(badging.stdout, args.edition, args.release);
  let verification;
  if (RELEASES[args.release].publicOnly) {
    requireValue(typeof args.javap === 'string' && args.javap.length > 0, 'The JDK bytecode inspector is required.');
    const library = path.join(repo, 'TMessagesProj/build/intermediates/compile_library_classes_jar/standalone/bundleLibCompileToJarStandalone/classes.jar');
    requireValue(fs.statSync(library).isFile(), 'Compiled Telegram library is unavailable.');
    const compiled = spawnSync(args.javap, ['-constants', '-public', '-classpath', library, 'org.telegram.messenger.BuildConfig'], { encoding: 'utf8', maxBuffer: 1024 * 1024 });
    requireValue(!compiled.error && compiled.status === 0, 'Compiled edition inspection failed.');
    validateCompiledPublicFlags(compiled.stdout);
    const manifest = spawnSync(args.aapt, ['dump', 'xmltree', sourceApk, 'AndroidManifest.xml'], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
    requireValue(!manifest.error && manifest.status === 0, 'Packaged edition inspection failed.');
    validatePackagedPublicEdition(manifest.stdout);
    verification = { compiledLibraryEdition: 'public', packagedEdition: 'public', debuggable: false };
  }
  const revision = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' });
  const commit = revision.stdout?.trim();
  requireValue(revision.status === 0 && /^[0-9a-f]{40}$/.test(commit), 'Source commit is unavailable.');
  requireValue(commit === process.env.GITHUB_SHA, 'Source commit differs from the workflow commit.');
  const editions = expectedEditions(args.release, args['include-friends'] === true);
  const editionIndex = editions.indexOf(args.edition);
  requireValue(editionIndex >= 0, 'This edition is not selected for the APK set.');
  const metadataPath = path.join(directory, 'build-metadata.json');
  const separatePublicEdition = RELEASES[args.release].separatePublicEdition === true;
  let metadata = { schemaVersion: separatePublicEdition ? 2 : 1,
    ...(separatePublicEdition ? { release: args.release } : {}),
    ...(verification ? { temporarySigning: true, productionSigningRequired: true, verification } : {}),
    sourceCommit: commit, packageName: PACKAGE, architecture: 'arm64-v8a', artifacts: [] };
  if (fs.existsSync(metadataPath)) {
    metadata = JSON.parse(fs.readFileSync(metadataPath, 'utf8'));
    requireValue(editionIndex > 0, 'The first edition cannot append to an existing APK set.');
    validateArtifactMetadata(metadata, args.release, commit, editions.slice(0, editionIndex));
  } else {
    requireValue(editionIndex === 0, 'Full APK must be collected before other editions.');
  }
  const destination = safeArtifactPath(directory, expected.fileName);
  fs.copyFileSync(sourceApk, destination, fs.constants.COPYFILE_EXCL);
  const hash = await sha256(destination);
  metadata.artifacts.push({ ...expected, sha256: hash });
  fs.writeFileSync(metadataPath, JSON.stringify(metadata, null, 2) + '\n', { mode: 0o600 });
  console.log(`Collected ${args.edition} APK; public SHA-256 ${hash}.`);
}

async function verifyPair(directory, release, includeFriends) {
  const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'build-metadata.json'), 'utf8'));
  const editions = expectedEditions(release, includeFriends);
  validateArtifactMetadata(metadata, release, process.env.GITHUB_SHA, editions);
  for (const edition of editions) {
    const entry = metadata.artifacts.find(item => item.edition === edition);
    const expected = expectedArtifact(edition, release);
    requireValue(entry && Object.entries(expected).every(([key, value]) => entry[key] === value), 'APK pair has an inconsistent edition.');
    requireValue(/^[0-9a-f]{64}$/.test(entry.sha256)
      && await sha256(safeArtifactPath(directory, entry.fileName)) === entry.sha256, 'Artifact SHA-256 does not match.');
  }
  console.log(`Verified ${editions.join('/')} APK set and non-secret provenance.`);
}

async function main() {
  const args = {};
  for (let i = 2; i < process.argv.length; i++) {
    const key = process.argv[i];
    requireValue(key.startsWith('--'), 'Unexpected collector argument.');
    if (key === '--verify-pair' || key === '--include-friends') args[key.slice(2)] = true;
    else args[key.slice(2)] = process.argv[++i];
  }
  args.release ??= '150';
  requireValue(typeof args['artifact-dir'] === 'string', 'Artifact directory is required.');
  if (args['verify-pair']) return verifyPair(path.resolve(args['artifact-dir']), args.release, args['include-friends'] === true);
  requireValue(typeof args['repo-root'] === 'string' && typeof args.aapt === 'string', 'Repository and Android inspection tool are required.');
  return collect(args);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
