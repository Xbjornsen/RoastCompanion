# Releasing RoastCompanion

## How a release works

1. Bump the fallback version in `app/build.gradle.kts` (`appVersionName` /
   `appVersionCode`) so local builds match — CI overrides from the tag, but the in-app updater on
   a debug build compares against this.
2. Commit, then tag and push:

   ```powershell
   git tag v1.1.0
   git push origin master v1.1.0
   ```

3. GitHub Actions (`.github/workflows/release.yml`) builds a **signed** release
   APK and publishes a GitHub Release with the APK attached.
4. On the phone: Settings → App → **Check for Updates** finds the new release,
   downloads the APK, and hands it to the Android installer.

Version code is derived from the tag: `major*10000 + minor*100 + patch`
(v1.2.3 → 10203), so codes always increase with semver.

## Signing

- Key: `release.jks` (repo root, **gitignored** — repo is public, never commit it).
- Local passwords: `keystore.properties` (also gitignored).
- CI: GitHub secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD` (already set).
- **Back up `release.jks` + `keystore.properties` somewhere safe.** If the key
  is lost, phones can't update without uninstalling first.

## One-time gotchas on the phone

- Android Studio (debug) builds install as a **separate app**, "RoastCompanion
  (debug)" (`com.roastcompanion.debug`, version shown as `x.y.z-dev`), so they never
  clash with the release install. Builds from before v1.8.0 used the release package
  id with the debug key: if a phone still has one of those (it shows 1.7.3), uninstall
  it once — export history to CSV first, untick "Keep app data" — then install the
  release APK.
- Android will ask once to allow RoastCompanion to install apps
  ("Install unknown apps") — approve it.
