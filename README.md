# PGRazor Updater

Android updater and APK cloner for the original `jpitty03/PGRazor` release channel.

## Features in this revision

- Checks the latest published PGRazor GitHub release on launch.
- Downloads the `PGRazor.apk` asset through Android DownloadManager.
- Verifies the asset SHA-256 digest when GitHub publishes one.
- Saves the requested clone package name and keystore selection.
- Encrypts the keystore and optional private-key passwords with Android Keystore (AES-GCM).
- Decodes the APK manifest/resources with the checksum-pinned APKEditor 1.4.9 release.
- Changes the manifest package name and updates custom permission names (including every component `permission`/`readPermission`/`writePermission` that references them) and provider authorities (including `@string` authorities in every `values*` resource file).
- Preserves relative component class names against the original package namespace.
- Rebuilds the APK, signs it with the selected JKS/PKCS12/BKS private key using apksig, and verifies the resulting signature. JKS is read by a built-in parser because Android has no JKS provider.
- Opens Android's package installer using a scoped FileProvider URI.
- After initial configuration, checks for a newer release on every launch and automatically downloads, clones, signs, and presents the installer when a new release is available.

## Build

GitHub Actions runs the unit tests and builds a release APK on pushes to `main` and manual workflow dispatch, then publishes it as a GitHub Release asset (`PGRazor-Updater-v0.0.N.apk`). The workflow downloads the official APKEditor 1.4.9 jar, verifies its SHA-256, bundles it into the app, and builds with Android SDK 35 / JDK 17.

**Stable signing key (recommended):** Android only installs an update over an existing app when both are signed with the same key. Without a key the build falls back to a per-runner debug key, so each build must be uninstalled before the next is installed. To fix that, create a keystore and add these repository secrets: `SIGNING_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

**Local build:** run `scripts/fetch-apkeditor.sh`, then `gradle testDebugUnitTest assembleDebug`.

## Use

1. Install PGRazor Updater.
2. Enter a unique lowercase package name, such as `com.example.pgrazor`.
3. Import your `.jks`, `.keystore` or `.p12` file, enter the alias and keystore password, and optionally enter a different private-key password. The file is copied into the app's private storage. If a PKCS12 file created with a recent JDK is rejected, convert it: `keytool -importkeystore -srckeystore old.p12 -destkeystore new.jks -deststoretype JKS`.
4. Save settings. The app checks the latest release, downloads it, clones it, signs it, and opens Android's installer.
5. Approve installation. Android always requires your confirmation.

On later launches, a new PGRazor release is processed automatically. The manual download and clone buttons remain available.

The app does not upload your keystore or password. The downloaded original APK remains unchanged.

## Important compatibility warning

PGRazor intentionally keeps its Android package identity compatible with Pokémon GO. A cloned package may break authentication callbacks, external intents, native/game integration, or other package-specific behavior. This engine performs the general manifest/permission/provider cloning workflow; it does not rewrite arbitrary package-name strings in DEX/native libraries. Test the clone independently and keep the original installation.

## Build/test status

The keystore loading, string rewriting and the full decode, rebuild and sign pipeline are covered by unit tests and were exercised end to end on a desktop JVM against a real APK (output verified with `apksigner`, `aapt2` and `zipalign`). An on-device clone/install test is still required before treating this as a validated release, and a successful clone does not prove that PGRazor functions correctly under a renamed package. See `docs/CLONING-ENGINE.md` and `THIRD_PARTY.md`.

## Release source

https://github.com/jpitty03/PGRazor/releases
