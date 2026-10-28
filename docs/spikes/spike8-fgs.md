# Spike 8 — dataSync foreground service timeout and resume

Date: 2026-10-26. Harness: `spikes/fgs` (throwaway, never shipped, no dependencies; debug-only permissions per ADR-016). Spec: SPEC §12, ADR-010, SPEC §16 Phase 0 spike 8.

**The main results below were measured on the Pixel 9 Pro emulator, Android 37.** The Galaxy M31 runs Android 12, where the 6-hour rule does not exist, so it can only show the background-freeze baseline; two short phone runs are recorded in the section "Galaxy M31 (Android 12), partial" and the longer ones are still open. The 6-hour limit was **shortened to 60 s** on the emulator with the documented `device_config` switch.

## What the official docs say (read 2026-10-26, before testing)

- The limit applies to apps targeting Android 15+ (we target 36), to `dataSync` and `mediaProcessing`, 6 hours per 24, per type, shared by all services of that type, counted while the app is in the background.
- At the limit Android calls `Service.onTimeout(startId, fgsType)`; the service must call `stopSelf()` "within a few seconds" or the app crashes with a `RemoteServiceException`.
- Starting another `dataSync` service afterwards throws `ForegroundServiceStartNotAllowedException`; the timer resets when the user brings the app to the foreground.
- Test switch: `adb shell device_config put activity_manager data_sync_fgs_timeout_duration <ms>`.

Sources: https://developer.android.com/develop/background-work/services/fgs/timeout and https://developer.android.com/develop/background-work/services/fgs/service-types (read through a summarising tool; re-read before relying on exact wording).

## Criteria (written before the first run)

| # | Criterion |
| --- | --- |
| F1 | A foreground service keeps ticking at 90% or better of the expected rate while the app is in the background, until its timeout |
| F2 | `onTimeout` is called for `dataSync` near the configured time and `stopSelf()` ends it with no crash and progress saved |
| F3 | Ignoring `onTimeout` produces the documented fatal exception (recorded, not judged) |
| F4 | A restart from the background after exhaustion is refused with `ForegroundServiceStartNotAllowedException`, caught and recorded, no crash |
| F5 | After the app returns to the foreground a restart works and resumes with no lost or repeated chunk |
| F6 | After a force-kill the job resumes from the last saved chunk, repeating at most one |
| F7 | Whether a plain thread stalls in the background (recorded, not judged) |

One wording mistake of mine: F1 said "expected rate", meaning 5 ticks/s (200 ms pause). The job also does 1 MiB of hashing and a synchronous progress write per tick, so even in the foreground it runs at about 3.3 to 5 ticks/s on this emulator. F1 is therefore judged against the **foreground rate in the same run**, which is what it was meant to say.

## Method

- Job: hash 1 MiB, save the chunk index synchronously, pause 200 ms. Real CPU work, so a frozen process shows as a gap. Every event is timestamped in `files/events.jsonl`.
- The service is a `dataSync` foreground service started from the visible activity; the app is then sent to the background with the Home key.
- The restart attempt from the background comes from a broadcast receiver triggered by `adb`.
- Doze was forced with `dumpsys deviceidle force-idle`, with the battery marked unplugged and the screen off; all test overrides were reverted afterwards.

## Results

| # | Result | Verdict |
| --- | --- | --- |
| F1 | Service, **screen off, forced deep idle**, 116 s: 536 ticks, **4.62/s against 4.96/s in the foreground (93%)**, no gap. | **Pass** |
| F2 | With a 60 s limit, `onTimeout(startId 1, fgsType 1)` arrived **62.7 s after the app was backgrounded** (67.3 s after service start); `stopSelf()` ended the service at once and the worker stopped 0.2 s later with chunk **262** saved. A "tap to continue" notification was posted. No crash. | **Pass** |
| F3 | Ignoring it: the process was killed **10.0 s after `onTimeout`** with `RemoteServiceException$ForegroundServiceDidNotStopInTimeException: A foreground service of type dataSync did not stop within its timeout`. The job kept ticking until the crash. "A few seconds" is 10 s here. | **Confirmed** |
| F4 | A start from the background after the timeout was refused with `ForegroundServiceStartNotAllowedException: startForegroundService() not allowed`, caught, no crash. **A control with no earlier timeout got the identical refusal and message**, so this test shows Android's ordinary background-start rule and **cannot isolate the time limit**. | **Pass as worded; time-limit path not isolated** |
| F5 | After bringing the activity to the foreground, the service started and logged `RESUME_FROM 262`; the next tick was 263. No loss, no repeat. | **Pass** |
| F6 | Force-stop after tick 59; the relaunched service logged `RESUME_FROM 59` and continued at tick 60. Nothing lost or repeated. | **Pass** |
| F7 | **On the emulator the plain thread was frozen about 70 s after the app went to the background**, with the screen on and no Doze (ticks stopped at 72 s; `ActivityManager: freezing <pid> <package>` appears in the log at that moment). In an earlier run with the screen off and forced idle the thread stopped at about 70 s and stayed stopped after the screen was woken. | **Recorded** |

## What this means (proposals, none decided)

1. **Long work should not run in a plain thread or coroutine of the app process.** On the emulator Android's cached-app freezer stopped it about 70 s after the app left the screen. **The Galaxy M31 did not freeze it in 4 or 5 minutes**, so this is not universal; whether it freezes later (spike 2 stalled for 15+ minutes) is untested. Treat a foreground service (or a user-initiated job, spike 9) as the safe design, and the link to the spike 2 stall as **unverified**.
2. **Implement `onTimeout` from day one:** save progress, call `stopSelf()` immediately (the deadline observed was 10 s), post a "backup paused, tap to continue" notification, and resume when the user opens the app.
3. **A first backup that takes days cannot be fully automatic.** On Android 15+ it pauses after about 6 hours in the background and only continues once the user opens the app. Onboarding and the status screen must say so plainly and show "paused, waiting for you" instead of a misleading "backing up" (honesty invariants 1 and 2). A WorkManager or user-initiated-job fallback is a design question for Phase 2 and spike 9; WorkManager would be a new dependency and needs your approval.
4. **Persist progress at every chunk or file boundary.** Saving after each chunk made kill-and-resume lose nothing; the cost (a synchronous write per chunk) must be weighed against throughput in Phase 2.
5. Treat a refused start from the background as a normal outcome, never a crash: catch it and show the paused state.


## Galaxy M31 (Android 12), partial, 2026-10-28

SM-M315F, Android 12 (no 6-hour limit exists here), wireless debugging, unplugged at 41 to 43%, 28.6 °C. Only the plain-thread baseline was run so far. The event logs of these two runs were overwritten by later runs, so the figures below come from the analyser output during the session and are **not** saved as files.

| Run | Result |
| --- | --- |
| Plain thread, app in the background, **screen on**, 240 s | 1,099 ticks in 240.6 s = 4.57/s against 4.80/s in the foreground (95%). No freeze and no `freezing` line in the log. |
| Plain thread, app in the background, **screen off** (via `adb`), 5 min | Ticks continued to the end (last tick 319 s after backgrounding, 1,359 ticks). 21 short gaps of 1.4 to 2.5 s in the first 30 s after the screen went off, then a steady rate of about 4.5/s. No freeze, no `freezing` line. |
| Plain thread, screen off, **16 min** | **Aborted.** The owner woke the screen and took a phone call, so the run no longer matched its premise; stopped without waiting and its data discarded. |

Reading: within 5 minutes this phone never froze the plain thread, unlike the emulator (70 s). So the freeze is not universal and the spike 2 stall (15+ minutes, cause unknown) is **not explained** by these runs.

Still to run on the phone, when it is idle: the 16-minute screen-off baseline, the same job as a foreground service, and kill/resume.

## Not covered

- Real Android 15 or 16 hardware, other makers' battery managers, and the Galaxy M31 (needs the phone; only the freezer baseline and kill/resume apply there because it runs Android 12).
- The real 6-hour value and how the budget accumulates across several services (I used 60 s and one service).
- Tapping the notification (I started the activity from `adb` instead), and the case where `POST_NOTIFICATIONS` is denied.
- Whether the freeze timing (about 70 s) is the same on other Android builds; it is likely build- and maker-specific.
- Raw logs: only the last run's events were kept (`docs/spikes/spike8-emulator-service-under-doze-2026-10-26.jsonl`, ticks thinned to every 25th). The other figures above were read from the analyser output during the session and not saved as files.

## Cleanup

Emulator only. The `device_config` override and the sync-disable switch were removed, Doze and battery overrides reset, and the emulator stopped. Nothing was installed on the owner's phone in this spike.
