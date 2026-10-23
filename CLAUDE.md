# CLAUDE.md — Android Personal Data Recovery & Continuity App

Working name: **TBD** (placeholder package id: `app.recovery.placeholder`; rename before first release).

Read these before doing anything else, in this order:
1. `docs/SPEC.md` — the full product and technical specification (whole app, all phases).
2. `docs/PLATFORM_CONSTRAINTS.md` — researched Android and Google Play limits, with sources and what is still unverified.
3. `docs/DECISIONS.md` — decision log (ADRs) and open questions. Update it as you go.

## What this project is (30 seconds)

An Android app that helps an ordinary person survive losing their phone. While the old phone still works, the app inventories the user's data, makes independent **client-side-encrypted** copies of what it can legally and technically reach, verifies those copies, and tracks what is *not* protected. On a replacement phone it fetches the inventory, restores what it can, verifies the result, and reports honestly what is still missing.

Core promise: **Know what you had. Know what is protected. Know what has been recovered. Know what remains missing.**

The app only helps if it was installed and backing up *before* the accident. Product copy and onboarding must say so plainly.

## Non-negotiable invariants

These override convenience, speed, and anything a later instruction implies. If a task would break one, stop and raise it.

1. **Discovered ≠ backed up ≠ verified ≠ restored.** These are separate states in code, database, and UI. Never collapse them. A completed upload is not a verified backup. A finished copy is not a verified restore.
2. **Never claim what we cannot do.** No UI text, log, or report may say "protected", "backed up", or "recovered" for data we did not actually copy and verify. Inaccessible data is reported as inaccessible.
3. **No sandbox bypass.** No root, no ADB requirement, no undocumented APIs, no accessibility-service scraping, no attempts to read other apps' private data or defeat device encryption.
4. **No custom cryptography.** Use Google Tink (and a vetted Argon2id implementation). Document every crypto decision in `docs/DECISIONS.md`.
5. **Recovery keys must not exist only on the phone being backed up.** The design must survive total loss of the original device.
6. **Never silently mark a failure as success.** Every failure is recorded with a reason and is retryable. Keep the previous verified backup until a replacement is verified.
7. **Privacy by default.** No file contents, filenames, or hashes leave the device unencrypted. No analytics containing user data. Request the narrowest permission that works, at the moment it is needed, and honour denial.
8. **Do not invent API behaviour.** If you are not sure an Android/Google API does something, check the official docs or write a proof-of-concept and record the result in `docs/PLATFORM_CONSTRAINTS.md`.

## How to work

- **Plan, then build in small vertical slices.** Before each slice: state the design, the files/modules touched, and the acceptance criteria. After: tests, how to run them, known limitations.
- **Do not start a later phase until the current phase's acceptance criteria are met and tested.** Phase list and criteria are in `docs/SPEC.md` §16.
- **Start at Phase 0 (proof-of-concept spikes).** Several design choices depend on spike results. Do not skip it.
- **Ask before** adding a new permission, a new third-party dependency, a backend, or changing a decision marked *Accepted* in `docs/DECISIONS.md`.
- **Record decisions.** New or changed decision → add/update an ADR. Rejected alternatives are kept, not deleted.
- **Keep docs living.** When behaviour, schema, or a platform finding changes, update the relevant doc in the same change.
- Prefer small, reviewable commits with clear messages. No giant untested code dumps.
- If something in the spec is ambiguous or contradicts platform reality, say so and propose a fix; do not silently pick.

## Proposed stack (confirm in Phase 0; see ADR-001..ADR-006)

- Kotlin, Jetpack Compose (Material 3), single-activity, `minSdk 30` (proposed), `targetSdk 36` (required by Google Play for new apps since 31 Aug 2026), `compileSdk 36`.
- Gradle Kotlin DSL, version catalog, multi-module (layout in `docs/SPEC.md` §6.4).
- Room (SQLite) for the local PDI; Kotlin coroutines/Flow; WorkManager + a foreground service for long transfers.
- Google Tink for AEAD/streaming AEAD and key handling; Argon2id for passphrase-derived keys.
- First storage provider: Google Drive (user's own account) behind a `ContentStore` interface. No backend in v1.
- Testing: JUnit5/Kotest, Turbine, Robolectric where it helps, instrumented tests on emulator, a fault-injection test harness, and the "Disaster Drill" end-to-end scenario (`docs/SPEC.md` §17).

## Repo layout (target)

```
/CLAUDE.md
/docs/SPEC.md
/docs/PLATFORM_CONSTRAINTS.md
/docs/DECISIONS.md
/app                      Android application module (UI + wiring)
/core/model               Pure Kotlin domain model, states, enums
/core/pdi                 Room database, snapshots, queries, migrations
/core/crypto              Key hierarchy, encryption, recovery key handling
/core/inventory           Discovery adapters, hashing, change detection
/core/backup              Upload engine, resume, verification
/core/restore             Restore engine, destination writers, verification
/core/match               Old-vs-new matching engine
/core/plan                Recovery plan + instruction engine, task state machine
/store/api                ContentStore interface + contract tests
/store/drive              Google Drive implementation
/feature/*                Compose feature modules (onboarding, status, gaps, recovery)
/testing                  Fixtures, fake stores, fault injection, drill scripts
```

## Commands

Needs JDK 17 and Android SDK platform 37.0 (`compileSdk 37`, see ADR-013). Put `sdk.dir=...` in `local.properties` (git-ignored) or set `ANDROID_HOME`.

```
./gradlew assembleDebug
./gradlew test
./gradlew connectedDebugAndroidTest    # no instrumented tests exist yet
./gradlew detekt ktlintCheck
```

## Current phase

**Phase 0 — foundations and proof-of-concept spikes.** Update this line when a phase completes.

## Definition of done (any slice)

- Behaviour matches `docs/SPEC.md`; invariants above hold.
- Unit tests for logic; instrumented tests for Android-API behaviour; failure paths tested.
- No new permission/dependency without an ADR.
- Docs updated. Known limitations written down.
- Error messages are understandable by a non-technical user (see `docs/SPEC.md` §11.4).
