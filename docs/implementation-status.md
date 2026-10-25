# Implementation status

## Library updates and personal state increment (0.6.0)

User-selected scope: automatic library updates, personal library features,
unavailable-media issues, and recovery checks. Downloads/Android remain deferred;
the user plans UI work after this increment.

- [x] Follow received new messages and chat-tail updates using known message IDs.
- [x] Ordered TDLib update delivery into an app-owned edit/delete journal.
- [x] Persistent watermarks, contiguous checkpoints, pending-work versions, and retry times.
- [x] Per-source launch/manual/scheduled scans, pause/resume, and at most two background scans.
- [x] TDLib reconnect/update recovery retained in its encrypted session; closed sessions recreate.
- [x] Bounded transient request/stream retries, persisted provider flood-wait cooldowns.
- [x] Continue watching, watched/unwatched, completion state, favorites, and watchlist.
- [x] Category/state filters and added/title/played/year/rating sorting.
- [x] Persistent issue reasons, play/retry, successful-playback clearing, and local removal.
- [x] Playback/schema/checkpoint compatibility, journal/retry/schedule/empty-gap fixtures.

Verification: CI compilation/tests, native player smoke, and packaging are pending
for this increment. Live bot recovery still requires device-side validation.
TDLib owns MTProto pts/difference recovery; the app does not maintain a competing
raw pts cursor. Unknown historical tails cannot be inferred from empty IDs, so a
manual end-link extension remains available if Telegram cannot replay an old gap.
Launch/scheduled work runs while the application is open, including minimized.

## In-app mpv player increment (0.5.0)

The user confirmed Telegram playback works in 0.4.0 and requested an integrated
mpv player. This replaces the separate VLC/Swing window entirely.

- [x] Bundled baseline x86_64 libmpv with pinned archive and SHA-256 verification.
- [x] Direct JNA client API; VLC/vlcj dependencies and separate player window removed.
- [x] Video embedded in the existing main window; navigation and controls use Compose.
- [x] Back to library, actual fullscreen, seek slider, ±10 seconds, play/pause/replay,
  volume, embedded audio/subtitle menus, status/error/retry, and keyboard shortcuts.
- [x] Existing Telegram byte ranges, temporary cache cleanup, and saved progress retained.
- [x] Native HWND lifecycle hooks release mpv before a video surface is destroyed.
- [x] Native client ABI/commands/properties/track/error fixtures plus an embedded
  video smoke check using generated media and the real loopback bridge.

Verification: [CI run 40210914139](https://github.com/vinayak-mv/a-fih/actions/runs/40210914139)
passed all 46 tests (16 shared, 30 desktop), native video rendering in the embedded
Windows surface, pause/volume/seek and peer-removal checks, and portable packaging.
The artifact is `a-Fih-windows-x64-preview-12`. Live rendering and fullscreen with
the user's Telegram MKV/H.265 collection remain device-side checks.

## Telegram streaming increment (0.4.0, player superseded by 0.5.0)

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
- [x] Bounded transient retry/backoff and persisted flood-wait scheduling.
- [ ] Validate bot update recovery and stale cursors through chosen transport.
- [x] Launch/manual/periodic scheduling and received incoming media updates.
- [x] Native Windows player and seekable Telegram streaming implemented.
- [ ] Live Telegram playback, seeking, and real codec/track validation.
- [ ] Durable downloads, completed-download offline library, and offline playback.
- [x] TMDB metadata, matched grouping, poster/detail discovery, and basic search.
- [x] Personal watch state, favorites/watchlist, filters and sorting.
- [ ] Richer release parsing and music metadata.
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
and now includes metadata details. The mpv video surface occupies a dedicated
region inside the main window, with Compose controls in adjacent regions to
avoid placing overlays over a heavyweight Windows surface.
