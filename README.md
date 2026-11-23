# Nomi Chat (Android)

Fast, smart, and secure Jetpack Compose messaging app backed by Firebase Auth, Cloud Firestore, and Firebase AI Logic.

## Direct APK Download (Chrome)

<p>
  <a href="./download/NomiChat.apk?raw=1" style="color: #1a73e8; font-weight: 600; text-decoration: underline;">
    Download NomiChat.apk directly
  </a>
</p>

- **APK file:** [`download/NomiChat.apk`](./download/NomiChat.apk?raw=1)
- **HTML download page:** [`download/index.html`](./download/index.html)

## Automated GitHub Actions APK Build & Release

Every push to `main` or `master` runs [`.github/workflows/build-apk.yml`](./.github/workflows/build-apk.yml), which:
1. Decodes `debug.keystore.base64` to `debug.keystore` and prepares `.env` from `.env.example`.
2. Builds the Android APK (`gradle :app:exportApkToDownload`) into `download/NomiChat.apk`.
3. Uploads `NomiChat.apk` as a build artifact and attaches it to the `latest` GitHub Release (`releases/latest/download/NomiChat.apk`).
