# Releasing Syncr

Syncr has one version: `VERSION` at the repo root (`MAJOR.MINOR.PATCH`). Both apps derive their compared build number from it, so the Mac, Android and update feeds always agree:

```
build = MAJOR × 1,000,000 + MINOR × 1,000 + PATCH        0.2.0 → 2000
```

| Field | From `0.2.0` |
|---|---|
| Mac `CFBundleShortVersionString` / Android `versionName` | `0.2.0` |
| Mac `CFBundleVersion` / Android `versionCode` | `2000` |
| Git tag | `v0.2.0` |

`code/mac/bundle.sh` stamps the Mac values into the bundle before signing. `code/android/app/build.gradle.kts` reads `VERSION` directly. Each part must stay below 1000. Once a build with an updater ships, the version may only go up.

```sh
sh scripts/version.sh version        # 0.2.0
sh scripts/version.sh build          # 2000
sh scripts/version.sh check-tag v0.2.0
```

## Cut a release

1. Bump `VERSION`, commit, and push to `main`.
2. Tag that commit and push the tag:
   ```sh
   git tag "$(sh scripts/version.sh tag)"
   git push origin "$(sh scripts/version.sh tag)"
   ```
3. `.github/workflows/release.yml` checks the tag matches `VERSION`, builds the Mac app (macOS runner) and the Android APK (Linux runner), then uploads both to a **draft** GitHub Release. Drafts are visible only to people with write access, and never to update checks.
4. Inspect the draft on GitHub (assets, notes), then publish it by hand.

A push to `main` alone never releases. Re-running the workflow for the same tag replaces the draft's assets. It refuses to touch a release that is already published.

**Dry run:** Actions → Release → *Run workflow* (or `gh workflow run release.yml --ref <branch>`) builds both artifacts as downloadable workflow artifacts and creates no release.

## Current limits (phase 1)

- **Unsigned.** The Mac app has an ad-hoc signature, so other Macs need right-click → Open. The Android APK is unsigned and **cannot be installed** until release signing is added.
- **No self-update yet.** Sparkle (Mac) and the Android update path come after signing.

Build a release-config Mac bundle locally:

```sh
sh code/mac/prepare.sh
swift build -c release --package-path code/mac --product SyncrMac
SYNCR_BUILD_CONFIG=release sh code/mac/bundle.sh /tmp/Syncr-release.app
```

`SYNCR_SIGN_IDENTITY` (default `-`, ad hoc) selects the signing identity for when a Developer ID is available.
