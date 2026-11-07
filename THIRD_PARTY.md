# Third-party components

## APKEditor

- Project: https://github.com/REAndroid/APKEditor
- Pinned source commit: `5d94bc08565e30e3e3bb7accbd83138e4d6a8e45`
- License: Apache License 2.0; see `LICENSES/APACHE-2.0.txt`.
- The CI workflow builds APKEditor from source and bundles its executable classes into the updater.

## Android apksig

- Artifact: `com.android.tools.build:apksig:8.7.3`
- Project: https://android.googlesource.com/platform/tools/apksig
- License: Apache License 2.0; see `LICENSES/APACHE-2.0.txt`.

## AndroidX Core / FileProvider

- Artifact: `androidx.core:core:1.15.0`
- Project: https://developer.android.com/jetpack/androidx
- License: Apache License 2.0; see `LICENSES/APACHE-2.0.txt`.

The APK cloning workflow in this repository is independently implemented. Morphe is referenced only as a behavioral comparison; no Morphe source code is copied or bundled.