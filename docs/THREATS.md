# THREATS — threat model (draft v0.1)

Status: draft, 23 Oct 2026. Expands `docs/SPEC.md` §9.5. Phase 0 deliverable; **not reviewed by a security professional**. ADR-005 requires an external review before any public release. Nothing here is a claim that a mitigation is implemented: every mitigation is a design intent until the phase that builds it passes its tests.

Scope: the v1 architecture (SPEC §6): an Android app plus the user's own Google Drive as an opaque store; no first-party backend. A backend (Phase 8) needs its own model.

Legend: **[verify]** = depends on a platform behaviour not yet confirmed (see `PLATFORM_CONSTRAINTS.md`; invariant 8).

## 1. Assets

| ID | Asset | Where it lives |
| --- | --- | --- |
| A1 | Master key (MK) | Wrapped in `keyring.json` (store) and Keystore-wrapped on the device (SPEC §9.2) |
| A2 | Passphrase | User's memory / password manager |
| A3 | Recovery key | Wherever the user saves it; shown once at setup |
| A4 | Plaintext user content | Old device, new device after restore |
| A5 | Backup objects (ciphertext) | Store |
| A6 | Encrypted manifests and `keyring.json` | Store |
| A7 | Local PDI database: plaintext filenames, paths, hashes, sizes | App-private storage on the device |
| A8 | Drive authorization tokens | Device |
| A9 | Availability: the ability to recover after losing the phone | The whole design |

## 2. Actors

Opportunistic thief (phone locked) · thief with the phone unlocked · attacker holding the user's Google account · the storage provider acting as honest-but-curious · a malicious or faulty store (tamper, roll back, delete) · a malicious app on the same device · a phisher targeting the recovery flow · the user making a mistake · us, through bugs.

## 3. Trust boundaries

1. App process ↔ rest of the device (OS sandbox, other apps).
2. Device ↔ network ↔ Drive.
3. App ↔ the human (recovery key and passphrase handling).
4. Store contents ↔ the new device (everything read back on restore is untrusted input until authenticated).

## 4. Threats

| ID | Threat | Asset | Mitigation (design intent) | Residual risk / open |
| --- | --- | --- | --- | --- |
| T1 | Thief with a locked phone reads app data | A1, A4, A7 | Data in app-private storage; MK held in Keystore-wrapped form only (SPEC §9.2); rely on OS file-based encryption | Depends on OS and screen lock strength |
| T2 | Thief with an unlocked phone | A4, A7 | App lock (biometric/PIN) before viewing status or starting a restore; sensitive operations require user authentication | Unlocked phone + unlocked app is out of our control |
| T3 | Attacker takes over the user's Google account | A5, A6, A9 | All content and manifests are client-side encrypted; object names are keyed hashes, not filenames (SPEC §8.2) | Attacker can delete or corrupt blobs → periodic verification (SPEC §8.6) notices; second target in Phase 6 |
| T4 | Store learns metadata: object count, sizes, upload times | A5 | Content-addressed opaque names; bundle small files (SPEC §8.4 step 6) | **Sizes, counts and timing leak.** Disclose; padding is not planned |
| T5 | Store tampers with or swaps ciphertexts | A5, A6 | AEAD; associated data binds scheme version and `content_id` (SPEC §9.4); hash-chained manifests | Cannot prevent deletion or full rollback by a malicious store |
| T6 | Store serves an older manifest head (rollback) | A6 | Hash chain detects truncation and some rollbacks | Cannot be fully detected without independent state; documented limit (SPEC §9.4) |
| T7 | Offline brute force of a weak passphrase against `keyring.json` | A1, A2 | Argon2id with parameters from spike 3; passphrase strength feedback; the recovery key is independent 256-bit | Parameters on low-end devices cap the cost we can impose. Anyone with Drive access can fetch `keyring.json` |
| T8 | Recovery key saved somewhere an attacker also reaches (screenshot synced to the same Google account, note in the same Drive) | A3 | Setup copy warns against storing it only in the account that holds the backup; offer print / password manager / write down | We cannot control where the user puts it |
| T9 | Recovery key or passphrase captured on the device (screenshot, screen recording, clipboard, overlay) | A2, A3 | `FLAG_SECURE` on key screens; avoid the clipboard or mark content sensitive **[verify API and min version]**; no exported components that hold secrets | Compromised OS out of scope |
| T10 | Phishing of the recovery flow (fake site or app asking for the key or Google password) | A3, A9 | Never ask for the Google password (system authorization UI only); the key is entered only inside the app and never sent anywhere; recovery copy says so | User social engineering |
| T11 | Lost passphrase and recovery key | A9 | Two independent wraps; confirmation step at setup; visible "Recovery readiness" (SPEC §11.2 #6) | Unrecoverable by design; disclosed at setup |
| T12 | Our bug deletes or overwrites the last good backup | A5, A9 | Invariant 6: keep the previous verified object until its replacement verifies; deletion needs confirmation; fault-injection tests | Bugs remain possible; tests are the control |
| T13 | Malicious or corrupt manifest at restore time (path traversal, overwrite of existing files, huge sizes) | A4 | Treat decrypted manifests as untrusted: normalise and validate `rel_path`; write only into the chosen SAF tree or MediaStore; never overwrite; verify hash after write (SPEC §8.10); size and count sanity limits | Needs dedicated tests in Phase 3 |
| T14 | Plaintext metadata leaks through logs, diagnostics or analytics | A7 | No analytics (ADR-011); no filenames or hashes in remote logs; redacted, user-triggered diagnostic bundle; tests that scan logs produced from fixtures | Discipline, enforced by tests |
| T15 | The PDI database leaves the device through Android backup or device-to-device transfer | A7 | `allowBackup` is **off** in the skeleton until spike 7 decides; any later opt-in must exclude the PDI explicitly | Behaviour of Auto Backup and Keystore on device loss is unverified **[verify]** |
| T16 | Drive token theft or over-broad scope | A8 | Least scope (`drive.file` or app-data, final choice after spike 4); platform-supported token storage; handle expiry and revocation (SPEC §8.5) | Scope semantics partly from secondary sources |
| T17 | Crypto misuse (nonce reuse, missing associated data, key reuse across purposes) | A1, A5 | Tink only, no custom primitives (invariant 4); HKDF-separated subkeys; fixed test vectors; tamper tests; external review (ADR-005) | Review not yet scheduled |
| T18 | Supply chain: compromised dependency or build tooling | all | Small dependency set, each with an ADR; versions pinned in the version catalog; consider Gradle dependency verification **[proposed, not enabled]**; CI uses the Gradle wrapper | Transitive dependencies are large (Compose, AndroidX) |
| T19 | User believes data is protected when it is not (authenticator seeds, WhatsApp chats, Secure Folder) | A9 | Honesty model (SPEC §4) and gap checklist (SPEC §11.2 #4); no generic "backup complete" while important categories are unprotected | Copy quality; tested in UX comprehension tests |
| T20 | Fake or tampered copy of this app distributed outside Google Play | A1–A3 | Distribution through Google Play; developer verification (PLATFORM_CONSTRAINTS §8) | Sideloaded builds outside our control |

## 5. Out of scope (v1)

Rooted or otherwise compromised OS · nation-state or forensic attackers with physical access · a malicious Google Play Services · side channels (timing, power) · coercion or legal compulsion of the user · a first-party backend (Phase 8).

## 6. How this document is used

- Each threat that a phase claims to mitigate gets a named test in that phase's acceptance criteria (SPEC §16, §17.1 "Security tests").
- New design decisions that touch a threat update this file in the same change.
- Items marked **[verify]** are resolved by a spike or an official-doc citation, then moved into `PLATFORM_CONSTRAINTS.md`.
