# DECISIONS — ADR log

Format: each decision has a status (**Proposed**, **Accepted**, **Rejected**, **Superseded**), context, decision, consequences, and the spike/evidence that backs it. Do not change an *Accepted* decision without asking the project owner. Never delete rejected alternatives; mark them.

The owner has accepted only what is marked **Accepted**. Everything else is a proposal for you (Claude Code) to validate in Phase 0 and then present for acceptance.

---

## ADR-000 — Product invariants
**Status:** Accepted
The invariants in `CLAUDE.md` are fixed: discovered ≠ backed up ≠ verified ≠ restored; honest reporting; no sandbox bypass; no custom crypto; recovery independent of the old phone; no silent failure; privacy by default; no invented APIs.

## ADR-001 — Language and UI toolkit
**Status:** Proposed
Kotlin + Jetpack Compose (Material 3), single activity, coroutines/Flow, Gradle Kotlin DSL with version catalog.
Alternatives: Flutter / React Native (rejected for now: heavy use of Android-specific storage, WorkManager, foreground services, Keystore, Play permission flows).
Consequences: Android-only; shared logic can be extracted later (Kotlin Multiplatform) if an iOS/desktop client is ever wanted.

## ADR-002 — SDK levels
**Status:** Proposed
`compileSdk 36`, `targetSdk 36` (Play requirement for new apps since 31 Aug 2026), `minSdk 30` (Android 11).
Why 30: scoped storage rules are uniform, photo picker backport floor, simpler MediaStore behaviour.
Open: check target-market device mix; lowering to 29 or lower costs complexity.
Amendment 2026-10-23 (still Proposed): `compileSdk` is **37**, not 36 — current AndroidX (Compose 1.12, core-ktx 1.19) requires it. `targetSdk 36` and `minSdk 30` are unchanged. See ADR-013.

## ADR-003 — Local-first architecture, no backend in v1
**Status:** Proposed
All discovery, hashing, encryption, upload, matching, planning, restore on device. Store holds opaque encrypted blobs + encrypted manifests + wrapped keyring.
Alternatives: first-party backend from day one (rejected for v1: hosting cost, security surface, operational burden; revisit in Phase 8).

## ADR-004 — Storage provider abstraction; Drive first
**Status:** Proposed
`ContentStore` interface with contract tests; `DriveStore` first; SAF/local and S3/WebDAV later.
Open: `drive.file` vs app-data scope (spike 4). Both avoid the annual restricted-scope assessment per secondary sources; confirm on Google's pages.

## ADR-005 — Encryption design
**Status:** Proposed — **requires external security review before release**
Tink streaming AEAD for objects; HKDF-derived subkeys from a random 256-bit master key; master key wrapped by (a) Argon2id(passphrase) and (b) a recovery key, both stored in `keyring.json` in the store; (c) Keystore wrap for device convenience only; object names via HMAC of plaintext hash; hash-chained encrypted manifests.
Open: Argon2id library/params (spike 3); recovery-key format; whether to add trusted-contact sharing (Phase 7, vetted library).
Consequence: if the user loses passphrase and recovery key, data is unrecoverable. This must be stated at setup and visible in "Recovery readiness".

## ADR-006 — PDI storage and versioning
**Status:** Proposed
Room/SQLite with immutable snapshots and `asset_version` validity ranges (`first_seq`..`last_seq`) instead of copying inventories per snapshot. Encrypted manifest export (delta + periodic full) to the store.

## ADR-007 — File access strategy
**Status:** Proposed — depends on spike 5
Default design: `MediaStoreAdapter` + `SafTreeAdapter` (works without special approval for picked folders). Optionally `AllFilesAdapter` if Play approves `MANAGE_EXTERNAL_STORAGE` for backup/restore. Photo/video broad permissions require a declaration; the picker is the fallback.
Consequence: if not approved, protected scope is "what the user explicitly selects"; UI must say so.

## ADR-008 — SMS and call log
**Status:** Proposed
Do **not** build SMS/call-log backup in v1–v5. It requires being the default SMS/Phone handler. Provide guidance to compliant tools instead. Revisit only with an explicit product decision.

## ADR-009 — Contacts and calendar
**Status:** Proposed
Phase 5: encrypted vCard/ICS export and duplicate-safe restore; `READ_CONTACTS` needs a Play declaration (mandatory from 28 Oct 2026 for API 37+ apps). Earlier phases only check sync status where possible.

## ADR-010 — Background execution
**Status:** Proposed
WorkManager for scans/incremental work; foreground service for long uploads with `onTimeout` handling for the 6 h/24 h `dataSync` limit; all work resumable. Evaluate user-initiated data transfer jobs (spike 9).

## ADR-011 — Telemetry
**Status:** Proposed
No analytics. Local logs + user-triggered redacted diagnostics. Optional opt-in crash reporting with scrubbed payloads, decided later.

## ADR-012 — Guides as data
**Status:** Proposed
Recovery guides are versioned data resources with `last_reviewed` dates and source links; never claim automation that does not exist; localisable.

## ADR-013 — Build toolchain and pinned versions
**Status:** Proposed
Context: Phase 0 skeleton. Versions were looked up on 2026-10-23 from Google Maven, Maven Central and the Gradle Plugin Portal; stable releases only.
Decision: Gradle 9.6.1 (wrapper), AGP 9.4.1, Kotlin 2.4.21 (JVM toolchain 17), Compose BOM 2026.09.00, activity-compose 1.13.0, core-ktx 1.19.1, JUnit Jupiter 6.1.3 with kotlin-test-junit5, ktlint-gradle 14.2.0, detekt 1.23.8. `compileSdk 37`, `targetSdk 36`, `minSdk 30`. All versions live in `gradle/libs.versions.toml`. Test framework for now is JUnit 5; Kotest is not adopted yet.
Alternatives considered:
- Pin older AndroidX so `compileSdk` can stay 36 — rejected by the owner on 2026-10-23: stale from day one. Platform 37.0 was installed instead.
- Pre-release tooling (Kotlin 2.5.0-Beta, AGP 9.6.0-alpha, activity-compose 1.14 alpha, detekt `dev.detekt` 2.0.0-alpha) — rejected: not stable.
Consequences / open:
- detekt 1.23.8 is the last release of the old `io.gitlab.arturbosch` line, built against an older Kotlin. It ran clean on the current code but may reject newer language syntax; revisit when `dev.detekt` 2.0 is stable.
- Gradle dependency verification (`verification-metadata.xml`) is not enabled yet; see T18 in `THREATS.md`.
- CI (`.github/workflows/ci.yml`) is written but has not run yet (first run comes with the first PR). Whether a hosted runner has platform 37.0 is untested.
Evidence: `./gradlew assembleDebug test detekt ktlintCheck` green on 2026-10-23 (macOS, JDK 17). A deliberate rename of one protection state made `SpecStatesTest` fail, so the drift guard is live.
Date / author: 2026-10-23 / Claude Code

---

## Open questions (owner to answer or delegate to spikes)

1. Final product name and package id.
2. Minimum Android version vs target audience devices.
3. Is bring-your-own-storage (Drive) the final model, or will first-party paid storage be offered (Phase 8)?
4. Willingness to become default SMS app (default answer: no).
5. Primary target markets (affects legal review, languages, Play requirements).
6. Does recovery need to work without a Google account (SAF folder + recovery key)? Proposed: yes by Phase 6.
7. Who performs the external security review, and when?
8. Test devices available (manufacturers, Android versions) for the Disaster Drill.
9. Developer account type and Play Console readiness (testing requirements not yet researched).
10. Open-source vs closed-source (affects verification, licensing, trust).

## How to add an ADR

```
## ADR-0NN — Title
**Status:** Proposed | Accepted | Rejected | Superseded by ADR-0MM
Context: ...
Decision: ...
Alternatives considered: ...
Consequences: ...
Evidence: spike #, links, test results
Date / author:
```
