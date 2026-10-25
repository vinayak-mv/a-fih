# a-Fih

A personal, server-free media library built with Kotlin Multiplatform and Compose.
Windows first, Android second. Telegram MTProto bot accounts are the v1 provider.

The agreed product requirements are in [the v1 spec](docs/v1-spec.md).

## Current implementation

This is the first foundation increment, not a working streaming release.

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

Provider configurations are saved locally but are **not yet authenticated**.
No Telegram native dependency is bundled yet. Source assignment, the media index,
update recovery, metadata, playback, and downloads are not implemented. Empty
screens state this explicitly and do not contain fake media or success results.
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

The workflow has not been executed until a repository is connected and a run
completes. See [implementation status](docs/implementation-status.md) for checks.

## Optional local development

Requires a JDK 17 installation and internet access for Gradle dependencies.
The checked-in wrapper pins Gradle 8.14.3 and its distribution checksum.

```powershell
.\gradlew.bat :shared:jvmTest :desktopApp:test
.\gradlew.bat :desktopApp:run
.\gradlew.bat :desktopApp:createDistributable
```

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

Validate and integrate a distributable Telegram native client, authenticate a
real bot, retrieve an explicit source range into a transactional local media
index, and test reconnect/update recovery. Keep raw MTProto message IDs separate
from the chosen client's internal ID encoding. See the spec's acceptance gates
before connecting this scanner to live Telegram operations.
