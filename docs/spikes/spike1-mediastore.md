# Spike 1 — MediaStore enumeration and SHA-256 at 10k+ files

Date: 2026-10-24. Harness: `spikes/mediastore` (throwaway, never shipped, no third-party dependencies). Spec: SPEC §16 Phase 0 spike 1, §8.1, §8.2.

## Pass/fail criteria (written before the first run)

| # | Criterion | Threshold |
| --- | --- | --- |
| C1 | Enumerate ≥ 10,000 MediaStore rows (metadata used by the cheap fingerprint) | median of 3 runs ≤ 5 s |
| C2 | SHA-256 throughput, first read of files, ≤ 2 worker threads | ≥ 30 MiB/s (about 1 h per 100 GB) |
| C3 | Memory while hashing | peak Java heap ≤ 64 MiB, independent of file count; no OOM |
| C4 | Errors reading the synthetic files | 0 |
| C5 | Phone temperature rise over the run | ≤ 8 °C |

Battery drop is **reported, not judged**: the screen is kept on and the phone is unplugged, which inflates it.

## Method

- Device: Samsung Galaxy M31 (SM-M315F), Android 12, over wireless debugging, unplugged.
- Test data: synthetic files created **on the phone** by `spikes/mediastore/tools/gen_synthetic.sh` under `Pictures/SpikeSynthetic` and `Movies/SpikeSynthetic`: random bytes, unique per file, JPEG header and footer for `.jpg`. Removed after the run. No personal files are read or hashed.
- Enumeration also walks the phone's **own** library rows (counts and timing only; names are never logged or stored).
- Two enumeration methods are compared: one cursor over the whole collection, and keyset batches of 1,000 rows ordered by `_id`.
- Hashing uses `ContentResolver.openInputStream` and a 1 MiB buffer, with 1, 2 and 4 worker threads, each configuration on a **different** third of the files so no configuration re-reads another's files. A fourth pass re-reads one third to show the page-cache effect.
- Limitations known up front: the page cache cannot be dropped without root, so "first read" numbers may be flattered because the files were just written; only one device; synthetic file sizes (JPEG 50–500 KiB, video 1–7 MiB) are not a real library.

## Results

Data set: **10,835 synthetic files, 5,103 MiB** (10,335 `.jpg` of 50–500 KiB, 500 `.mp4` of 1–7 MiB) on top of the phone's own 2,561 media rows (2,194 images, 367 videos). Raw data: `docs/spikes/spike1-sm-m315f-*-2026-10-24.json` (no file names or hashes recorded). Phone: unplugged, screen on, 32.6 °C at start.

### Against the criteria

| # | Result | Verdict |
| --- | --- | --- |
| C1 | 13,396 rows enumerated in a median **439 ms** with one cursor (30,500 rows/s); keyset batches of 1,000 took 546 ms. The very first pass was 1,153 ms (cold). | **Pass** |
| C2 | First-read hashing: **1 thread 23.4 MiB/s, 2 threads 45.7, 4 threads 89.2** (49, 98, 190 files/s). | **Pass at 2 threads**; 1 thread misses the 30 MiB/s bar |
| C3 | Peak Java heap **37 MiB** (this pass also held the 10,835-row list), 11–17 MiB in later passes; native heap 13 MiB; total PSS 53 MiB at the end. No OOM. | **Pass**, but only one library size was tested |
| C4 | **0 errors** across all 10,835 files (each hashed once, one third twice). | **Pass** |
| C5 | Battery temperature 32.6 → 33.6 °C (+1.0 °C) over a 215 s run. | **Pass**, short run only |

Reported, not judged: battery 86% → 85% over 215 s with the screen on. Re-reading a third of the files a second time ran at 21.1 MiB/s against 23.4 MiB/s on the first read, so the page cache did **not** visibly flatter the first-read numbers.

### What limits the speed (follow-up runs, same phone)

| Test | Result |
| --- | --- |
| SHA-256 on an in-memory buffer, no I/O | about **1,000 to 1,080 MiB/s** |
| Read-only via `openInputStream` (no hashing), 600 files | 23.7 to 28.9 MiB/s over two runs |
| Hash via `openInputStream`, same 600 files | 27.4 to 29.4 MiB/s |
| Hash via `openFileDescriptor`, same files | 21.7 to 28.8 MiB/s |
| Read-only, 300 **small** files (about 0.32 MiB each) | 16.6 MiB/s = **52 files/s** (about 19 ms per file) |
| Read-only, 60 **large** files (about 3.7 MiB each) | **105 MiB/s** = 28 files/s |

Reading: hashing costs almost nothing. The read path through MediaStore is the limit, and for ordinary photo-sized files it is dominated by a **per-file cost of roughly 19 ms**, not by bytes; large files stream at about 105 MiB/s on one thread. The two ways of opening a file performed alike within run-to-run noise (about ±20%). Throughput scaled close to linearly with threads (1 → 4 threads: 3.8x) on the small-file mix. I did not isolate where the per-file cost comes from (the MediaStore/FUSE layer is my hypothesis, **unverified**).

### Rough projections (extrapolations, not measurements)

- 100,000 photos of 3 MiB: about 19 ms + 3 MiB at 105 MiB/s, so roughly 48 ms per file, about **80 minutes on one thread**; about **20 minutes on four** if the 3.8x scaling also holds for large files (untested; flash bandwidth may cap it).
- Enumeration is not a concern: 100,000 rows would be seconds at the measured 30,000 rows/s, if it scales linearly (untested).
- Later scans only hash new or changed files (SPEC §8.1 fingerprint), so the long pass happens once.

### Other findings

1. **Files written straight to the filesystem are invisible to other apps until scanned.** The synthetic files were created from the shell; MediaStore listed them with `is_pending=1` and the spike app saw **0 of 10,835** until a volume scan cleared the flag. Relevant to Phase 3 restore writers (spike 6): write through `MediaStore` with `IS_PENDING` 1 then 0, never plain file writes, and test fixtures need a scan.
2. The `MediaStore` query path with `RELATIVE_PATH`, `SIZE`, `DATE_MODIFIED`, `MIME_TYPE` and `DISPLAY_NAME` works with only `READ_EXTERNAL_STORAGE` granted on Android 12.
3. A transient wireless-debugging disconnect mid-run did not affect the run; the results stayed in the app and logcat.

### Cleanup

All synthetic files and their MediaStore rows were removed; the phone is back to 2,194 image and 367 video rows and 53 GB free, as before. The spike app is still installed.

### Not covered

Android 13+ permission behaviour (`READ_MEDIA_*`) and partial photo access, a larger library, SAF (spike 2), real photos (compressed content makes no difference to hashing but file sizes differ), cold-cache reads (not possible to force without root), and any device other than one Galaxy M31.

## Verdict

**Spike 1 passes on the M31 with the caveats above.** Design consequences, proposed: run hashing with at least 2 and preferably 4 worker threads (battery and heat to be judged in Phase 1); expect first-inventory time to depend on file count more than on bytes; do not assume hashing is the cost.
