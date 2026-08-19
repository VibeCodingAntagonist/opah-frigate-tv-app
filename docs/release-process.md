# Release process

> This page is for the repository owner and release maintainers. It is not part
> of normal installation or use. If you only want to install or update Opah,
> follow the [installation guide](installation.md).

Only a designated release maintainer should perform these steps. Public
releases use version labels such as `v0.2.0`.

## One-time signing setup

Create the permanent Android release key on a trusted, backed-up system. Losing
this key prevents existing users from installing future updates under the same
application ID. Never use the debug key and never commit the keystore or secrets.

Configure these GitHub Actions secrets:

| Secret | Value |
| --- | --- |
| `OPAH_RELEASE_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `OPAH_RELEASE_STORE_PASSWORD` | Keystore password |
| `OPAH_RELEASE_KEY_ALIAS` | Signing key alias |
| `OPAH_RELEASE_KEY_PASSWORD` | Key password |
| `OPAH_RELEASE_CERT_SHA256` | SHA-256 fingerprint of the signing certificate |

Store an encrypted offline backup of the keystore, credentials, certificate,
and recovery instructions separately from GitHub. Restrict repository release
and secret-management permissions.

The permanent `app.opah.tv` release certificate uses RSA-3072 and
SHA256withRSA. Its SHA-256 fingerprint is
`62:E1:20:A1:76:7F:B1:DE:C3:4B:7D:2B:97:FC:0A:60:E6:9C:D3:49:ED:6D:C5:84:CC:30:75:30:A6:A3:8E:EF`.
Verify this value independently whenever signing infrastructure is restored.

Stable Git tags use a separate GPG signing identity. Its public fingerprint is
`F1F6 F581 4931 9F21 B0D3 8AE2 6068 13A5 467E 0896`. Keep encrypted offline
backups of the private tag-signing key and Android signing identity; neither may
be committed or uploaded as a release asset. Verify the full GPG fingerprint
locally and in GitHub before publishing a tag.

The repository includes interactive helpers that keep passwords out of command
arguments, files, and shell history. From a trusted PowerShell session, create
the identity in an empty secure directory outside the source tree:

```powershell
.\tools\create-release-signing-identity.ps1 `
  -Destination 'D:\secure-offline\opah-release-signing' `
  -KeytoolPath 'C:\path\to\jdk-17\bin\keytool.exe'
```

Enter unique store and key passwords only at keytool's prompts and save them
directly in a password manager. The helper writes the keystore, public
certificate, and a non-secret identity/fingerprint record; it never stores a
password. Copy the signing directory to a separate encrypted offline backup and
verify both copies before publishing.

After authenticating GitHub CLI as the release owner, configure repository
secrets without exposing their values in command arguments or logs:

```powershell
.\tools\configure-github-release-secrets.ps1 `
  -KeystorePath 'D:\secure-offline\opah-release-signing\opah-release.jks' `
  -IdentityPath 'D:\secure-offline\opah-release-signing\opah-release-identity.txt'
```

## Prepare a release

1. Start from a clean public repository checkout with no uncommitted changes.
2. Confirm a source license is present, the permanent app and signing identity
   are fixed, and the current Android rules for apps distributed outside Google
   Play have been reviewed.
3. Update `CHANGELOG.md`, compatibility documentation, and the exact-version
   release notes at `docs/releases/vX.Y.Z.md`.
4. Run `tools/verify-public-tree.ps1`.
5. Run the full local gate:

   ```powershell
   .\gradlew.bat --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleCandidate
   ```

6. Review all changed files and ensure no private capture, config, address,
   hostname, credential, or signing material is present.
7. Commit the release preparation and let Android CI pass.

Before creating the first tag or after changing the signing setup, run the
`Publish signed APK` workflow manually with the intended version. This test run
checks the secrets, tests, code quality, package, version, publisher identity,
and checksum. It uploads a private seven-day test APK but does not create a
public release. Inspect that APK and checksum before tagging.

## Publish

Enable GitHub immutable releases before pushing the first stable tag. Once an
immutable release is published, its tag and assets cannot be replaced; a fix
requires a higher version.

Create and push an exact stable tag:

```text
git tag -s v0.2.0 -m "Opah 0.2.0"
git push origin v0.2.0
```

For a tag push, the release workflow:

1. checks the version tag and creates Android's numeric version;
2. runs automated tests and code-quality checks;
3. builds the smaller release APK using the protected GitHub secrets;
4. verifies the app name, version, and expected publisher identity;
5. names the artifact `opah-vX.Y.Z.apk`;
6. creates a SHA-256 checksum; and
7. requires matching reviewed release notes; then
8. publishes the APK, checksum, and notes in a GitHub release.

The final publication job is isolated from the build job and receives
`contents: write` only for a tag-triggered run. Manual rehearsals and the signing
job retain read-only repository access.

For a local PowerShell signing check, quote every Gradle `-P` argument (for
example, `'-Popah.requireReleaseSigning=true'`). PowerShell otherwise removes
the property-name prefix before `gradlew.bat` receives it. Bash does not require
this Windows-specific quoting.

Download the published files, verify them independently, install on a clean TV
profile, and test sign-in, Home, one H.264 camera, one H.265 camera when
available, Review playback, Birdseye, Settings, and sign-out.

Never retag or replace an already published APK. Fix a release with a higher
patch version.
