# Spike 6 — restoring into MediaStore and SAF with names, paths and dates

Date: 2026-10-26. Harness: `spikes/restore` (throwaway, never shipped, **no permissions**, no dependencies). Spec: SPEC §16 Phase 0 spike 6, §8.10.

**Status: partly done.** Everything below was measured on **one device: the Pixel 9 Pro emulator, Android 37, time zone Asia/Kolkata**. The Galaxy M31 (Android 12) was busy, so the phone run is still to do. The sample-video test is waiting for a video from the owner. Android 11 (our minimum) is not covered.

## Criteria (written before the first run)

| # | Criterion |
| --- | --- |
| R1 | Restore into `DCIM/Restored/` works with no permissions |
| R2 | Every restored file matches the source SHA-256 |
| R3 | The requested capture date (`DATE_TAKEN`) comes back exactly when set; when unset, record whether EXIF is used |
| R4 | The requested modified date is honoured, or the limitation is recorded |
| R5 | A name clash never overwrites an existing file |
| R6 | Other apps cannot see a file while it is pending and can see it afterwards |
| R7 | Every failure is recorded with the exact exception, never silent |

## Method

- The app builds JPEGs itself: a small bitmap plus an EXIF block I wrote byte by byte (DateTimeOriginal, and in a second form also DateTime). Real camera files were not used, so EXIF behaviour on real photos is **not** confirmed.
- Restore recipe under test: `MediaStore.insert` with `IS_PENDING` 1, `DISPLAY_NAME`, `MIME_TYPE`, `RELATIVE_PATH`; write the bytes; update `IS_PENDING` to 0; read everything back, hash it and compare.
- Part 6b writes documents into a folder picked through the system picker with `DocumentsContract.createDocument`.
- Another-app view (R6): the spike 1 app, which enumerates MediaStore, counted rows before, during and after.
- File names are synthetic and shown only as lengths. Raw data: `docs/spikes/spike6-emulator-*-2026-10-26.json`.

## Results (emulator only)

| # | Result | Verdict |
| --- | --- | --- |
| R1 | Inserts into `DCIM/Restored/`, `Pictures/Restored/`, `Pictures/Restored/Sub/Deep/` and `DCIM/Camera/` all worked with **no permission**. | **Pass** |
| R2 | All **25** restored images and **all 7** restored documents re-read byte-identical (SHA-256). | **Pass** |
| R3 | **A capture date set through `DATE_TAKEN` is thrown away.** It is stored while the row is pending and replaced by NULL when `IS_PENDING` becomes 0, whether set at insert, in the same update that clears pending, or later (a later update returned 0 rows). EXIF alone did not fill it in either. **It does appear when the file's own modified time is set to the old date** (see "What worked"). | **Fail as specified; a workaround exists** |
| R4 | `DATE_MODIFIED` set through `ContentValues` is ignored (it becomes the insertion time). **Setting the file's modified time before clearing pending works**: the row then shows the old date. `DATE_ADDED` is always the insertion time and could not be set. | **Fail as specified; a workaround exists** |
| R5 | Restoring the same name twice gave the second file a " (1)" suffix, a different URI, and the first file's bytes were unchanged. | **Pass** |
| R6 | Another app counted 56 rows before, **56 while the file was pending, 57 after** it was finalised. | **Pass** |
| R7 | The four refused folders each returned an `IllegalArgumentException` with a clear message; nothing failed silently. | **Pass** |

### What worked for dates (strategies in `dates`, same fixture, wanted 2019-07-14 10:30)

| Strategy | `DATE_TAKEN` after finalising | `DATE_MODIFIED` |
| --- | --- | --- |
| EXIF only, nothing set (A, B) | NULL | insertion time |
| `DATE_TAKEN` set at insert, with or without EXIF (C, D) | NULL | insertion time |
| `DATE_TAKEN` set by update after clearing (E), or in the clearing update (K) | NULL | insertion time |
| **File mtime set while pending** (F, no EXIF) | NULL | **2019-07-14** |
| File mtime set after clearing, then rescan (G) | NULL | **2019-07-14, after a few seconds** |
| Capture date set while pending plus a matching, 1-day or 10-day-off file mtime, **no EXIF** (M, N, P) | NULL | 2019-07-14 |
| **EXIF (DateTime + DateTimeOriginal) plus file mtime set to the old date** (L, Q) | **2019-07-14** | **2019-07-14** |

Reading: the scanner derives `DATE_TAKEN` from EXIF, but in these runs only when the file's own modified time was also set to a matching old date. A restored file that keeps its original bytes (EXIF inside) and gets its original mtime while pending ends up with both dates right. A file with **no EXIF** (screenshots, some downloads, possibly videos) ends with `DATE_TAKEN` NULL whatever I tried. I did not find why the scanner behaves this way; "it distrusts EXIF that is far from the file's mtime" is my guess and is **unverified**.

Two more details: the EXIF time has no time zone and MediaStore stored it as 10:30 **UTC** on a device set to IST, so a gallery may show it shifted by the offset; and only the two-tag EXIF form was tested with the mtime route (the DateTimeOriginal-only form was tested without it).

### Names, folders and documents

- **Allowed folders for an image:** `DCIM/…` and `Pictures/…`, nested too. **Refused** (`IllegalArgumentException`, "Primary directory … not allowed"): `Documents/`, `Download/`, `Movies/`, and a bare `Restored/`.
- **Names are changed silently:** a `/` becomes `_`; a name with no extension gets `.jpg` appended from the MIME type; a **255-character name was cut to 235**; non-ASCII and upper-case extensions were kept. A restore must read back the stored name and record it next to the original name instead of assuming they match.
- **Documents (6b), picked folder:** matching name and MIME are kept; a clash gets " (1)"; an unknown extension kept its name but the stored MIME became `chemical/x-xyz`; a missing extension gets `.txt` appended; **a MIME type that disagrees with the extension appends another extension** (a `.txt` created as `image/jpeg` gained `.jpg`), so the writer must pass a MIME type that fits the name. **The modified time cannot be set**: updating `last_modified` returns `UnsupportedOperationException: Update not supported`, and the stored value is the creation time. Nested folders worked, and all test documents were deleted afterwards.

## What this means (proposals, none decided)

1. **Restore recipe for photos:** insert pending → write the original bytes (EXIF intact) → set the file's modified time to the original while still pending → clear pending → read back name, dates and hash and store them in the report.
2. Never rely on `DATE_TAKEN` or `DATE_MODIFIED` in `ContentValues`. If something must be reported honestly, report what was read back (invariant 2).
3. The restore report must be able to say "restored, but date could not be preserved" for files without EXIF, and for documents (no way to set the modified time through SAF).
4. Restored folders for photos are limited to `DCIM/` and `Pictures/`; documents go to a picked folder, not MediaStore.
5. Record the stored name and the original name for every file (silent changes seen above).

## Still to do

- **Galaxy M31 (Android 12):** rerun every mode there; the date behaviour and the UTC/IST reading may differ with the older MediaProvider. Needs the phone connected.
- **Sample video:** needs a video from the owner placed in the local, uncommitted folder `spikes/restore/local-fixtures/`; the `video` mode is built but has not run.
- **Android 11** (minSdk 30): not covered, would need a system image download.
- Real camera photos, the `DateTimeOriginal`-only EXIF form with the mtime route, other makers' phones, reinstall behaviour.

## Cleanup

All test rows, folders and documents were removed from the emulator (0 rows with the spike's names remain). Nothing was installed or created on the owner's phone in this part.
