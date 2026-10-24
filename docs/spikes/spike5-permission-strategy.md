# Spike 5 — file-access permission strategy

Date: 2026-10-24. Type: documentation research, no code. Question (SPEC §16 Phase 0, spike 5): for a backup app, all-files access (`MANAGE_EXTERNAL_STORAGE`) or picker/SAF-only, and what would the Play declaration say?

**Method and limits.** I read the official pages listed in §6 on 2026-10-24 through a fetch tool that returns a model-written summary of each page, not the raw text. Quoted fragments are from those summaries. Before anything is filed in Play Console, re-read the live policy pages yourself. No Play Console declaration was submitted, so **approval for any permission below is unknown**.

## 1. What the official pages say

| Topic | Finding | Source |
| --- | --- | --- |
| All-files access: backup apps | "Backup and restore" is a listed permitted use, but it "must have a need to automatically access multiple directories outside of its app-specific storage space for the purpose of backup and restore". Every listed use is "Subject to Google Play review and approval". | Play policy 10467955 |
| All-files access: media | **"Media Files access" is listed as an invalid use**, as is "any file selection activity where the user manually selects individual files". The list is not exhaustive. Photos and videos belong to MediaStore. | Play policy 10467955 |
| All-files access: declaration | Core-functionality apps must complete the Permissions Declaration Form and explain why SAF or MediaStore is not sufficient. The page gives no review time and mentions no video. Misuse may lead to removal or account termination. | Play policy 10467955 |
| All-files access: effect | Read/write to shared storage, the `MediaStore.Files` table, SD/OTG roots; write to internal directories except `Android/data` and most of `Android`. Other apps' private directories stay closed. | Android docs, manage-all-files |
| SAF tree limits (Android 11+) | A tree cannot be the internal storage root, a reliable SD volume root, **the `Download` directory**, `Android/data` or `Android/obb`. Iterating many files in a tree "might" be slow. The page states no limit on persisted grants. Persisted access is lost if the document is moved or deleted. | Android docs, documents-files |
| Other apps' files in Downloads | Reading a `MediaStore.Downloads` file the app did not create requires the Storage Access Framework. | Android docs, media |
| Own media | Android 10+: no storage permission is needed to read or modify media the app owns. After uninstall and reinstall, `READ_EXTERNAL_STORAGE` is needed to reach files the app created before. | Android docs, media |
| Writing media | `MediaStore` insert with `DISPLAY_NAME`, `RELATIVE_PATH` (documented as a hint) and `IS_PENDING` 1 then 0; do not use the `DATA` column. | Android docs, media |
| Photo/video permissions | `READ_MEDIA_IMAGES`/`VIDEO` only if system pickers are not sufficient for core functionality; gallery apps are the named example. **The word "backup" does not appear in the photo/video policy** (it does in the contacts policy). Apps that are denied must make "a reasonable effort" to accommodate those users. Full compliance has been mandatory since 28 May 2025. | Play policy 14115180 |
| Partial access, Android 14+ | With `READ_MEDIA_VISUAL_USER_SELECTED` the user can share chosen items only; that access "expires eventually"; the app must not assume full access or an empty library; the user can switch access in Settings. Without declaring it, the app runs in a compatibility mode where the grant lasts only for the session. | Android docs, partial access |

## 2. What this changes in our plan

1. **All-files access cannot be justified by photos and videos.** Only by non-media files in several directories that SAF cannot reach. Today that is mainly the `Download` root and the storage roots.
2. **Photo/video backup eligibility is not confirmed anywhere in the official text.** The argument available to us is the "picker is technically insufficient" clause: a backup must run unattended, incrementally and across the whole library, which a per-session picker cannot do. Only a submitted declaration will show whether Google accepts that. This corrects a note in `PLATFORM_CONSTRAINTS.md` that said the text did not name backup apps: it names neither backup nor sync.
3. **Restore writes need no permission** on Android 10+ when using `MediaStore` inserts, and re-reading the files just written in the same install is covered by ownership. Verification after an app reinstall is not: that would need `READ_EXTERNAL_STORAGE` (effectively `READ_MEDIA_*` on newer versions, **[verify]**).
4. **Partial photo access is real and must appear in coverage.** If the user shares only some photos, the report says so and never calls the rest protected (invariant 2).

## 3. Options compared

| | A. Picker/SAF only | B. `READ_MEDIA_*` + SAF trees | C. B + all-files access |
| --- | --- | --- | --- |
| Photos and videos | User-chosen items only; no unattended backup | Whole library if granted; partial access handled | Same as B |
| Documents in granted trees | Yes | Yes | Yes |
| `Download` root, storage roots | No | No (guide the user to move or select files) | Yes |
| Play review risk | None | **Medium: eligibility unconfirmed** | **High: media is invalid, documents case is narrow** |
| Fits the product promise | Poor: most users' photos are not protected automatically | Good for the main asset | Best coverage, worst approval odds |

## 4. Recommendation (a proposal for ADR-007; the owner decides)

- **Build for B.** Photos and videos through `MediaStore` with `READ_MEDIA_IMAGES`/`VIDEO` and the partial-access path; documents through persisted SAF trees. File the `READ_MEDIA_*` declaration early, because approval is the unknown that can block the product.
- **Always keep A working.** If the declaration is refused or the user declines, the app stays useful through the picker, and coverage says plainly how much is protected.
- **Do not request all-files access in v1.** Keep `AllFilesAdapter` as a later, separate decision. Report the `Download` root as "not covered" with a guide, never silently.
- **New permissions need your approval first** (CLAUDE.md): `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`, and `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"`. They are not in the app today, and the debug spike builds that need them (spike 1) are the first place they would appear.

## 5. Draft declaration text for `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`

Draft only; not submitted. Review against the live form and the final app before use.

> **Core functionality.** The app's single purpose is to make an independent, end-to-end encrypted copy of a person's photos and videos (and other files they choose) so they can be recovered on a replacement phone after loss, theft or damage.
>
> **Why a system picker is not sufficient.** Protection only works if it runs without the user choosing files each time: new photos and videos must be detected and copied incrementally in the background across the whole library, and the app must later check which items have and have not been protected. The photo picker returns a user-selected set per request and cannot provide continuous, library-wide, unattended backup.
>
> **Data handling.** Files are encrypted on the device before upload; filenames and contents are not sent unencrypted; the app has no analytics.
>
> **If the user declines.** The app remains usable with the photo picker and partial access, and reports exactly how much is protected.

Open point for the final wording: say what is uploaded and where (the user's own Google Drive), once spike 4 fixes that.

## 6. Still unverified

- Whether Google approves a backup app for `READ_MEDIA_*`, and review timing. Only a submission answers this.
- The maximum number of persisted SAF grants (the page states none). Test in spike 2.
- Whether `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` behaves as I remember (page does not list it). Read the `Settings` API reference when all-files access is revisited.
- What a non-media document owned by another app looks like through `MediaStore.Files` without all-files access, per Android version. Test in spike 1 or 2.
- Play Console rules for new personal developer accounts (still on the research round 2 list).

## 7. Sources

- https://support.google.com/googleplay/android-developer/answer/10467955 (all-files access policy)
- https://support.google.com/googleplay/android-developer/answer/14115180 (photo and video permissions policy)
- https://developer.android.com/training/data-storage/manage-all-files
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access
