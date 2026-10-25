# PLATFORM_CONSTRAINTS — Android and Google Play limits

As of **23 Oct 2026**. Research round 1. Treat this as a living document: when a spike proves or disproves something, update the status column and add the date and device/OS used.

Status legend: **Verified** = read in official docs; **Secondary** = from third-party sources, re-check against Google/vendor pages; **Unverified** = not researched or only background knowledge; **Spike** = must be proven by proof-of-concept.

---

## 1. Storage and file access

| Topic | Finding | Status | Source |
| --- | --- | --- | --- |
| All-files access (`MANAGE_EXTERNAL_STORAGE`) | Backup and restore apps are a named permitted use on Google Play, but approval is via the Permissions Declaration Form and the developer must justify why the Storage Access Framework or MediaStore is not sufficient. Apps may not extend it to undisclosed purposes. | Verified | https://support.google.com/googleplay/android-developer/answer/10467955 |
| What all-files access grants | Read/write across shared storage, MediaStore.Files access, SD/OTG roots. Write access to all internal directories **except** `/Android/data/`, `/sdcard/Android` and most subdirectories. Other apps' app-specific directories remain inaccessible. | Verified | https://developer.android.com/training/data-storage/manage-all-files |
| SAF folder picker restrictions (Android 11+) | Cannot select: the internal storage root, the SD card volume root (reliable volumes), the `Download` directory, `Android/data` and `Android/obb` (and subdirectories). | Verified | https://developer.android.com/training/data-storage/shared/documents-files |
| Other apps' private directories (Android 11+) | An app cannot access another app's data directory or app-specific external directory. | Verified | https://developer.android.com/about/versions/11/privacy/storage |
| `DocumentFile` performance on big trees | Spike 2 (2026-10-24, Galaxy M31, Android 12, 50,000 files): `DocumentsContract` bulk queries listed all 50,000 files in 220 to 230 s (about 220 files/s) and were about **12.8x faster** than a hand-built per-property (`DocumentFile`-style) pattern at 17 files/s. Absolute speed is slow. The slow pattern is my reconstruction, not the library. | **Measured, one device** | `docs/spikes/spike2-saf.md` |
| Persisted tree grant survival | Spike 2: a persisted tree grant (read + write) stayed listed and usable after an app force-stop and after a phone reboot. After the folder was renamed away, the grant stayed listed but the listing returned 0 children with no error; renaming back restored access. Maximum number of grants and renames made through a file manager: not tested. | **Measured, one device** | `docs/spikes/spike2-saf.md` |

Spike 5 (2026-10-24) additions: the all-files policy lists **"Media Files access" as an invalid use**, so photos cannot justify it; other apps' files in `MediaStore.Downloads` need SAF; the SAF page states no limit on persisted grants (untested, spike 2).

**Implication:** photos/videos and user-picked folders are the realistic protected scope; Download root and anything else needs all-files access (approval not guaranteed). Hidden-but-accessible files are only reachable if inside a granted tree or all-files scope.

## 2. Photo/video permissions policy

| Topic | Finding | Status | Source |
| --- | --- | --- | --- |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | Only apps whose core functionality needs broad, persistent photo/video access may keep them; others must use a system picker. Photo/video managers and gallery apps are the most commonly eligible. Declaration in Play Console required. | Verified | https://support.google.com/googleplay/android-developer/answer/14115180 |
| Backup apps approved for these permissions? | Re-read 2026-10-24 (spike 5): the official photo/video policy names gallery apps and "picker technically insufficient" cases; it mentions **neither backup nor sync**. Eligibility is unconfirmed; only a submitted declaration answers it. One secondary source claims backup apps are eligible. | **Unconfirmed** (spike 5, `docs/spikes/spike5-permission-strategy.md`) | https://support.google.com/googleplay/android-developer/answer/14115180 ; https://medium.com/@punitsolanki157/how-to-fix-google-plays-photo-video-permissions-policy-rejection-in-flutter-f1a1b1d3378e |
| Photo picker availability | Android 11+ on devices receiving modular system updates; need `androidx.activity` 1.7.0+. Custom pickers do not automatically qualify for broad permission. | Verified | https://support.google.com/googleplay/android-developer/answer/16935362 |
| Partial photo access (`READ_MEDIA_VISUAL_USER_SELECTED`) | Android 14+ (app targeting 34+): the user can share chosen items only; that access "expires eventually"; do not assume full access or an empty library; without declaring the permission the app runs in a compatibility mode with session-only grants. The app must say so in coverage. | Verified (2026-10-24) | https://developer.android.com/about/versions/14/changes/partial-photo-video-access |
| Own media and reinstall | Android 10+: no permission to read or modify media the app owns; after uninstall and reinstall, `READ_EXTERNAL_STORAGE` is needed to reach the files it created earlier. | Verified (2026-10-24) | https://developer.android.com/training/data-storage/shared/media |

## 3. Contacts, SMS, call log

| Topic | Finding | Status | Source |
| --- | --- | --- | --- |
| Contacts permission policy | Introduced April 2026; **mandatory 28 Oct 2026** for apps targeting Android 17 (API 37)+. `READ_CONTACTS` only if the Contact Picker is insufficient; declaration required. **Contact backup/restore is a listed valid use.** | Verified | https://support.google.com/googleplay/android-developer/answer/16935362 |
| Contact Picker availability | Android 17+ only, not backported. | Verified | same |
| SMS permission group | App must be the registered default SMS (or Assistant) handler to declare it. | Verified | https://support.google.com/googleplay/android-developer/answer/16909972 |
| Call log permission group | App must be the registered default Phone (or Assistant) handler. | Verified | same |
| Exception for non-default handlers | Exceptions exist for some use cases but the requirements include APKs published **before 1 Jan 2019**. A policy table also lists "Backup and restore for users". Re-read the live policy and confirm in Play Console before planning any SMS/call-log feature. | Verified text, **interpretation unconfirmed** | https://support.google.com/googleplay/android-developer/answer/9047303 |

**Implication:** default plan is to not build SMS/call-log backup (needs becoming the default SMS app).

## 4. Android backup and app data

| Topic | Finding | Status | Source |
| --- | --- | --- | --- |
| Auto Backup | Up to 25 MB per app user; E2E-encrypted on Android 9+ with screen lock; roughly nightly under idle + Wi-Fi conditions (≥ 24 h since last); restored when the app is installed (including during device setup). Data stays unreadable to the user and to other apps. Previous backup is replaced. | Verified | https://developer.android.com/identity/data/autobackup |
| Device-to-device transfer | Supported for apps via data extraction rules (e.g. Pixel), requires the old device. | Verified | same |
| Block Store | Stores up to 16 byte arrays, up to 4 KB each, restored through the OS restore flow; intended for sign-in tokens. Not a place for the only copy of a recovery key. | Verified | https://developer.android.com/identity/block-store |
| Android Keystore behaviour on device loss | Keys are expected to be device-bound and not survive loss; not confirmed in this research round. | **Unverified / Spike** | — |

## 5. Background execution

| Topic | Finding | Status | Source |
| --- | --- | --- | --- |
| `dataSync` foreground service limit (target API 35+) | 6 hours total per 24 h; then `onTimeout` is called; cannot start another `dataSync` FGS until the user brings the app to the foreground. Services must only be started from direct user interaction. | Verified | https://developer.android.com/about/versions/15/behavior-changes-15 |
| `mediaProcessing` FGS | Same 6 h/24 h limit, tracked separately. | Verified | same |
| WorkManager per-run limits | Standard per-job runtime limits apply (commonly cited ~10 min); periodic minimum 15 min. Exact numbers not confirmed here. | Secondary / **Spike** | https://www.adalo.com/posts/android-sync-constraints-developers-need-know/ |
| User-initiated data transfer jobs | Newer Android API intended for large user-initiated transfers. | **Unverified / Spike** | — |
| Android 17 | New per-app memory limits and Keystore key-count limits (50,000 keys for apps targeting API 37+); background-audio hardening. Not storage-critical but check memory use of hashing/encryption. | Verified (Android 17 behaviour changes page) | https://developer.android.com/about/versions/17/behavior-changes-all |

## 6. Google Drive and Photos APIs

| Topic | Finding | Status | Source |
| --- | --- | --- | --- |
| Drive scope tiers | `drive.file` and `drive.appdata` are non-sensitive (basic verification, no security assessment). `drive`, `drive.readonly` are restricted (full verification **plus** annual CASA assessment). | Secondary (re-check Google's own page) | https://docs.unified.to/skills/google-drive-oauth-app/SKILL.md ; https://dev.to/seiyasato/the-scope-you-pick-decides-whether-you-can-ship-alone-11gl |
| `drive.file` semantics | App sees only files it created or the user opened/granted. | Secondary | https://github.com/techempower-org/candela/wiki/Google-Drive-setup |
| Drive checksum for uploaded objects, resumable upload session lifetime, API quotas | Believed available/limited; exact details not confirmed. | **Unverified / Spike** | — |
| Behaviour of app-data files if user disconnects the app | Not confirmed. | **Unverified / Spike** | — |
| Google Photos Library API | Since **31 Mar 2025**, `photoslibrary.readonly`, `.sharing`, `photoslibrary` scopes removed; list/search/get now only for items **created by your app**. Existing library items require the **Picker API**. | Verified | https://developers.google.com/photos/support/updates |

## 7. OEM and third-party apps

| Topic | Finding | Status | Source |
| --- | --- | --- | --- |
| Samsung Secure Folder | Backup to Samsung Cloud no longer supported; restoring from old backups unavailable on One UI 8+; Smart Switch (needs the old device) is the transfer route. | Verified | https://www.samsung.com/us/support/troubleshoot/TSG10001919/ |
| WhatsApp (Android) | Backs up to Google Drive; restore requires the **same phone number** (and the same Google account); an end-to-end encrypted backup cannot be restored if the password/key is lost. Not readable by other apps. | Secondary — re-check WhatsApp's help center | https://helpdeskgeek.com/how-to-restore-whatsapp-backup-from-google-drive/ |
| Authenticators, passkeys, password managers, banking apps | Credentials are intentionally device-bound or provider-managed; not accessible to other apps. Passkey/Credential Manager sync details **not researched**. | **Unverified** (round 2) | — |

## 8. Distribution

| Topic | Finding | Status | Source |
| --- | --- | --- | --- |
| Target API | New apps and updates on Google Play must target Android 16 (API 36)+ since 31 Aug 2026. Existing apps must target API 35+ to stay available to new users on newer OS. Extensions exist for some. | Verified | https://support.google.com/googleplay/android-developer/answer/11926878 |
| Android developer verification | Apps on certified devices must come from verified developers; enforcement began 30 Sep 2026 in Brazil, Indonesia, Singapore, Thailand; global rollout 2027. Advanced sideloading flow exists for unverified apps. | Verified | https://support.google.com/android-developer-console/answer/16561738 |
| Personal developer account testing requirements, store listing rules | Not researched. | **Unverified** | — |

---

## Spike log (fill in as results arrive)

| # | Spike | Date | Device/OS | Result | Decision/ADR affected |
| --- | --- | --- | --- | --- | --- |
| 1 | MediaStore enumeration + hashing performance | 2026-10-24 | Samsung SM-M315F (Galaxy M31), Android 12, unplugged, screen on, wireless debugging; 10,835 synthetic files (5.1 GiB) plus the phone's own 2,561 rows | **Pass on this device** (criteria C1–C5 in `docs/spikes/spike1-mediastore.md`). Enumeration of 13,396 rows: 439 ms median. SHA-256 first-read: 23.4 / 45.7 / 89.2 MiB/s with 1 / 2 / 4 threads; peak Java heap 37 MiB; 0 errors; +1.0 °C. SHA-256 itself runs at about 1,000 MiB/s, so reading is the limit: about 19 ms per file plus about 105 MiB/s for large files. **Files written straight to the filesystem stay `is_pending=1` and invisible to other apps until scanned** (relevant to restore, spike 6). Not covered: Android 13+ permissions, partial access, larger libraries, other devices | Informs SPEC §8.1/§8.2; no ADR changed |
| 2 | SAF traversal performance + persisted grants | 2026-10-24 | Samsung SM-M315F (Galaxy M31), Android 12, wireless debugging; 50,000 synthetic files in 200 directories | **S1, S3, S4, S5 pass; S2 fails** (60 s target missed: 220 to 230 s). Bulk listing 218 to 227 files/s, 12.8x faster than the per-property pattern; opening files through the grant about 55 ms each (18 files/s, 87 with 4 threads); grant survived restart and reboot; a renamed folder lists as empty without an error. The first full run stalled after the app was sent to the background (not diagnosed). Raw data and caveats: `docs/spikes/spike2-saf.md` | Informs ADR-007; no ADR changed |
| 3 | Tink + Argon2id on low-end device | 2026-10-23 | **Emulator only**: `sdk_gphone16k_arm64`, Android 37, 16 KB pages, 4 cores, 4 GB RAM, Java heap 192 MiB, on an Apple-silicon host. **Not a low-end device.** | **Partial.** Correctness: BouncyCastle 1.86 passes the RFC 9106 §5.3 Argon2id vector and argon2kt 1.6.0 agrees with it on 3 further inputs. argon2kt is 16 KB aligned (ELF LOAD align 0x4000) and loaded on a 16 KB-page device. Speed (median of 3): argon2kt 19 ms at 19 MiB/t2/p1, 118 ms at 64 MiB/t3/p1, 44 ms at 64 MiB/t3/p4, 96 ms at 128 MiB/t3/p4; BouncyCastle 141 / 513 / 499 / 999 ms (single-threaded: p=4 does not help). No OOM at 128 MiB, but the emulator's 192 MiB Java heap is generous. Tink streaming AEAD (64 MiB file, second pass): `AES256_GCM_HKDF_4KB` 288 MiB/s encrypt, 312 decrypt, 263,224 B overhead; `AES256_GCM_HKDF_1MB` 1362 / 914 MiB/s, 1,080 B overhead. Bit flip, last-segment truncation and wrong associated data are all rejected on both templates. **Still open:** timings and memory limits on a real low-end phone, and therefore the final KDF parameters. Raw data: `docs/spikes/spike3-emulator-2026-10-23.json`. | ADR-005 (open item: Argon2id library), ADR-014 |
| 3b | Tink + Argon2id on a real phone | 2026-10-24 | Samsung SM-M315F (Galaxy M31, 2020), Android 12 / API 31, 4 KB pages, 8 cores, 5.5 GB RAM, Java heap 256 MiB; unplugged at 93% battery, about 30 °C throughout; connected over wireless debugging. **Mid-range, not low-end** (≤ 3 GB RAM phones are still unmeasured) | **Pass for library choice.** Both libraries pass the RFC 9106 vector and agree with each other; argon2kt's native library loaded on this Exynos/Samsung phone. Argon2id median of 3, argon2kt: 19 MiB/t2/p1 82 ms; 46 MiB/t1/p1 103 ms; 64 MiB/t3/p1 459 ms; 64 MiB/t3/p4 358 ms; 128 MiB/t3/p4 698 ms. BouncyCastle: 680 / 753 / 2,712 / 2,742 / 5,369 ms (about 6x to 8x slower than argon2kt; p=4 gives no gain). No OOM or error. Tink streaming AEAD, 64 MiB, second pass: `AES256_GCM_HKDF_4KB` **22 MiB/s encrypt, 26 decrypt**; `AES256_GCM_HKDF_1MB` **114 / 123 MiB/s** (about 5x faster). Bit flip, truncation and wrong associated data rejected on both. Raw data: `docs/spikes/spike3-sm-m315f-2026-10-24.json` | ADR-014 |
| 4 | Drive authorization, resumable upload, checksums, revoke behaviour | | | | |
| 5 | All-files vs picker strategy; Play declaration outcome | 2026-10-24 | Documentation only (no device) | **Research done; declaration outcome unknown (not submitted).** Photos cannot justify all-files access (invalid use). Backup eligibility for `READ_MEDIA_*` is not confirmed by the official text. Recommendation: build for `READ_MEDIA_*` + SAF trees, keep picker-only working, defer all-files access. Draft declaration text and open items in `docs/spikes/spike5-permission-strategy.md` | ADR-007 |
| 6 | MediaStore restore with dates/paths | 2026-10-26 | **Emulator only** (Pixel 9 Pro, Android 37, Asia/Kolkata); phone, sample video and Android 11 still open | **Partial.** Bytes restore identically (25 images, 7 documents); no permission needed; clash gets a " (1)" suffix; a file pending in MediaStore is invisible to other apps until finalised. **`DATE_TAKEN` and `DATE_MODIFIED` set through `ContentValues` are discarded**; setting the file's own modified time while pending fixes `DATE_MODIFIED`, and together with EXIF also gives the right `DATE_TAKEN`. Images may only go to `DCIM/` or `Pictures/` (others throw). Names are changed silently (slash to underscore, extension appended, 255 chars cut to 235). SAF documents: modified time cannot be set. Details and caveats: `docs/spikes/spike6-restore.md` | Informs SPEC §8.10; no ADR changed |
| 7 | Keystore + Auto Backup behaviour on device loss | | | | |
| 8 | FGS `dataSync` timeout handling | | | | |
| 9 | User-initiated data transfer jobs | | | | |

## Research round 2 (to do)

Passkeys / Credential Manager sync and recovery; authenticator app migration options; Android 16/17 storage changes in detail; Play Console requirements for new personal developer accounts; WhatsApp official help pages; Samsung/Xiaomi/OnePlus migration tools; legal/privacy requirements for target markets; cost model inputs.
