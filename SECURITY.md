# Security Policy

## Supported versions

Only the newest LumaGram release is supported with security fixes.

## Verifying releases

Release APKs are signed with the same private LumaGram signing key. Android blocks an update signed by any other key. The in-app updater additionally verifies the SHA-256 checksum, package name and version before opening the system installer.

The private signing key and its passwords are never stored in this repository.

Official signing-certificate SHA-256 fingerprint:

```text
24a3777b3b0b2d353b0452aa166660f5ad39e0f50aa2124d95b85978880c7cd9
```

## Reporting a vulnerability

Do not publish an exploitable vulnerability in a public issue. Use GitHub's [private vulnerability reporting](https://github.com/sergeevdeonisii-dotcom/LumaGram-Android/security/advisories/new), include the affected version and clear reproduction steps, and allow time for a fix before public disclosure.

Never include Telegram login codes, passwords, session files, API hashes or signing keys in a report.
