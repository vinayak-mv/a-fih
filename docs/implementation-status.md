# Implementation status

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
Build execution and visual desktop QA are pending an available JDK or the first
GitHub Actions run. No GitHub repository has been supplied or connected, so no
remote build has been dispatched.

Completed local checks:

- Official Gradle wrapper JAR SHA-256 matches the checksum published by Gradle.
- Wrapper JAR ZIP integrity and launcher-class presence verified.
- Gradle 8.14.3 distribution SHA-256 recorded and pinned in wrapper properties.
- POSIX Gradle launcher passed `bash -n` syntax validation.
- Reviewed configuration diagnostics: credential values are masked/redacted and
  provider data is not printed to logs.

Pending: Kotlin compilation, execution of the 11 authored tests, workflow
execution, application startup, and visual/interaction QA. YAML linting was not
run because no YAML linter is installed in this workspace.

## Next work

- [ ] First successful Windows build, test reports, and actual app launch QA.
- [ ] Telegram transport/native library packaging and secure session storage.
- [ ] Real bot authentication and source access validation.
- [ ] Sources, library/source associations, topics, and per-source parsing options.
- [ ] Transactional media index and per-range/source checkpoints.
- [ ] Live import using the shared scanner, retry/flood-wait behavior, cancellation.
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
Configuration DPAPI storage is for a small settings file; it is not the media
index database. The live index will need a transactional database.

The initial UI uses system fonts until licensed Manrope/Inter font assets are
bundled. It follows the prototype's tokens but has not been visually verified
and does not yet recreate the media detail/player screens.
