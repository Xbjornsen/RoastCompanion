# Releasing RoastCompanion

## How a release works

1. Bump the fallback version in `app/build.gradle.kts` (`appVersionName` /
   `appVersionCode`) so local builds match — optional, CI overrides from the tag.
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

- The debug build installed over adb is signed with the **debug** key. The
  first release install won't go over it — uninstall RoastCompanion once
  (export history to CSV first!), then install the release APK. After that,
  in-app updates work seamlessly.
- Android will ask once to allow RoastCompanion to install apps
  ("Install unknown apps") — approve it.
