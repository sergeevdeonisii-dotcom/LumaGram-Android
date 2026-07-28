# LumaGram for Android

LumaGram is an open-source Telegram client for Android based on the official
[Telegram Android source code](https://github.com/DrKLO/Telegram). It keeps Telegram's protocol and core behavior while adding Luma's visual features, including Liquid Glass, the FullBlack theme and interface refinements.

This repository is public so anyone can inspect what the client does, build it independently and compare release hashes. LumaGram is an independent project and is not affiliated with Telegram FZ-LLC.

## Download safely

Use only APK files attached to [this repository's GitHub Releases](https://github.com/sergeevdeonisii-dotcom/LumaGram-Android/releases) or mirrored by the official LumaGram Telegram channel. Published releases are immutable on GitHub. Every release must publish:

- the exact APK file;
- its SHA-256 checksum;
- the version and changelog;
- the Git commit used for the build.

The in-app updater downloads only over HTTPS and rejects an APK unless all of these checks pass:

1. SHA-256 matches `updates/latest.json`;
2. Android package name is `org.luma.liquid.web`;
3. version code matches the manifest and is newer;
4. signing certificate matches the already installed LumaGram app.

The release signing key is deliberately **not** stored in GitHub. Publishing it would let anyone sign a fake update that Android considers genuine.

Official LumaGram signing-certificate SHA-256 fingerprint:

```text
24a3777b3b0b2d353b0452aa166660f5ad39e0f50aa2124d95b85978880c7cd9
```

To check an APK manually on Windows:

```powershell
Get-FileHash .\LumaGram.apk -Algorithm SHA256
```

To inspect its signing certificate with Android SDK Build Tools:

```powershell
apksigner verify --verbose --print-certs .\LumaGram.apk
```

## Build from source

Requirements:

- JDK 17;
- Android SDK 35 and NDK required by the Telegram project;
- your own Telegram API ID and API hash from <https://my.telegram.org>.

Create `local-luma.properties` in the repository root (it is ignored by Git):

```properties
API_ID=123456
API_HASH=your_api_hash
```

Build the standalone arm64 APK:

```powershell
.\gradlew.bat :TMessagesProj_AppStandalone:assembleAfatStandalone --console=plain
```

The APK is written under:

```text
TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/
```

For a locally signed build, create the ignored file
`local-signing/luma-signing.properties`:

```properties
storeFile=C:/absolute/path/to/your-release-key.jks
storePassword=change_me
keyAlias=your_alias
keyPassword=change_me
```

Never publish your `.jks` file or these passwords.

## Publishing an update

1. Increase `APP_VERSION_CODE` and `APP_VERSION_NAME` in `gradle.properties`.
2. Build and sign the APK with the same LumaGram release key.
3. Create a GitHub Release and attach the APK.
4. Generate the manifest:

```powershell
.\tools\make-update-manifest.ps1 `
  -ApkPath .\LumaGram.apk `
  -Version 12.9.0-luma.25 `
  -VersionCode 69879 `
  -ApkUrl https://github.com/sergeevdeonisii-dotcom/LumaGram-Android/releases/download/v12.9.0-luma.25/LumaGram.apk `
  -Changelog "Bug fixes and visual improvements."
```

5. Commit the generated `updates/latest.json` to the default branch.

Installed clients will show a Telegram-style card above the chat list. It opens the changelog, displays in-app download progress, verifies the APK and then opens Android's normal update installer.

## Privacy and security

LumaGram does not need access to your Telegram login code outside the normal Telegram authorization screen. Never send login codes, two-factor passwords or release signing keys to anyone.

GitHub secret scanning with push protection, Dependabot security updates, CodeQL and immutable releases are enabled for the official repository.

Please report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## License

The project is distributed under the GNU General Public License v2 or later, matching the upstream Telegram Android source. See [LICENSE](LICENSE).
