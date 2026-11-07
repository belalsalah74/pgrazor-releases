# PGRazor Updater

Android companion app for the original `jpitty03/PGRazor` release channel.

## Current status

This repository starts the Android updater project. The current app checks the latest GitHub release, downloads the `PGRazor.apk` asset, verifies its SHA-256 digest when GitHub publishes one, stores a package-name preference, and opens Android's package installer.

**APK cloning and re-signing are not implemented yet.** The package-name field does not modify the downloaded APK. The imported keystore is not yet used to sign output. Do not treat this build as a cloner.

## Build

GitHub Actions builds a debug APK on pushes to `main` and uploads it as a workflow artifact. The build targets Android 15 (API 35), with minimum API 26.

## Release source

`https://github.com/jpitty03/PGRazor/releases`

## Security

Keystore credentials must remain on-device. Never commit a keystore or passwords to this repository.