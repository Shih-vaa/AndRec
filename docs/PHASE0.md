# PHASE0 — progress and what blocks the rest

Living checklist for SPEC §16 Phase 0. Updated 2026-10-23. Phase 0 is done only when every spike has a recorded result and the dependent ADRs are Accepted or revised; nothing in Phase 1 starts before that.

## Deliverables

| Deliverable | Status | Where |
| --- | --- | --- |
| Repo skeleton (`:app`, `:core:model`), Gradle wrapper, version catalog | Done, builds | `settings.gradle.kts`, `gradle/libs.versions.toml` |
| Lint and tests wired (ktlint, detekt, JUnit 5) | Done | ADR-013 |
| CI workflow | Written, **never run** (not pushed yet) | `.github/workflows/ci.yml` |
| Honesty-model vocabulary + spec-drift test | Done (enums only, no transition logic: that is Phase 1/2) | `core/model` |
| ADR log | ADR-013 added, ADR-002 amended; all still Proposed | `DECISIONS.md` |
| Threat model draft | Draft, unreviewed | `THREATS.md` |
| Cost model template | Template only, no figures | `COST_MODEL.md` |
| Remaining modules from the CLAUDE.md layout | Not created on purpose: each is added in the phase that first uses it | — |
| Spike reports | **3 of 9 run**: spike 1 (one real phone), spike 3 (emulator and one real phone), spike 5 (research only) | `PLATFORM_CONSTRAINTS.md` spike log |
| Spike 3 harness | Throwaway Android app, never shipped | `spikes/crypto` |

## Spikes: what each one needs before it can run

Nothing below has been started. "Ask" means CLAUDE.md requires the owner's approval first.

| # | Spike | Needs | Ask? |
| --- | --- | --- | --- |
| 1 | MediaStore enumeration + SHA-256, ≥ 10k files | **Done on the Galaxy M31** (`spikes/mediastore`, `docs/spikes/spike1-mediastore.md`); permissions approved in ADR-015. Not yet run on Android 13+ (`READ_MEDIA_*`) or a second device | — |
| 2 | SAF traversal, ≥ 50k files, grants survive reboot | Spike module; picker only, no manifest permission | New module |
| 3 | Tink streaming AEAD + Argon2id on a low-end device | **Done for library choice** on the emulator and a real Galaxy M31 (mid-range) over wireless debugging; ADR-014 proposed. KDF parameter floor stays provisional until a ≤ 3 GB RAM phone is measured. Dependencies approved by the owner for this spike | Owner: accept ADR-014? Another, weaker phone? |
| 4 | Drive authorization, resumable upload, checksums, revoke | Google Cloud project and OAuth client, a Google account, Google auth and Drive client libraries | **Dependencies, account setup** |
| 5 | All-files access vs picker/SAF strategy | **Research done** (`docs/spikes/spike5-permission-strategy.md`); Play declaration drafted, **not submitted**, so approval is unknown | Owner: accept the ADR-007 refinement? When to submit? |
| 6 | MediaStore restore preserving dates and paths | Spike module; own-file MediaStore writes (no extra permission expected **[verify]**); emulators across API levels | New module |
| 7 | Keystore + Auto Backup on device loss | Two devices or emulators, a backup/restore run; decides `allowBackup` | New module |
| 8 | Foreground service `dataSync` timeout | Spike module, a foreground-service permission and type declaration; find the documented way to trigger the timeout before relying on one | Permission |
| 9 | User-initiated data transfer jobs | Read the official docs first, then a small prototype | Permission, maybe |

The `spikes` parent module now holds `:spikes:crypto` (spike 3) and `:spikes:mediastore` (spike 1). Proposed next step, if approved: add 2, 6, 7, 8 and 9 as further debug-only modules under `spikes/` (platform APIs only, no third-party dependencies), run on the emulator first and then on real hardware. Spike 4 waits for the owner to set up the Google Cloud project when asked.

## Environment notes (2026-10-23)

- Built on macOS with JDK 17, Gradle 9.6.1, SDK platforms up to 37.0, one AVD (`Pixel_9_Pro`, Android 37 image). The placeholder app was installed and launched on that emulator (API 37) on 2026-10-23 and renders; it has **not** been run on a real device or an older API level.
- `~/Library/Android/sdk/cmdline-tools/latest` is a symlink into Homebrew's own SDK root. Run `sdkmanager --sdk_root=$HOME/Library/Android/sdk ...` or it installs into the wrong place.
