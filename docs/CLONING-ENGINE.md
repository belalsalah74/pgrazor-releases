# APK cloning design and compatibility notes

## Target workflow

1. Resolve the latest non-draft release from `jpitty03/PGRazor`.
2. Download the exact `PGRazor.apk` release asset and verify its SHA-256 digest.
3. Inspect the APK before modifying it: package ID, version, manifest, providers, custom permissions, DEX files, native libraries, and signing certificate.
4. Apply the requested package rename using a full APK resource/manifest rebuild, not a ZIP rename or a manifest-only byte edit.
5. Update custom permission names and provider authorities by default. Keep these as explicit options because some apps rely on stable external authorities.
6. Rebuild, zip-align, sign with the user's imported keystore, and verify the output package and signature.
7. Hand the output to Android Package Installer. Never silently replace the original APK.

## Reference behavior

Morphe's public universal **Clone app** patch documents the same general behavior: change the package name, update custom permissions, and update provider authorities. Its source also warns that cloning is not compatible with every app and can cause crashes. See:

- https://github.com/MorpheApp/morphe-patches
- https://github.com/MorpheApp/morphe-patches/blob/main/patches/src/main/kotlin/app/morphe/patches/all/misc/clone/CloneAppPatch.kt
- https://github.com/MorpheApp/morphe-patcher

This project is not Morphe and must not use Morphe branding. Any code copied or adapted from GPL-licensed Morphe sources must preserve the required notices and comply with the applicable GPL terms. Prefer an independently implemented compatible workflow unless a deliberate GPL adoption is made.

## PGRazor-specific risk

PGRazor's own installation documentation says its Android package identity is kept compatible with Pokémon GO. A clone package may therefore affect code paths that expect the original package ID, including sign-in callbacks, providers, native/game integration, and external intents. The original release must remain untouched, and the clone must be tested on-device before it is considered usable.

Source: https://github.com/jpitty03/PGRazor

## Current implementation status

The Android app currently performs release lookup, APK download, SHA-256 verification (when the GitHub asset digest is available), local configuration storage, and installation handoff.

The actual APK transformation and signing pipeline is **not implemented yet**. The package name and keystore inputs are configuration only. Do not describe this revision as an APK cloner.
