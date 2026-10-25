# Implementation status

## Telegram streaming increment (0.4.0)

- [x] Play any imported movie, episode/version, unmatched video, or audio file.
- [x] Resolve the message again when opening media; use its current file ID/size.
- [x] Bounded TDLib range downloads and verified downloaded-prefix reads.
- [x] Private loopback bridge with single-range GET/HEAD, suffix ranges, and seeking.
- [x] Native Windows VLC player window with play/pause, seek, volume, maximize,
  embedded audio/subtitle selection, buffering/error text, and retry by reopening.
- [x] Local playback position per provider/chat/message, retained across restarts.
- [x] Stop superseded reads and clean temporary stream data when the player closes.
- [x] Checksum-pinned VLC runtime bundled alongside Java and TDLib in CI artifacts.
- [x] Range/HTTP/Telegram-read/progress fixtures and a native WAV playback smoke check.

Verification: [CI run 40206757343](https://github.com/vinayak-mv/a-fih/actions/runs/40206757343)
passed all 41 tests (16 shared, 25 desktop), native WAV playback through the
loopback bridge, and portable Windows packaging. Its artifact is
`a-Fih-windows-x64-preview-9`. Live Telegram seeking, MKV/H.265, and track selection need
testing with the user's media. This is a Windows player milestone; Android,
offline downloads, external subtitles, a persistent unavailable-media queue,
and continue-watching shelves remain pending. Streaming uses temporary storage,
not the offline library. Only one playback window is active at a time.

## TMDB library increment (0.3.0)

- [x] Protected TMDB API key/read-token settings and language selection.
- [x] Enrich existing imports and automatically fetch metadata after scans/on launch.
- [x] Pause updates, retain completed matches, and retry unmatched files.
- [x] Conservative filename/year/episode parsing and movie/series matching.
- [x] Additive metadata/season tables preserve Telegram files and scan progress.
- [x] Poster/list views, featured backdrops, details, cast, seasons, and episodes.
- [x] Matched versions/episodes share cards; unmatched files stay browsable.
- [x] Bounded local artwork cache, graceful missing artwork, and TMDB attribution.
- [x] Fixture tests cover matching, decoding, caching, retry, and schema preservation.

CI run 40204666570 passed 35 tests, native runtime checks, and Windows packaging.
Live matching with the user's TMDB credential remains a device-side check.
No personal credentials are used in fixtures.

## Telegram import increment (0.2.0)

Cold-start chat resolution fix: private sources now load their supergroup using
`getSupergroup` before calling `createSupergroupChat` with network lookup enabled.
This also applies when resuming saved sources after restart. The old force-only
path required TDLib to have already learned the chat, which could happen only
after a new incoming update in a fresh session. Regression fixtures cover empty
sessions, username resolution, and genuine access failures. The user confirmed
the cold-start fix works, and workflow 40202056007 passed all jobs.

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

The Kotlin checks passed in workflow run 40184045823. The first native build
revealed a missing gperf build tool; the build script now installs it through
the pinned vcpkg tree and supplies its path to CMake. Subsequent native packaging
and the user's live bot test succeeded.
No personal credentials are present in fixtures. Incoming update indexing,
reconnection recovery, scheduled scans, downloads, and Android remain pending.
Streaming/playback was added in 0.4.0; downloads have no button yet.

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

The 0.2.0 build and live cold-start import were validated; 0.3.0 passed CI.
No YAML linter is installed. The 0.4.0 tests, native stream smoke check, and
portable packaging passed in run 40206757343.

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
- [x] Native Windows player and seekable Telegram streaming implemented.
- [ ] Live Telegram playback, seeking, and real codec/track validation.
- [ ] Durable downloads, completed-download offline library, and offline playback.
- [x] TMDB metadata, matched grouping, poster/detail discovery, and basic search.
- [ ] Richer release parsing, music metadata, and personal watch state.
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
and now includes metadata details. The native player uses a separate window
to keep the heavyweight Windows video surface clear of Compose overlays.
