# Implementation status

## Telegram import increment (0.2.0)

The user confirmed that the original 0.1.0 Windows build and app launch succeeded.
The repository is now connected at `vinayak-mv/a-fih`.

New code in this increment:

- TDLib JSON transport, MTProto bot sign-in, identity/access checks, safe errors.
- Encrypted TDLib session databases with DPAPI-protected random database keys.
- Source setup/edit/removal, topic scope, per-source parsing, many-library links.
- SQLite media index with atomic per-batch checkpoints and deduplication.
- Manual scan/pause/resume and indexed file browsing/search in each library.
- Pinned native Windows TDLib/vcpkg build, caching, packaging, and license notices.
- Additional tests for codecs, database rollback/durability, configuration
  compatibility, and message ID conversion.

This code still requires its own successful CI run and live bot acceptance test.
No personal credentials are present in fixtures. Incoming update indexing,
reconnection recovery, scheduled scans, streaming, playback, downloads, metadata
enrichment, and Android remain pending. Imported files currently have no play or
download button. The library view shows up to 500 recent matching files.

## Foundation implemented

- [x] Agreed v1 specification recorded.
- [x] Kotlin Multiplatform shared JVM module and Windows Compose desktop module.
- [x] Provider and library configuration UI with add/edit/remove operations.
- [x] Current-user Windows DPAPI persistence; no plaintext fallback.
- [x] Bounded range scanning and public/private/topic link parser.
- [x] Automated scanner/parser tests and Windows secure-store test authored.
- [x] Windows CI tests and portable app-image packaging configured.
- [x] Official Gradle wrapper with checksum-pinned distribution.

## Verification

The development workspace initially has no Java, Kotlin compiler, or Gradle.
Do not interpret authored tests or a configured workflow as passing tests.
The user confirmed the baseline build and app startup through GitHub Actions.
New Kotlin tests, native compilation, and packaging run in the workflow; local
diff checks and PowerShell build-script syntax checks are also performed.

Completed local checks:

- Official Gradle wrapper JAR SHA-256 matches the checksum published by Gradle.
- Wrapper JAR ZIP integrity and launcher-class presence verified.
- Gradle 8.14.3 distribution SHA-256 recorded and pinned in wrapper properties.
- POSIX Gradle launcher passed `bash -n` syntax validation.
- Reviewed configuration diagnostics: credential values are masked/redacted and
  provider data is not printed to logs.

Pending for 0.2.0: Kotlin/native compilation, execution of the expanded test
suite, packaged startup, and live bot import checks. No YAML linter is installed.

## Next work

- [x] User-confirmed baseline Windows build and app launch.
- [x] Telegram transport/native packaging and encrypted session storage code.
- [x] Bot authentication/source access checks implemented; live validation pending.
- [x] Sources, library/source associations, topics, and per-source parsing options.
- [x] Transactional media index and resumable range checkpoints implemented.
- [x] Shared scanner wired into manual imports with pause/resume and safe errors.
- [ ] Validate with a live bot, deleted IDs, empty batches, and process restarts.
- [ ] Automatic retry/backoff and flood-wait scheduling (manual retry is available).
- [ ] Validate bot update recovery and stale cursors through chosen transport.
- [ ] Launch/manual/periodic scheduling and incoming media updates.
- [ ] Native player selection, seekable streaming, and real codec/track validation.
- [ ] Durable downloads, completed-download offline library, and offline playback.
- [ ] Metadata, file grouping, discovery, search, and personal watch state.
- [ ] Android target, platform implementations, UI adaptation, and build artifacts.

## Technical boundaries

The range scanner is a shared business-logic component with injected transport
and batch store, not a Telegram client implementation. `ScanBatchStore.commit`
must upsert records and save the contiguous checkpoint in one transaction.
Configuration DPAPI storage is for a small settings file. The media index is
SQLite at `%LOCALAPPDATA%/a-Fih/media.sqlite`. Native sessions live under the
adjacent `telegram/<provider ID>` directory and are encrypted separately.
The adapter uses raw MTProto IDs in the domain and shifts only at the TDLib
boundary, following the pinned TDLib `MessageId.h` layout.

The initial UI uses system fonts until licensed Manrope/Inter font assets are
bundled. It follows the prototype's tokens but has not been visually verified
and does not yet recreate the media detail/player screens.
