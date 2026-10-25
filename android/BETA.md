# Android beta signing and installation

Radio Guaka uses the Android application ID `org.guaka.radio`. Android accepts an APK update only when it has the same application ID and is signed by the same certificate as the installed app. Keep one beta signing identity for every beta build. Its public SHA-256 fingerprint is pinned in `beta-certificate.sha256`; release builds reject a different certificate. Version codes must increase for each update; this build defaults to version code `4` and version name `0.4.0`.

## Create and protect the signing identity

This workspace already has a private signing identity at `~/.local/share/radio-guaka/signing/radio-guaka-beta.jks`; reuse and securely back it up. Do not generate another key for this beta channel. For a separate new installation channel, create a JKS keystore once on a trusted computer and establish its own public certificate pin. Use a unique, strong store password and key password, and record the alias and both passwords in a password manager. The command prompts for passwords so they do not appear in shell history or process listings:

```sh
mkdir -p "$HOME/.local/share/radio-guaka/signing"
chmod 700 "$HOME/.local/share/radio-guaka/signing"
keytool -genkeypair -v \
  -keystore "$HOME/.local/share/radio-guaka/signing/radio-guaka-beta.jks" \
  -storetype JKS -keyalg RSA -keysize 3072 -validity 10000 \
  -alias radio-guaka-beta
chmod 600 "$HOME/.local/share/radio-guaka/signing/radio-guaka-beta.jks"
```

Make a second encrypted backup of the keystore and verify that it can be restored. Store passwords separately from the keystore. Losing the keystore or either password prevents future updates to existing beta installs. Never put the keystore or password files in the repository, a build artifact, or an unencrypted shared drive.

For the local build helper, create two private one-line files named `store-password` and `key-password` in that directory, each mode `600`. The helper reads their first lines without displaying them. Build with:

```sh
cd android
RADIO_BETA_KEYSTORE="$HOME/.local/share/radio-guaka/signing/radio-guaka-beta.jks" \
RADIO_BETA_KEY_ALIAS=radio-guaka-beta \
RADIO_BETA_STORE_PASSWORD_FILE="$HOME/.local/share/radio-guaka/signing/store-password" \
RADIO_BETA_KEY_PASSWORD_FILE="$HOME/.local/share/radio-guaka/signing/key-password" \
bash ./scripts/build-beta.sh
```

The same values can be supplied as `RADIO_BETA_STORE_PASSWORD` and `RADIO_BETA_KEY_PASSWORD` environment variables. Do not put passwords in Gradle command-line properties, which can be visible in process lists and build logs. Release builds fail if any signing value is missing or the keystore path is invalid; there is no debug-signing fallback.

To build a later beta, increase the version code and set a matching version name:

```sh
bash ./scripts/build-beta.sh
```

The release APK is written to `app/build/outputs/apk/release/app-release.apk`. Verify its signature and fingerprint before distributing it:

```sh
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

Record the certificate SHA-256 fingerprint with the beta. Compare it with the fingerprint of later APKs before installing them. Keep beta APKs and fingerprints available to testers over a channel you control.

## Install and update

Enable Android's install-from-this-source permission for the file manager or browser used to open the APK. The first installation can use `adb install app-release.apk`. An update can use `adb install -r app-release.apk` or Android's package installer. Keep the same signing certificate and use a higher version code for every update. An in-place update preserves app data, including preferences and Android Keystore-backed credentials.

The debug CI APK is signed with a development key, so it cannot update an installation signed with the beta key (and a beta APK cannot update a debug install). If Android reports a signature conflict, uninstall the old app before changing signing channels. Uninstalling deletes app data; Plex and Music Assistant credentials will need to be entered again. Back up any information you need before uninstalling.

## Troubleshooting

- **“App not installed” or signature mismatch:** confirm the application ID and certificate fingerprint, and install an APK signed by the same beta key. Switching between debug and beta requires uninstalling the existing app.
- **Version downgrade:** Android rejects APKs with a lower version code. Increase `betaVersionCode` for the next build.
- **Wrong or unreadable keystore:** check `RADIO_BETA_KEYSTORE`, file permissions, alias, and passwords. Do not replace the established keystore to work around an error.
- **Credentials need sign-in again after an update:** confirm the install was an in-place update and used the beta signing certificate. Uninstalling clears app data and its Android Keystore encryption key.
- **Lost signing identity:** restore the original keystore from its protected backup. A newly generated key cannot update existing installations.

## Settings migration

The first beta registers preference schema 1 without renaming or rewriting prototype settings, favorites, saved queues, or encrypted credentials. It preserves the Android Keystore alias. Later in-place beta updates keep this data; a schema marker from a newer app is never downgraded. Migration is tested for idempotence and data preservation. This cannot transfer data across a debug/beta signature change or an uninstall.

## Optional future signed CI

A signed CI workflow is **not included**. The current GitHub workflow continues to produce debug APKs. Do not configure signing secrets until a reviewed signed-artifact workflow and its destination have been explicitly authorized.

Proposed CI secret names

For a manually triggered signed CI build, configure these repository or environment secrets only after choosing who can trigger the workflow and download its artifacts:

- `RADIO_BETA_KEYSTORE_BASE64`: base64 encoding of the JKS keystore.
- `RADIO_BETA_STORE_PASSWORD`: keystore password.
- `RADIO_BETA_KEY_ALIAS`: `radio-guaka-beta`.
- `RADIO_BETA_KEY_PASSWORD`: private key password.

Use an environment with required reviewers if release access needs approval. GitHub secrets are separate from the local signing files, and setting them does not create or back up the signing identity. The workflow must write the decoded keystore only to a temporary runner directory, restrict its file permissions, avoid caching it, remove it after the job, and publish only the signed APK and public certificate fingerprint.


## Recorded validation

On 2026-10-24, a local 0.4.0 beta (version code 4) was signed and verified, then updated in place on an API 36 emulator using a same-certificate test build with version code 5. Android retained the UID and original install time; the favorite and endpoint setting remained, and the updated app successfully authenticated to a local fake MA endpoint with the encrypted dummy token saved before the update. Missing signing inputs and a different certificate are rejected. Fifteen JVM tests and eight instrumentation tests passed. Actual Pixel/car/Plex/speaker acceptance and an encrypted off-device backup of the signing identity remain separate user tasks.

APK versions default to `0.4.YYYYMMDD-HHmm` in Europe/Lisbon time (for example `0.4.20261025-1734`). The numeric Android version code defaults to Unix epoch minutes, which upgrades earlier sequential codes such as 11. Builds in the same minute share a version code. Explicit `-PbetaVersionName=...` and `-PbetaVersionCode=...` arguments still override the defaults for reproducible rebuilds.
