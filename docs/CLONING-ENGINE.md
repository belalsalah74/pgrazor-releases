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

## Implementation in this repository

The current source includes an APKEditor-based transformation pipeline. It decodes the binary manifest/resources while retaining raw DEX files, normalizes relative component class names against the original package, changes the manifest package, updates declared custom permissions and provider authorities (including string resources), rebuilds the APK, signs with the selected JKS/PKCS12 key using Android apksig, and verifies the resulting signature.

The app automatically checks the release on launch. After a package name and signing key are configured, a newly published release is downloaded, digest-checked when GitHub provides an asset digest, cloned, signed, and handed to Android's installer. Android still requires the user's confirmation.

**Not yet validated:** the first GitHub Actions build and an on-device PGRazor clone/install. This is especially important because PGRazor keeps the game-compatible package identity; changing it may break game/native integrations. The clone engine does not rewrite arbitrary package-name strings in DEX or native libraries.
