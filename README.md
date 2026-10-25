# a-Fih

A personal, server-free media library built with Kotlin Multiplatform and Compose.
Windows first, Android second. Telegram MTProto bot accounts are the v1 provider.

The agreed product requirements are in [the v1 spec](docs/v1-spec.md).

## Current implementation

This is the Telegram import increment (0.2.0), not a working streaming release.

- Compose desktop shell following the prototype's light/red visual direction.
- Add, edit, and remove Telegram provider configurations and media libraries.
- API ID/hash/token validation, masked fields, redacted credential diagnostics.
- Windows DPAPI-protected configuration with atomic replacement and preserved
  unreadable files. Data lives at `%LOCALAPPDATA%\a-Fih\configuration.dpapi`.
- Shared Telegram message-link parsing for public/private links and topic forms.
- Shared bounded range scanner that skips deleted/empty IDs without stopping,
  supports resumable checkpoints, and requires atomic per-batch persistence.
- Tests for gap handling, interruption/resume, topic filtering, source isolation,
  parsing, secret redaction, and Windows encrypted-storage round trips.
- GitHub Actions workflow for checks and a portable unsigned Windows app image.
- Real TDLib bot authentication and source access checks.
- Source setup with inclusive message ranges, whole-chat/topic scope, per-source
  parsing preferences, and association with multiple libraries.
- SQLite media index with transactional per-batch checkpoints and deduplication.
- Manual scan/pause/resume and browsing/searching imported files by library.
- CI builds and caches a pinned TDLib Windows DLL, bundles it with the app, and
  includes TDLib/OpenSSL/zlib license notices. Initial native builds take longer.

Use **Connect / check** on a provider to authenticate it. **Sources → Add source**
checks chat access, saves its configuration, and starts scanning. Open a library
to browse imported file titles. Editing a source's range or parsing preference
causes a fresh bounded scan; already imported media is retained and deduplicated.
Removing a source forgets its index associations without touching Telegram files.

Incoming updates, update recovery, scheduled scanning, metadata enrichment,
playback, and downloads are not implemented yet. Their screens do not contain
fake media or success results. Live bot access still needs testing with your own
credentials; compilation and fixture tests cannot establish Telegram permissions.
Android has not been enabled yet; shared domain code is in `commonMain` so that
its business logic can be reused when the Android target is added.

## Build in GitHub Actions

1. Create a public GitHub repository and push this directory to it.
2. The **Windows preview** workflow runs on pushes (including tags), pull
   requests, and **Run workflow** manual dispatch.
3. Download `a-Fih-windows-x64-preview-<run number>` from a successful run's
   artifacts. Extract the complete artifact and run `a-Fih.exe` inside the
   application directory. Keep its adjacent runtime and application files.

The app image includes Java; the testing computer does not need a JDK installed.
These are unsigned preview artifacts, not signed releases. No Telegram or
metadata credentials are needed in GitHub to build. Enter personal credentials
in the app on your Windows device. No workflow publishes a GitHub release.

The initial 0.1.0 build was successfully built and launched by the user. Each new
increment still requires its own successful workflow and live integration checks.
See [implementation status](docs/implementation-status.md) for verification.

## Optional local development

Requires a JDK 17 installation and internet access for Gradle dependencies.
The checked-in wrapper pins Gradle 8.14.3 and its distribution checksum.

```powershell
.\gradlew.bat :shared:jvmTest :desktopApp:test
.\gradlew.bat :desktopApp:run
.\gradlew.bat :desktopApp:createDistributable
```

For local Telegram development, put a built `tdjson.dll` at
`native/windows-x64/tdjson.dll`, or set `AFIH_TDJSON_PATH` to its absolute path.
The DLL is automatically bundled in GitHub Actions test artifacts. No credentials
are stored in this DLL. Native build revisions are pinned in the workflow.

The desktop app currently requires Windows because its secure store uses DPAPI.
Shared tests can run on another JVM platform using `bash ./gradlew :shared:jvmTest`.
The build deliberately does not require an Android SDK for the Windows milestone.

## Layout

```text
shared/       Platform-independent models, Telegram range parsing/scanning, tests
desktopApp/   Windows Compose UI, secure configuration persistence, packaging
docs/         Agreed v1 scope and implementation/validation status
.github/      Windows build, test, and artifact workflow
```

Technology versions are pinned to a compatible baseline: Kotlin 2.2.20,
Compose Multiplatform 1.9.1, Gradle 8.14.3, and Java 17. This is an intentional
baseline rather than a requirement to use the newest available versions.

## Next milestone

Validate a live range import with your bot and test reconnect/update recovery,
then implement incoming updates and scheduling. Native playback and downloads
follow. Raw MTProto message IDs remain separate from TDLib's internal encoding.
