# Folder PDF Automator

A native Android Kotlin/Jetpack Compose app that scans immediate subfolders of a SAF-selected parent directory, natural-sorts JPG/JPEG/PNG/WEBP files, and creates one A4 PDF per folder sequentially.

## Build APK using GitHub Actions
1. Create a new GitHub repository.
2. Upload/extract all files in this project into the repository root.
3. Open **Actions** → **Build Android APK** → **Run workflow** (or push to `main`).
4. Download `Folder-PDF-Automator-debug-apk` from the workflow artifacts.
5. Install the APK on your Samsung Galaxy A16 5G. Allow installation from your browser/file manager if prompted.

## Build locally
Install Android Studio with Android SDK Platform 35 and JDK 17, open this project, then run:
```bash
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Current implementation
- Android Storage Access Framework parent/output folder selection
- Immediate subfolder scan; hidden folders ignored
- JPG/JPEG/PNG/WEBP filtering
- Natural numerical filename sorting
- Sequential one-PDF-per-folder generation with Android `PdfDocument`
- A4 page, white background, image centered and aspect-ratio preserved
- Basic progress, pause/resume/cancel controls and existing-PDF skip behavior

## Important limitations / next development steps
This is a buildable MVP, not the full production feature list from the specification. Pause/cancel are best-effort; persistent resume after process death, WorkManager/foreground notification, recursive scan, manual folder selection, retry management, image orientation/EXIF correction, custom page settings, error report export, and UI polish are not yet implemented. Android's PDF drawing may rasterize/encode images, so do not promise bit-for-bit lossless PDF output. Test with a small batch first.
