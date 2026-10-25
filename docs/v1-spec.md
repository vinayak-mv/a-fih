# a-Fih v1 specification

Status: agreed product scope; implementation in progress.
Date: 2026-10-25

## Product

a-Fih is a personal media-library and streaming application written in Kotlin
Multiplatform. It connects directly to storage services from the user's device.
There is no separately deployed backend, app account, registration, or admin
approval flow. Each device is independent. The first provider is Telegram using
an MTProto bot account, not the HTTP Bot API.

Windows desktop is the first implementation and test target. Android follows.
iOS, macOS, Linux, and TV targets are future work. Shared business logic and,
where practical, Compose UI should be reusable for Android.

## Design baseline

Reference: `D:\Lenovo Backup\Projects\a-fih-frontend`.
Preserve its light surfaces, red accent, tonal layering, rounded cards, generous
spacing, and Manrope/Inter typography where available. Re-create its home,
library, details, seasons, episodes, player, and settings concepts in Compose.
Adapt navigation for each platform. Dark mode is deferred. Screens must use real
state or honest empty/pending states, not invented media or success indicators.

## Providers and sources

- Provider management is available at any time: add, edit, and remove connections.
- Telegram setup asks for a name, API ID, API hash, and bot token.
- Support multiple independent bot connections, channels, and supergroups.
- Users arrange permissions outside the app. Inaccessible chats produce an error.
- Save credentials using platform-secure storage. Never embed personal credentials
  in source, logs, crash messages, CI variables used to build binaries, or artifacts.
- A saved connection is not considered authenticated until a real connection succeeds.
- Provider removal requires confirmation and identifies affected sources. It must
  not silently delete downloaded files or remote Telegram content.
- Add sources by Telegram identifiers/links. Accept public and private message
  links, including topic-shaped links. Offer whole-chat or explicit-topic scope.
  Reject malformed or ambiguous inputs instead of guessing their identity.
- Parsing can use filenames, captions, or both; configure a provider default and
  an optional source override. Changing this setting takes effect on rescan.

## Libraries and discovery

- Movies, TV series, anime, music, and unmatched media are in scope.
- One library can include multiple sources; one source can feed multiple libraries.
- A source is indexed once; library membership is separate from physical files.
- Unrecognized supported files remain browsable without a metadata match.
- Parse title, year, season/episode, language, and quality when present.
- Group different versions of the same movie/episode under one title and expose a
  file/version selector. Avoid merging files when identification is uncertain.
- Fetch posters, backdrops, synopsis, cast, and episode data using user-configured
  metadata-service credentials. Metadata failure must not prevent file playback.
- TMDB is the initial movie/TV candidate. Anime/music metadata coverage and their
  enrichment providers require validation; filenames remain the baseline.
- Manual correction of metadata matches is deferred beyond v1.
- Home includes featured library content, library shortcuts, and recently added.
- Search, basic filters, sorting, grid/list views, continue watching, watched
  status, and favorites/watchlist follow core indexing, playback, and downloads.

## Telegram indexing

### Initial and explicit range imports

The user supplies start and end message links from the same chat. Retrieve IDs
in inclusive batches of 20 (implementation can tune between 10 and 20). Empty
messages, deleted IDs, non-media messages, and entirely empty batches do not end
the scan. Stop only at the explicit upper boundary. Topic scope filters results
without changing the scan boundary. Support further range imports later.

Store message identity as provider + chat + Telegram message ID. Topic identity
is additional information, not a replacement for chat identity. Deduplicate repeat
imports and distinguish equal message IDs in different chats or providers.

Persist each successfully processed batch and its contiguous scan checkpoint
atomically. Request failures, access errors, flood waits, and cancellation must
not advance the checkpoint past unprocessed work. Persist unresolved errors.
An empty response for a successfully requested ID is not a transport failure.

### Incoming content and reconnection

Receive updates while connected and upsert supported media. Maintain the highest
observed message ID separately from the last contiguous scanned ID. Receiving a
new message must not move the latter past an unscanned gap.

Validate bot access to `updates.getChannelDifference`, persistent `pts`, `final`,
and stale-cursor behavior through the chosen Kotlin transport. If supported,
prefer transport-managed update recovery. Keep update cursors separate from raw
message IDs. After a gap, scan from the contiguous checkpoint to a known upper
message boundary in bounded batches.

If no reliable upper boundary is available, do not label empty-batch probing as
a complete scan. Keep the source in a boundary-needed state and allow an end
link. An empty-run heuristic may only be a temporary pause and must not cause
future messages to be skipped. Update recovery is not assumed to recover an
unlimited history after a long absence.

### Scheduling and background operation

Manual, launch-time, and configurable periodic scans are required. Prevent
overlapping scans of the same source. Indexing and downloads may continue while
minimized or the screen is off, subject to platform lifecycle limits. Windows
must remain running; Android requires appropriate background-work integration.
App termination/sleep is not a promise of uninterrupted background work.

## Playback

- Stream Telegram file bytes on-device; do not download the entire file before
  playback. Support seek requests, bounded buffering, cancellation, and retries.
- Cache reusable transport sessions for a single user's activity; no multi-user
  server load-balancing architecture is needed.
- Handle data-center routing, expired file references, and flood waits through
  the transport or explicitly in the integration.
- Essential controls: play/pause, seek, volume, fullscreen, resume position,
  available audio tracks, and subtitles. Support embedded subtitle selection;
  local external subtitle selection is desirable after basic playback works.
- Validate representative MKV and MP4 files, including H.264/H.265 and multiple
  audio/subtitle tracks. Format/codec support depends on the selected native
  player and device; surface unsupported-format errors clearly.
- No hosted transcoding. Casting, PiP, advanced automation, and extra player
  integrations are deferred unless needed to deliver reliable basic playback.
- Windows uses bundled libmpv, selected by the user after validating Telegram
  streaming. Playback must be integrated into the main app with Compose controls;
  no separate player window or user-installed media player is required.
  Select and validate an Android engine when Android implementation begins.

## Downloads and offline use

- Offline mode shows completed, locally available downloads, not the full index.
- No import of existing local files/folders in v1.
- Download controls: queue, pause/resume, cancel, retry, configurable concurrency
  (default 2), destination folder, progress, and available-space failures.
- Whole-season queuing and Wi-Fi-only Android policy follow core transfers.
- Streaming cache is temporary, bounded, and separate from explicit downloads.
  Cache is never shown as a completed offline file.
- Mark a download complete only after verifying expected length and committing
  the final file. Retain partial state for resumable transfers.
- Downloaded items retain enough title/artwork/track information to browse and
  play offline. Playback does not require reconnecting to Telegram.
- Downloaded copies survive missing remote sources and library cleanup unless
  the user explicitly chooses to delete the local file.

## Unavailable content

On playback failure, record an item in an issues list with reason, retry, and
remove-from-library actions. Distinguish access loss, network errors, and expired
references from confirmed missing messages. Retry/refetch where simple. Process
straightforward edit/delete updates when available; avoid a complex reconciliation
system in v1. Never automatically delete remote Telegram messages. Local library
removal and local download deletion are distinct operations.

## Local data and privacy

Persist provider/source configuration, library membership, media index, scan
checkpoints, watch state, download jobs, and issues on-device. No device sync,
Trakt sync, cloud accounts, local PIN, biometrics, or multi-profile system in v1.
Keep secrets redacted from object string representations and diagnostics. Use
Windows DPAPI initially; Android will use its platform keystore facilities.
Do not silently replace an unreadable data store with an empty one.

## Builds and distribution

Public GitHub repository. GitHub Actions runs checks and produces unsigned
Windows test artifacts on pushes, pull requests, manual dispatch, and version
tags. Begin with a portable app image containing a runtime; installer artifacts
can follow. Never publish releases automatically while this is a prototype.
Signing and public releases are deferred. Android build jobs follow its target.
No personal credentials are required to compile or test the app.

## Delivery and acceptance gates

1. Foundation: reproducible Gradle setup, shared domain, light desktop shell,
   securely persisted provider/library settings, range parsing and gap tests,
   Windows build/test/artifact workflow.
2. Telegram: native client distribution, real bot setup/access errors, range
   import into a local transactional index, topic filtering, deduplication,
   resumable checkpoints, update-recovery spike, incoming updates/scheduling.
3. Playback: real indexed media selection, stream/seek/cache integration, MKV/
   MP4/H.265 and track tests, resume, actionable failure states.
4. Offline: durable download jobs and independent offline library/playback.
5. Metadata/discovery: enrichment, grouping, details/seasons, search/filter/sort,
   watched state and favorites; preserve unmatched-file access.
6. Android: platform credentials/storage, native playback and transport,
   background behavior, CI artifacts, adapted navigation.

Acceptance includes fresh-device setup; inaccessible chat; a deleted ID followed
by valid media; a completely empty batch followed by valid media; failed batch
retry without lost IDs; repeated imports; equal IDs in different chats; restart
mid-scan; stale update cursor; seek outside the current buffer; unavailable remote
media; track selection; interrupted/resumed download; and downloaded playback
with the network disabled.

## References

- Streaming approach: https://github.com/rafsanbasunia/reelnn-backend/blob/main/utils/custom_dl.py
- Bot MTProto authorization: https://core.telegram.org/method/auth.importBotAuthorization
- Message retrieval: https://core.telegram.org/method/channels.getMessages
- Update recovery: https://core.telegram.org/method/updates.getChannelDifference
- File transfer: https://core.telegram.org/api/files
- Candidate transport: https://core.telegram.org/tdlib

The reference is used to understand transfer behavior. Do not copy its code
without checking applicable licenses and preserving required attribution.
