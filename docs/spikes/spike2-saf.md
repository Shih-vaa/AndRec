# Spike 2 — SAF tree traversal at 50k files and persisted grants

Date: 2026-10-24. Harness: `spikes/saf` (throwaway, never shipped, no permissions, no dependencies). Spec: SPEC §16 Phase 0 spike 2, §8.1.

## Pass/fail criteria (written before the first run)

| # | Criterion |
| --- | --- |
| S1 | The listing returns exactly as many files as were created |
| S2 | The fast (`DocumentsContract` bulk) method lists 50,000 files in ≤ 60 s |
| S3 | The bulk method is ≥ 3x faster than the `DocumentFile`-style method |
| S4 | Peak Java heap ≤ 64 MiB while listing 50,000 files; no crash; runs off the main thread |
| S5 | The grant is still listed and a listing still works after an app force-stop **and** after a phone reboot |

Reported, not judged: cost of opening and reading files through the grant, behaviour when the folder is renamed, battery temperature.

## Method

- Device: Samsung Galaxy M31 (SM-M315F), Android 12, wireless debugging.
- Data: 50,000 synthetic 1 KiB files (random bytes) in `Documents/SpikeSaf`: 100 directories, each holding 400 files plus a sub-directory of 100 more (201 directories counting the root and the sub-directories; 200 below the root). Created on the phone by `spikes/saf/tools/gen_docs.sh`. Creation ran at about 15 files/s with one worker and about 35 files/s with two, because Android indexes every new file.
- Grant: the owner picked `Documents/SpikeSaf` once in the system folder picker; the app called `takePersistableUriPermission` with read and write.
- Fast method: one `DocumentsContract` children query per directory, reading id, name, type, size, modified time and flags in one pass, iteratively.
- Slow method: **my hand reconstruction of how `androidx.documentfile` behaves (the library source was not read)**: list children by id, then one query per property per child (type, name, size, modified). Capped at 2,000 files because the full set would take far too long. This is an approximation of the library, not the library.
- Open cost: open and read 1,000 random files with 1 thread and another 1,000 with 4 threads through the tree grant.
- No file names or contents are logged; the files were synthetic anyway.

## Results

### Against the criteria

| # | Result | Verdict |
| --- | --- | --- |
| S1 | **50,000 files and 200 directories**, exactly, in every full pass | **Pass** |
| S2 | **220.3 s** (single pass with progress logging) and **229.7 s** (inside the full run): about **218 to 227 files/s** | **Fail**: about 4x over the 60 s target |
| S3 | Bulk 218 files/s against **17 files/s** for the per-property method (8,217 queries for 2,000 files, 118.9 s): bulk is about **12.8x faster** | **Pass** |
| S4 | Peak Java heap **14 MiB** of 256; total PSS 69 MiB; no crash; the work ran on a background thread | **Pass** |
| S5 | Force-stop and restart: grant listed (read + write), listing OK. **After a reboot** (boot count 85 → 86, 3 min uptime, phone unlocked once): grant still listed with read and write, and the listing returned all 100 top-level directories | **Pass** |

The 60 s target for S2 was my own guess before any measurement. The honest reading is that `DocumentsContract` is the right tool (S3) but is slow on this phone in absolute terms: about 4.4 ms per file, or roughly 1.1 s per 250-file directory. Where that time goes (the provider's per-file work on the filesystem is my hypothesis) was **not** isolated.

### Reported, not judged

- **Opening files through the grant**: 1 thread **18 files/s** (about 55 ms per file); 4 threads **87 files/s** (4.8x). That is about three times the per-file cost measured through MediaStore in spike 1 (about 19 ms).
- **Rename of the folder** (done from the shell, outside the system's own document handling), on a folder of 50,000 files: the rename itself took minutes because Android updates every entry. While renamed away, the grant stayed **listed** (read + write) and the root listing returned **"ok" with 0 children and no error**. After renaming it back the same grant listed all 100 directories again, and all 50,000 files were intact. Documents are found by path here, so moving the folder away and back restores access.
- Battery 67% → 66%, temperature 33.8 → 33.1 °C over the full run (screen on, unplugged).

### Two things that did not go to plan

1. **First full run never finished.** The app was sent to the background (Home) soon after it started. The process stayed alive but idle (about 12 s of CPU over 15 minutes, state "cached", not frozen) and produced no result. I did not diagnose it. It is a reminder that long work in a plain thread inside an app that has left the screen is unreliable on this phone, which is what spike 8 (foreground services) is for. The reruns kept the app on screen.
2. My first progress-less version gave no way to see what was happening; I added progress logging and a single-pass mode.

## What this means (proposals, none of it decided)

- Use `DocumentsContract` bulk queries for SAF trees; do not use the `DocumentFile`-style per-property pattern.
- Plan for slow SAF inventories: roughly 4 minutes to list 50,000 files and, extrapolating, about 10 minutes to hash them with 4 workers (each open costs about 55 ms), against minutes for the same count via MediaStore. These two figures are extrapolations from 1,000-file samples.
- **An empty listing is not proof the folder is empty.** After a rename the app got "ok, 0 children". The inventory must compare against the last snapshot and report "folder missing or moved, reselect it" instead of marking 50,000 files deleted (honesty invariant 2 and 6).
- Persisted grants survived restart and reboot on this phone, so the persisted-tree model in ADR-007 looks workable.

## Not covered

- The maximum number of persisted grants (the Android page states none; testing it needs hundreds of manual picks).
- Renaming or moving through a file-manager app, which may revoke the grant instead.
- Other phones, Android 13+, SD cards or USB storage, real documents (sizes), cold-start effects after a very long idle.
- Why the listing is slow, and whether a different listing approach could be faster.

## Cleanup

The 50,000 files, the generator script and their MediaStore rows were removed from the phone. Verified afterwards: no rows with `SpikeSaf` in their path, `Documents` back to its original contents, 53 GB free as before, 367 videos, and 2,195 images, **one more than the 2,194 at the start**. The extra row is a screenshot owned by System UI (`DCIM/Screenshots`), taken during the session; it is not one of the test files. The spike app, which still holds a grant to the now-deleted folder, remains installed until the owner decides.
