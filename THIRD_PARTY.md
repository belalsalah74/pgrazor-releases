# Third-party components

## APKEditor

- Project: https://github.com/REAndroid/APKEditor
- Pinned release: `V1.4.9` (`APKEditor-1.4.9.jar`, SHA-256 `a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67`)
- License: Apache License 2.0; see `LICENSES/APACHE-2.0.txt`.
- The CI workflow downloads the official release jar, verifies the SHA-256 above and bundles it into the updater.

## Android apksig

- Artifact: `com.android.tools.build:apksig:8.7.3`
- Project: https://android.googlesource.com/platform/tools/apksig
- License: Apache License 2.0; see `LICENSES/APACHE-2.0.txt`.

## AndroidX Core / FileProvider

- Artifact: `androidx.core:core:1.15.0`
- Project: https://developer.android.com/jetpack/androidx
- License: Apache License 2.0; see `LICENSES/APACHE-2.0.txt`.

The APK cloning workflow in this repository is independently implemented. Morphe is referenced only as a behavioral comparison; no Morphe source code is copied or bundled.
