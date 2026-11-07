# PGRazor Updater

Android updater and APK cloner for the original `jpitty03/PGRazor` release channel.

## Features in this revision

- Checks the latest published PGRazor GitHub release on launch.
- Downloads the `PGRazor.apk` asset through Android DownloadManager.
- Verifies the asset SHA-256 digest when GitHub publishes one.
- Saves the requested clone package name and keystore selection.
- Encrypts the saved keystore password with Android Keystore (AES-GCM).
- Decodes the APK manifest/resources with the pinned APKEditor engine.
- Changes the manifest package name and updates custom permission names and provider authorities.
- Preserves relative component class names against the original package namespace.
- Rebuilds the APK, signs it with the selected JKS/PKCS12 private key using apksig, and verifies the resulting signature.
- Opens Android's package installer using a scoped FileProvider URI.

## Build

GitHub Actions builds a debug APK on pushes to `main` and manual workflow dispatch. The workflow builds APKEditor from pinned commit `5d94bc08565e30e3e3bb7accbd83138e4d6a8e45`, bundles it into the app, and builds with Android SDK 35 / JDK 17.

Download the `PGRazor-Updater-debug` artifact from the Actions run.

## Use

1. Install PGRazor Updater.
2. Open it and wait for the latest PGRazor release check.
3. Enter a unique lowercase package name, such as `com.example.pgrazor`.
4. Import your `.jks` or `.keystore` file and enter the alias and password.
5. Save settings.
6. Download the latest official PGRazor APK.
7. Tap **Clone & install downloaded APK** and approve Android's installer.

The app does not upload your keystore or password. The downloaded original APK remains unchanged.

## Important compatibility warning

PGRazor intentionally keeps its Android package identity compatible with Pokémon GO. A cloned package may break authentication callbacks, external intents, native/game integration, or other package-specific behavior. This engine performs the general manifest/permission/provider cloning workflow; it does not rewrite arbitrary package-name strings in DEX/native libraries. Test the clone independently and keep the original installation.

## Build/test status

The source and CI build pipeline are committed. The first CI build and on-device clone test are still required before treating this as a validated release. A successful compilation alone does not prove that PGRazor functions correctly under a renamed package.

## Release source

https://github.com/jpitty03/PGRazor/releases
