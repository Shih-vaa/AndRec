# SPEC — Android Personal Data Recovery & Continuity App

Status: v0.1 draft, 23 Oct 2026. Covers the **entire product**, not only the MVP. Phases and acceptance criteria are in §16; start with Phase 0.

Legend used throughout:
- **[Verified]** confirmed against official docs or a source listed in `PLATFORM_CONSTRAINTS.md`.
- **[Proposed]** design choice by the author, not yet validated.
- **[Spike]** must be proven by a proof-of-concept before it is relied on.
- Feasibility tiers: **F1 Fully supported**, **F2 Conditionally supported**, **F3 Guided recovery only**, **F4 Unsupported**.

---

## 1. Vision

Smartphone replacement should be a routine migration, not a personal-data disaster. When a phone is lost, stolen, or destroyed, an ordinary non-technical person should be able to install the app on a new Android phone, sign in, and get back everything that was protected, with an honest report of everything that was not.

The product combines: (1) a personal data inventory, (2) an automated encrypted backup manager, (3) a cross-device migration assistant, (4) a recovery planning and verification engine, and (5) an honest "gap" tracker for data no app can reach.

It is **not** merely "upload files to the cloud", and **not** merely a list of instructions for restoring Google backups. Its differentiator is the unified view: *what did you have, what is protected, what is verified, what is still missing, and exactly what to do about each gap.*

### 1.1 Product promise

> Know what you had. Know what is protected. Know what has been recovered. Know what remains missing.

### 1.2 What the product will never promise

- That all data on every Android phone can be recovered.
- That a phone which was never running the app can be helped after it dies. The product is about **prevention**; onboarding says so.
- Recovery of device-bound secrets (authenticator seeds, passkeys, banking sessions, other apps' private databases) unless the owning app/provider supports it.

## 2. Users and scenarios

Target user: ordinary smartphone owner, not a developer. No terminal, no ADB, no developer options, no folder hunting.

Primary scenarios:
1. **Dead phone.** Screen broken or phone destroyed/lost/stolen. User buys another Android phone (possibly different manufacturer/Android version). Recovery must not depend on the old phone.
2. **Planned upgrade.** Old phone works; user wants confidence that everything moved.
3. **Partial loss.** Accidental deletion, factory reset, app uninstall. Restore specific items.
4. **Multi-device.** User has had several phones; snapshots exist per device.
5. **Account trouble.** Google account inaccessible or storage full; the product must explain what can still be recovered and how.

Persona note for copy: assume low technical vocabulary; many users will be on mid-range devices, limited storage, and metered data. Plan for slow networks and interrupted sessions.

## 3. Core concepts (glossary)

- **PDI — Personal Data Image.** A versioned, structured record of the user's discoverable digital assets and their protection/recovery status. A conceptual model, **not** a disk image and **not** a backup by itself. Stored locally (SQLite) and exported as an encrypted manifest to the storage provider so a new device can retrieve it.
- **Asset.** One tracked item: a file, a contact, a calendar, an account reference, an app-level item. Has a **stable internal ID** that does not depend on path or filename.
- **Asset version.** The state of an asset over a range of snapshots (content hash, size, mtime, location). New content or location → new version.
- **Snapshot.** An immutable point-in-time view of the PDI for a device. Once finalized, never rewritten; later facts are new versions or events.
- **Backup object.** An encrypted stored copy of an asset version in a `ContentStore`.
- **Recovery source.** Where an asset can be recovered from: independent encrypted copy, a cloud-service reference, an app-guided procedure, or none.
- **Integration.** An authorised link to an external service (e.g. Google Drive, Google account sync status).
- **Recovery plan / task.** Ordered, dependency-aware steps generated on the replacement device to restore and verify.
- **Verification record.** Evidence of an integrity or restoration check (method, result, time).
- **Audit event.** Append-only record of important operations.

## 4. The honesty model (state machine of truth)

Two independent axes. Never merge them.

**Protection axis (old device, per asset version):**

| State | Meaning |
| --- | --- |
| `DISCOVERED` | Seen in an inventory. Nothing else is implied. |
| `NOT_BACKED_UP` | Eligible, but no copy exists yet. |
| `UPLOADING` | Copy in progress. |
| `BACKUP_FAILED` | Last attempt failed; reason recorded; retryable. |
| `UPLOADED_UNVERIFIED` | Bytes stored; integrity not yet confirmed. |
| `BACKUP_VERIFIED` | Integrity confirmed by a defined method (see §8.6). |
| `INACCESSIBLE` | We cannot read it (permission, app-private, vault, OEM). Reason recorded. |
| `NEEDS_APP_SPECIFIC` | Recoverable only through the owning app's own mechanism (guided). |
| `EXTERNALLY_HELD` | Content lives in another service we reference but do not copy (e.g. Gmail). **Not** an independent backup. |
| `NO_SOURCE` | No usable recovery source known. |

**Recovery axis (new device, per asset version):**

| State | Meaning |
| --- | --- |
| `RECOVERY_AVAILABLE` | A verified source exists. |
| `RESTORING` | In progress. |
| `RESTORED_VERIFIED` | Present on the new device and verified (hash match). |
| `RESTORED_UNVERIFIED` | Present but not exactly verified (e.g. transcoded; probable match only). |
| `PRESENT_VIA_SERVICE` | Available through a connected service on the new device. |
| `NOT_FOUND` | Not on the new device and no restore performed. |
| `REQUIRES_USER_ACTION` | Guided step needed. |

**Reporting rules**
- A single percentage never stands alone. Reports show counts per state and per category, plus an explicit "could not be inspected" section.
- "100% recovered" is only valid as "100% of items we could inspect"; the UI must show the uninspected categories beside it.
- Match confidence (exact / probable / via service) is always displayed separately.

## 5. Data categories and feasibility

Each category is a **discovery adapter** (§6.3) with declared capabilities. Evidence for the tiers is in `PLATFORM_CONSTRAINTS.md`.

| # | Category | Discover | Protect (independent copy) | Restore | Verify | Tier | Phase |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5.1 | Photos & videos (MediaStore) | Yes (needs media permission w/ Play declaration, or user picker) | Yes, encrypted | Yes (MediaStore insert) | SHA-256 | F2 | 1–3 |
| 5.2 | Documents & downloads (user-selected folders; optionally all-files) | Yes in selected trees; Download root only via all-files access | Yes | Yes (SAF tree or MediaStore) | SHA-256 | F2 | 1–3 |
| 5.3 | Hidden-but-accessible folders | Only if inside a granted tree/all-files scope | Yes | Yes | SHA-256 | F2 | 1–3 |
| 5.4 | Vaults / Secure Folder / app-locked storage | **No** (other apps/OEM own it) | No | Guided via the owning app | Manual | F3 | 4 |
| 5.5 | Gmail / other email | Presence of account only | No (server-held) | Sign-in guidance | Sign-in check | F3 | 4 |
| 5.6 | Contacts | READ_CONTACTS (declaration required) or sync status | vCard export, encrypted | Insert/merge without duplicates | Count + field hash | F2 | 5 |
| 5.7 | Calendars | READ_CALENDAR | ICS export, encrypted | Insert | Count + hash | F2 | 5 |
| 5.8 | SMS & call log | Only as default SMS/Phone handler | Only if default handler | Only if default handler | — | F2/F4 | 5 (decision) |
| 5.9 | Messaging apps (WhatsApp etc.) | App presence; accessible shared media | Media files if in granted folders; **not** chat DBs | Guided via app's own restore | Manual checklist | F3 | 4 |
| 5.10 | Voice recordings / notes | Recorder folders if granted; notes apps via export | Files yes; private notes via export | Files yes | SHA-256 | F2/F3 | 1–3, 4 |
| 5.11 | App data (private) | No | Only the app's own data via Android Auto Backup | Automatic by OS for opted-in apps | — | F3/F4 | 4 |
| 5.12 | Authenticators, passkeys, password managers, banking | No | No (and must not try) | Provider account recovery | Manual | F3 | 4 |
| 5.13 | Google Photos / Drive library | Picker-selected items only; Library API sees app-created items only | Optional import via picker | Sign-in guidance | Presence check | F2/F3 | 4 |
| 5.14 | Installed-app list | PackageManager (needs QUERY_ALL_PACKAGES declaration) or user-reported | Metadata only | Reinstall checklist | Manual | F2 | 4 |
| 5.15 | Device settings / Wi-Fi / wallpaper | Not accessible to normal apps | No | OS restore | — | F4 | — |

Rules:
- A category's adapter must declare: supported categories, what it can discover, permissions, content vs metadata access, can-back-up, can-restore, can-verify, supported Android versions, known limitations.
- If a private-storage area cannot be inspected, the UI says so **while the old phone still works** (principle: explain limits before the disaster).
- Hidden ≠ backed up. A hidden file is only protected if it was actually copied and verified.

## 6. Architecture

### 6.1 Principle
Local-first. The phone does discovery, hashing, encryption, upload, matching, planning and restore. There is **no application backend in v1**; the storage provider (Google Drive) holds opaque encrypted blobs and the encrypted manifest. A backend is an optional later phase (§16, Phase 8).

### 6.2 Component diagram

```
                        ANDROID APP
 ┌─────────────────────────────────────────────────────────┐
 │ UI (Compose)  ◄──── ViewModels / Flows                  │
 │      │                                                  │
 │      ▼                                                  │
 │ Orchestrator (WorkManager + FG service) ─ Task engine   │
 │      │                                                  │
 │  ┌───┴──────────┬───────────────┬──────────────┐        │
 │  ▼              ▼               ▼              ▼        │
 │ Inventory     Backup         Restore        Match/Plan  │
 │ (adapters,    (chunk,        (writers,      (compare,   │
 │  hashing)      encrypt,       verify)        tasks,     │
 │      │         upload,                       guides)    │
 │      │         verify)                                  │
 │      ▼              │               │              │    │
 │   PDI (Room/SQLite) ◄───────────────┴──────────────┘    │
 │      │                                                  │
 │   Crypto (Tink, key hierarchy)                          │
 │      │                                                  │
 │   ContentStore interface ─► DriveStore | LocalStore |...│
 └──────────────────────────────┬──────────────────────────┘
                                ▼
                  Google Drive (opaque encrypted blobs
                  + encrypted manifest + wrapped keyring)
```

### 6.3 Data discovery adapters
Interface sketch (Kotlin):

```kotlin
interface DiscoveryAdapter {
    val id: String
    val categories: Set<Category>
    val capabilities: AdapterCapabilities  // canReadContent, canBackup, canRestore, canVerify, minSdk, maxSdk
    suspend fun requiredAccess(): List<AccessRequirement>   // permission/picker/grant status
    fun discover(scope: DiscoveryScope): Flow<DiscoveredItem>
    suspend fun openContent(item: DiscoveredItem): Source?   // null if metadata-only
    suspend fun limitations(): List<Limitation>             // shown to the user
}
```

Initial adapters: `MediaStoreAdapter`, `SafTreeAdapter` (persisted tree URIs), `AllFilesAdapter` (only if approved), `ContactsAdapter`, `CalendarAdapter`, `InstalledAppsAdapter`, `AccountsAdapter` (account presence/sync flags), `GuidedOnlyAdapter` (WhatsApp, Secure Folder, authenticators: no discovery, produces guidance items). Manufacturer-specific logic stays in separate optional adapters, never in the common PDI model.

### 6.4 Modules
See `CLAUDE.md` repo layout. Dependency rule: `core/model` depends on nothing; `core/*` depend on `model` only (and `crypto`, `pdi` where needed); `store/*` implement `store/api`; `feature/*` depend on core via interfaces; `app` wires everything.

### 6.5 ContentStore interface [Proposed]

```kotlin
interface ContentStore {
    val id: String
    suspend fun capabilities(): StoreCapabilities   // resumable, maxObject, quota info, listing
    suspend fun beginUpload(name: ObjectName, size: Long, meta: OpaqueMeta): UploadSession
    suspend fun resume(session: UploadSession): UploadSession
    suspend fun putChunk(session: UploadSession, offset: Long, bytes: ByteArray): UploadSession
    suspend fun finish(session: UploadSession): StoredObject          // returns remoteId + provider checksum if any
    suspend fun stat(ref: RemoteId): StoredObjectInfo?                // size, provider checksum, exists
    suspend fun open(ref: RemoteId, range: LongRange? = null): InputStream
    suspend fun delete(ref: RemoteId)
    suspend fun list(prefix: String, page: Cursor?): Page<StoredObjectInfo>
    suspend fun quota(): QuotaInfo?
}
```
All implementations must pass a shared **contract test suite** in `store/api` (fake in-memory store included for tests).

### 6.6 Where things run
| Concern | Location |
| --- | --- |
| Discovery, hashing, change detection | Device |
| Encryption/decryption, key handling | Device only |
| Upload/download, resume, verification | Device ↔ store |
| PDI storage | Device (Room); encrypted manifest copy in store |
| Matching, plan generation, restore | Device |
| Auth for storage | Google Identity authorization (OAuth), no passwords handled |
| Billing, quotas, support tooling | Optional backend, Phase 8 |

## 7. PDI data model

### 7.1 Design goals
Multiple devices per user; many snapshots per device; incremental changes without copying the inventory each time; large collections (100k+ assets); duplicates; renamed/moved files; multiple recovery sources per asset; failures/retries; partial restores; historical recovery reports; deletion and retention; assets that are **not** local files (contacts, accounts, app guides).

### 7.2 SQLite schema (Room entities derive from this) [Proposed]

```sql
-- Devices known to this user's PDI
CREATE TABLE device (
  id TEXT PRIMARY KEY,                 -- UUID
  display_name TEXT NOT NULL,
  manufacturer TEXT, model TEXT, android_sdk INTEGER,
  registered_at INTEGER NOT NULL, last_seen_at INTEGER,
  retired_at INTEGER                   -- set when user marks replaced
);

-- Immutable once finalized. Membership is derived from asset_version ranges.
CREATE TABLE snapshot (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES device(id),
  seq INTEGER NOT NULL,                -- monotonically increasing per device
  started_at INTEGER NOT NULL, finalized_at INTEGER,
  status TEXT NOT NULL,                -- BUILDING | FINAL | ABANDONED
  coverage_json TEXT NOT NULL,         -- per-category: inspected? access state? limitations
  UNIQUE (device_id, seq)
);

-- Stable identity of a tracked thing
CREATE TABLE asset (
  id TEXT PRIMARY KEY,                 -- stable UUID, independent of path
  device_id TEXT NOT NULL REFERENCES device(id),
  category TEXT NOT NULL,              -- PHOTO, VIDEO, DOCUMENT, AUDIO, CONTACT, CALENDAR, ACCOUNT, APP_GUIDE, ...
  kind TEXT NOT NULL,                  -- FILE | RECORD | SERVICE_REF | GUIDE
  provider_id TEXT,                    -- e.g. MediaStore id or cloud provider id when meaningful
  created_seq INTEGER NOT NULL
);

-- One row per distinct state of an asset; valid for snapshots [first_seq, last_seq]
CREATE TABLE asset_version (
  id TEXT PRIMARY KEY,
  asset_id TEXT NOT NULL REFERENCES asset(id),
  first_seq INTEGER NOT NULL,
  last_seq INTEGER,                    -- NULL = still current
  content_id TEXT,                     -- keyed hash of plaintext (HMAC-SHA256), for dedupe; NULL if not content-addressable
  sha256 BLOB,                         -- plaintext SHA-256 (local only; never sent unencrypted)
  size_bytes INTEGER, mtime INTEGER, ctime INTEGER,
  source_uri TEXT, rel_path TEXT, display_name TEXT, mime TEXT,
  metadata_json TEXT,                  -- dimensions, duration, EXIF essentials, etc.
  protection_state TEXT NOT NULL,      -- see §4
  state_reason TEXT                    -- machine code + human text key
);
CREATE INDEX idx_av_asset ON asset_version(asset_id, first_seq);
CREATE INDEX idx_av_content ON asset_version(content_id);

CREATE TABLE store (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL,       -- DRIVE | LOCAL | S3 | ...
  account_ref TEXT, config_json TEXT, created_at INTEGER NOT NULL
);

CREATE TABLE backup_object (
  id TEXT PRIMARY KEY,
  asset_version_id TEXT NOT NULL REFERENCES asset_version(id),
  store_id TEXT NOT NULL REFERENCES store(id),
  remote_id TEXT, remote_name TEXT,
  enc_scheme TEXT NOT NULL,            -- e.g. "tink-aes256-gcm-hkdf-4kb/v1"
  plain_size INTEGER, cipher_size INTEGER, cipher_md5 TEXT,
  state TEXT NOT NULL,                 -- PENDING | UPLOADING | UPLOADED | VERIFIED | FAILED | DELETED
  attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT,
  uploaded_at INTEGER, verified_at INTEGER, verify_method TEXT
);

CREATE TABLE recovery_source (
  id TEXT PRIMARY KEY,
  asset_id TEXT NOT NULL REFERENCES asset(id),
  kind TEXT NOT NULL,                  -- INDEPENDENT_COPY | CLOUD_SERVICE_REF | APP_GUIDED | NONE
  backup_object_id TEXT REFERENCES backup_object(id),
  integration_id TEXT REFERENCES integration(id),
  ref_json TEXT, verified_at INTEGER
);

CREATE TABLE integration (
  id TEXT PRIMARY KEY, provider TEXT NOT NULL, account_ref TEXT,
  scopes TEXT, status TEXT NOT NULL,   -- CONNECTED | EXPIRED | REVOKED | UNREACHABLE
  connected_at INTEGER, last_checked_at INTEGER
);

CREATE TABLE recovery_plan (
  id TEXT PRIMARY KEY, new_device_id TEXT NOT NULL, source_snapshot_id TEXT NOT NULL,
  created_at INTEGER NOT NULL, status TEXT NOT NULL
);

CREATE TABLE task (
  id TEXT PRIMARY KEY, plan_id TEXT NOT NULL REFERENCES recovery_plan(id),
  category TEXT NOT NULL, type TEXT NOT NULL,   -- RESTORE_FILES | CHECK_SIGNIN | OPEN_GUIDE | VERIFY | ...
  state TEXT NOT NULL, depends_on_json TEXT,
  reason_key TEXT, payload_json TEXT, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT,
  created_at INTEGER, updated_at INTEGER
);

CREATE TABLE match_result (
  id TEXT PRIMARY KEY, plan_id TEXT NOT NULL,
  old_asset_version_id TEXT NOT NULL, new_local_ref TEXT,
  method TEXT NOT NULL,                -- EXACT_HASH | METADATA | PERCEPTUAL | SERVICE_REF
  confidence REAL NOT NULL, recovery_state TEXT NOT NULL
);

CREATE TABLE verification_record (
  id TEXT PRIMARY KEY, subject_type TEXT NOT NULL, subject_id TEXT NOT NULL,
  method TEXT NOT NULL, result TEXT NOT NULL,   -- PASS | FAIL | INCONCLUSIVE
  at INTEGER NOT NULL, detail_json TEXT
);

CREATE TABLE audit_event (               -- append-only
  id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL,
  type TEXT NOT NULL, subject TEXT, detail_json TEXT
);
```

### 7.3 Versioning strategy
- Snapshot membership is derived: asset version `v` is in snapshot `s` iff `first_seq <= s.seq AND (last_seq IS NULL OR s.seq <= last_seq)`. A new snapshot after no change creates **no** new asset rows.
- A changed file closes the old version (`last_seq = seq-1`) and opens a new one. A deleted file closes its version. A moved file keeps the same `asset.id` when matched (see §8.3).
- Finalized snapshots are immutable; corrections are new events.

### 7.4 Schema migrations
- Room migrations with exported schemas checked into the repo; migration tests required for every version bump.
- The exported **manifest** carries its own `schema_version`; the restore engine must read all prior manifest versions.

### 7.5 Manifest export format [Proposed]
The PDI for a device is exported as an encrypted, versioned file set in the store:

```
/<root>/keyring.json             wrapped master key(s), KDF params, version (no secrets in plaintext)
/<root>/manifests/<device>/<seq>.pdi.enc     encrypted full or delta manifest
/<root>/manifests/<device>/head.enc          pointer to latest finalized snapshot
/<root>/objects/<aa>/<content_id>.enc        encrypted content objects (content-addressed by keyed hash)
```
Delta manifests reference a base; compaction produces a new full manifest periodically. The decrypted manifest is JSON (examples in §19).

## 8. Algorithms

### 8.1 Discovery
1. For each enabled adapter, check access state. If not granted → record category as `INACCESSIBLE` with reason; **do not skip silently**.
2. Enumerate items: MediaStore via cursor queries (batch by `_ID`), SAF via `DocumentsContract` child-document queries (not `DocumentFile`, which is slow) [Spike: performance on 50k files].
3. Emit `DiscoveredItem(sourceUri, relPath, size, mtime, mime, providerId)`.
4. Compute a **cheap fingerprint** `(size, mtime, providerId/path)`; compare with the current `asset_version`. Unchanged → keep; changed or new → queue for hashing.

### 8.2 Hashing
- Stream SHA-256 over content; 1 MiB buffer; cancellable; resumable by recording progress only at file granularity.
- Run in a throttled `Dispatchers.IO` pool; respect battery constraints (§12).
- `content_id = HMAC-SHA256(K_name, sha256)` is what is used as the remote object name so the store learns nothing about plaintext hashes.

### 8.3 Change detection and identity
- Match a newly seen item to an existing asset by, in order: same `providerId`; same normalized path; same `sha256` with the old path now missing (move/rename). Ties → prefer most recent; record the decision in `audit_event`.
- Deleted-after-backup: mark version closed but **keep** the backup object per retention policy (§13.3).

### 8.4 Upload
For each `asset_version` in `NOT_BACKED_UP`/`BACKUP_FAILED` (eligible):
1. Dedupe: if a `backup_object` with the same `content_id` is `VERIFIED` → link and finish, no upload.
2. Create `backup_object(PENDING)`.
3. Encrypt as a stream (Tink streaming AEAD; associated data binds `content_id` + scheme version). Compute ciphertext MD5 while streaming (for provider comparison where available).
4. Open resumable upload session (store capability); persist session URI/offset after each acknowledged chunk.
5. On success → `UPLOADED_UNVERIFIED`. Then verify (§8.6).
6. Small-file optimisation (Phase 2b) [Spike]: pack many small files into bundles (e.g. ≤ 32 MiB) to avoid per-file API overhead and rate limits.

### 8.5 Resume and failure behaviour
| Failure | Behaviour |
| --- | --- |
| Network drop | Back off (exponential + jitter); resume from last acknowledged offset. |
| Process killed / phone powers off | On restart, reload sessions from DB; verify remote offset before continuing. |
| Session expired | Start a new session; discard partial remote object. |
| Quota full | Pause backup, show clear reason, never delete older verified backups automatically. |
| Auth expired/revoked | Mark integration `EXPIRED`; ask the user to re-authorise; no data loss. |
| Source file changed mid-upload | Abort, re-hash, new version. |
| Source file deleted mid-upload | Record `BACKUP_FAILED(reason=SOURCE_GONE)`. |
| Corrupted stored object | Verification fails → re-upload; keep previous verified object until the new one verifies. |

### 8.6 Verification of backups
Levels (record which was used in `verify_method`):
1. **Stat check:** remote exists, size == expected ciphertext size, provider checksum (if offered) == locally computed. [Spike: confirm Drive checksum availability for the upload path used.]
2. **Sampled decrypt check:** periodically download and decrypt a random sample; compare plaintext SHA-256 to the PDI.
3. **Full re-verification:** scheduled (e.g. monthly), bandwidth-aware, resumable.
Only levels ≥ 1 may set `BACKUP_VERIFIED`; the UI shows *how* each category was verified.

### 8.7 Snapshot build and export
1. Open `snapshot(BUILDING)`; run discovery per category; write coverage (what was inspected, what was not).
2. Apply changes to `asset_version`; mark finalized with `seq`.
3. Serialize delta manifest → encrypt → upload → update `head.enc`. A snapshot is **exportable** only after all referenced content for its `BACKUP_VERIFIED` claims is verified.

### 8.8 Recovery on the new device
1. **Authenticate** to the store (Google authorization). Locate the root; read `keyring.json`.
2. **Unlock** with passphrase and/or recovery key (§9). Derive keys; decrypt `head.enc`; list devices and snapshots.
3. User picks previous device/snapshot (default: latest finalized).
4. **Inventory the new device** with the same adapters (what already exists: photos from Google Photos sync, pre-restored files, etc.).
5. **Match** (§8.9) old assets vs new.
6. **Generate plan** (§10): tasks for missing assets with verified sources; guide tasks for external recovery.
7. **Execute**: restore in dependency order; verify each file after write (SHA-256 of restored bytes vs PDI).
8. **Final inventory + report** (§4 rules, §11.3).

### 8.9 Matching (old vs new)
Tiers, each recorded with method and confidence:
1. `EXACT_HASH` (SHA-256 equal) → `RESTORED_VERIFIED`/already present. Confidence 1.0.
2. `METADATA` (size + media type + timestamps/dimensions/duration agree, name may differ) → candidate only; confirm by hashing when possible. Never counted as verified.
3. `PERCEPTUAL` (image pHash/dHash; video keyframe sampling) → `RESTORED_UNVERIFIED`, confidence < 1, for recompressed/transcoded cloud copies. Phase 7.
4. `SERVICE_REF` (item exists via a connected service) → `PRESENT_VIA_SERVICE`.
Output keeps all candidate matches; the user can inspect "probable" ones.

### 8.10 Restore writers
- **Photos/videos** → `MediaStore.Images/Video` insert with `RELATIVE_PATH` (e.g. `DCIM/Restored/`), set display name and date fields; use `IS_PENDING` while writing. Original capture time must survive (EXIF is inside the bytes; also set `DATE_TAKEN`/`DATE_MODIFIED` where permitted) [Spike].
- **Documents** → user-chosen SAF tree (default suggestion `Documents/Restored`); never overwrite an existing file: on name collision with different content, add a suffix and record it.
- **Contacts/calendar** (Phase 5) → merge with duplicate detection; never blindly insert.
- After each write: re-read, hash, compare → `RESTORED_VERIFIED` or `RESTORED_UNVERIFIED`/`FAILED`.

### 8.11 Missing-content detection and gap reporting
For every old `asset_version`: compute `(protection_state, recovery_state)`. Build the report from the cross product, always including: restored+verified, restored-unverified, via service, missing with verified source (offer restore), missing without source (explain), inaccessible categories (list the reason), and manual-action items.

---

## 9. Security architecture

### 9.1 Principles
Client-side encryption by default; the storage provider never sees plaintext, filenames, or plaintext hashes. No custom primitives: **Google Tink** for AEAD/streaming AEAD/HKDF; a vetted **Argon2id** implementation for passphrase stretching (library choice = ADR, see DECISIONS). Every cryptographic choice gets a written rationale. **Get an external security review before public release.**

### 9.2 Key hierarchy [Proposed]

```
Master Key (MK, 256-bit random, generated on device at setup)
  ├─ HKDF → K_content   (encrypt file content)
  ├─ HKDF → K_manifest  (encrypt manifests)
  └─ HKDF → K_name      (HMAC for content_id / object names)

MK is stored only in WRAPPED form:
  • Wrap A: KEK_pass  = Argon2id(passphrase, salt, params)      → in keyring.json (in the store)
  • Wrap B: KEK_rec   = recovery key (256-bit, shown once)       → in keyring.json (in the store)
  • Wrap C: Android Keystore key (device convenience only)       → local, NOT relied on for recovery
```
- Recovery works from **either** the passphrase **or** the recovery key, on any device, with only access to the store.
- Keystore-wrapped copy is a convenience so the user is not asked for the passphrase on every operation. It dies with the device and that is acceptable. [Spike: confirm behaviour on device loss/restore.]
- Changing the passphrase re-wraps MK; content is not re-encrypted. Rotating MK itself is a heavy operation (re-encrypt everything) and is an explicit, rare, user-initiated flow.

### 9.3 Setup flow requirements
1. Explain in plain words: "If you lose both your passphrase and your recovery key, nobody — including us — can recover your backup."
2. Generate MK; show recovery key (printable/copyable, grouped characters or word list); require the user to **confirm** they saved it (re-enter a few characters). Offer "save to a password manager", "print", "write down".
3. Optional: share recovery key with a trusted contact (Shamir secret sharing) — Phase 7 [Proposed]; do not hand-roll SSS, use a vetted library.
4. Make the "lost both" outcome visible in settings (a "Recovery readiness" check).

### 9.4 Encryption format
- Per object: Tink streaming AEAD (e.g. AES-256-GCM-HKDF segments); associated data = scheme version ‖ content_id. Prevents swapping ciphertexts between objects.
- Manifest: AEAD with `K_manifest`; includes `seq`, `device_id`, previous-manifest hash (hash chain) to detect truncation/rollback within what a store-only design can detect. Full rollback detection against a malicious store is limited without independent state; document this.
- Version every scheme (`enc_scheme`) so old objects remain decryptable forever.

### 9.5 Threat model (summary; expand in `docs/THREATS.md` during Phase 0)

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| Stolen/unlocked phone | App lock (biometric/PIN) for viewing/restore actions; Keystore-wrapped MK requires user auth for sensitive ops | Attacker with unlocked phone and app unlocked can read local data |
| Compromised Google account | Content is end-to-end encrypted; attacker sees opaque blobs | Can delete/corrupt blobs → keep periodic verification + optional secondary target |
| Compromised/malicious store | AEAD + hash-chained manifests detect tampering | Cannot prevent deletion or full rollback by a malicious store |
| Lost keys | Two independent wraps (passphrase, recovery key); setup confirmation | If both lost, data is permanently unrecoverable (disclosed) |
| Accidental deletion | Retention policy; deletions require confirmation; keep verified backups until replacement verified | User can still delete deliberately |
| Malicious app on device | Android sandbox; no exported components holding secrets; FLAG_SECURE on key screens | Rooted/compromised OS is out of scope |
| Phishing of recovery flow | Never ask for Google password; use system authorization UI; never send recovery key anywhere | User social-engineering |
| Support/operator access (future backend) | Operator never holds keys; no plaintext in logs | N/A in v1 (no backend) |

### 9.6 Authentication and authorization
Use Google's supported authorization flow for Drive access; request the **least** scope (`drive.file` or app-data, final choice by ADR after spike). Never collect or store Google passwords. Token storage via platform-supported mechanisms. Handle revocation/expiry (§8.5).

## 10. Recovery plan and instruction engine

### 10.1 Purpose
Generate a **personalised** plan from the PDI and the current device. Not a generic guide.

### 10.2 Task model
Fields: task id; category; reason it is needed; required permissions/accounts; prerequisites (dependency task ids); automation available (full/partial/none); user actions (plain-language steps); expected result; verification method; failure handling; known limitations.

### 10.3 Task state machine

```
PENDING ─► READY ─► IN_PROGRESS ─► COMPLETED ─► VERIFIED
              ▲          │  │            └──► VERIFICATION_FAILED ─► READY
              │          │  └─► WAITING_FOR_USER ─┐
              └──────────┼──────────────────────────┘
                         └─► FAILED ─► RETRY ─► READY
SKIPPED (user declined)   BLOCKED (dependency failed)
```
Rules: tasks only enter `READY` when all dependencies are `VERIFIED`/`SKIPPED`; the engine is resumable after process death; every transition is an `audit_event`.

### 10.4 Example rules
- Photos with `BACKUP_VERIFIED` copy and `NOT_FOUND` on new device → `RESTORE_FILES` task (automated).
- Gmail account present → `CHECK_SIGNIN` guide; verify by account presence on device.
- Photos present via a Google Photos sync on the new device → mark `PRESENT_VIA_SERVICE`, offer verification by picker.
- WhatsApp installed on old device (from inventory) → `OPEN_GUIDE(whatsapp_restore)`: same phone number, same Google account, check backup exists, encrypted-backup key warning. Mark verified only by the user's confirmation + presence check; never claim chat history was restored by us.
- Secure Folder content known to exist but inaccessible → guide: explains Samsung's current limits (see PLATFORM_CONSTRAINTS) and what can still be done.
- Document inventoried but never backed up → report "cannot be recovered from this app" with no restore button.

### 10.5 Guide content
Guides are data (versioned JSON/Markdown resources, localizable), keyed by provider + OS/OEM version, each with a "last reviewed" date and source links. They must not present manual steps as automated actions.

## 11. UX specification

### 11.1 Principles
No terminal; no manual file hunting; clear status; honest reporting; limits explained before disaster; automatic where possible, guided otherwise; verification after recovery; accessible design; no false guarantees; complexity lives in the implementation.

### 11.2 Screens (all phases)
1. **Onboarding:** what the app does and does not do (prevention), storage choice, permissions explained one at a time with a "why", recovery key creation and confirmation.
2. **Home / Protection status:** per-category cards with state counts, last successful backup time, last verification time, and a prominent "Gaps" entry. No generic "Backup complete" if any important category is unprotected.
3. **Category detail:** counts by state, what was inspected vs not, reasons, actions (grant folder, retry failed).
4. **Gaps & guidance checklist:** WhatsApp, Secure Folder/vaults, authenticators/passkeys, password manager, banking, other apps; each with "why it can't be protected" and "what to do now".
5. **Backup settings:** destination, Wi-Fi only, charging only, quota/usage, retention, pause/resume, per-folder include/exclude.
6. **Recovery readiness:** "Can you recover today?" checklist: recovery key saved, store reachable, last verified backup, Google account access, 2FA recovery methods.
7. **Recovery flow (new device):** Sign in → unlock (passphrase/recovery key) → choose device/snapshot → scan this phone → report → ordered plan with buttons → progress → final report.
8. **Final recovery report:** restored+verified; restored-unverified; via service; missing with source; missing without source; inaccessible; manual actions. Exportable (PDF/Markdown) [Phase 7].
9. **Activity log & diagnostics:** human-readable history (backups, failures, verifications), redacted diagnostic export.
10. **Settings:** storage providers, integrations (connect/revoke), data deletion, retention, language, accessibility, app lock.

### 11.3 Report example (illustrative, derive real numbers from state)
```
Recovery status for "Samsung Galaxy A52" — last verified backup: 20 Oct 2026
Photos      8,420 inventoried | 8,420 verified on this phone | 0 missing
Videos        315 inventoried |   290 verified              | 25 missing (restore available)
Documents     184 inventoried |   172 verified              | 12 missing (restore available)
Not inspected: WhatsApp chats, Secure Folder, authenticator apps, app-private data
```

### 11.4 Error copy rules
Translate technical errors into actions. Example: not "Storage permission denied" but "We couldn't open this folder. Allow access to include its files in your backup." Every error has: what happened, whether data is safe, what to do next, a retry control. Keep an error-code → copy table in the repo; strings externalised for localisation.

### 11.5 Accessibility and localisation
TalkBack labels, minimum touch targets, scalable text, high contrast, no colour-only status. All user-visible strings in resources from day one; plan for right-to-left and additional languages.

## 12. Background execution and resource policy

- **Scheduling:** WorkManager for periodic inventory/incremental scans (platform minimum period applies) with constraints (unmetered network, charging, battery-not-low by default; user can relax).
- **Large transfers:** foreground service with a visible notification, started from a user-visible context where possible. On Android 15+, `dataSync` foreground services are limited to 6 hours per 24 and cannot be restarted after the limit until the user opens the app; implement `onTimeout`, persist progress, resume later. [Verified; see PLATFORM_CONSTRAINTS]
- Evaluate **user-initiated data transfer jobs** (newer Android) for user-triggered big uploads. [Spike]
- Doze/App Standby: expect delays; never assume exact timing. Do not request battery-optimisation exemption unless justified by an ADR and Play policy.
- Throttle CPU-heavy hashing; avoid heat/battery drain; pause on low battery or thermal pressure.
- First backup of hundreds of GB will take days. UI shows ETA ranges, per-category progress, and "what to prioritise first" (recent photos, documents).
- Never block the UI thread; all I/O cancellable; all long operations resumable.

## 13. Storage providers, cost, retention

### 13.1 Providers
- **v1:** Google Drive (user's own quota) via `drive.file` or app-data scope (ADR after spike). Pros: no backend, no hosting cost, familiar. Cons: user quota, Google account dependency, tied to Drive policies.
- **Later:** local folder/SD/USB via SAF; S3-compatible/WebDAV (bring your own); PC export tool; optional first-party cloud storage (Phase 8).
- Failure scenarios per target are shown to the user (account loss, service outage, accidental deletion, device loss) so they can choose targets that cover their risks. Recommend ≥ 2 independent targets for important data.

### 13.2 Cost model (to do in Phase 0; template)
Inputs: average library size (GB), growth/month, dedupe ratio, number of users, provider price/GB-month, egress on restore, API request costs. Output: cost per user per month at 10/100/1000 users for each provider option. Only needed in v1 if first-party storage is considered; BYO storage = zero hosting cost.

### 13.3 Retention and deletion
- Defaults: keep deleted-after-backup files for N days (user-configurable); keep last verified version of changed files plus a limited number of prior versions.
- User-controlled deletion of all backup data; revoke integrations; "delete everything" flow that removes remote objects, manifests, and local DB.
- Never delete a previous verified object until its replacement is verified.

## 14. Privacy, policy, compliance

- **Google Play:** target API 36+; permission declarations for All-files access (if used), photo/video, contacts (mandatory from 28 Oct 2026 for API 37+ apps), SMS/call-log (default-handler only), `QUERY_ALL_PACKAGES` if used. Data Safety form; privacy policy; in-app data deletion path. Request permissions in context; honour denial; remain functional with picker-based fallbacks.
- **Developer verification:** register the developer account/apps as Google's verification programme requires (rolling out region by region; global in 2027).
- **Data minimisation:** inventory metadata stays on device; only encrypted blobs/manifests leave. Avoid collecting sensitive content from filenames in any telemetry.
- **Applicable law:** check the laws of the markets you ship to (for example India's DPDP Act, GDPR if distributed in the EU). [Not researched here — get proper legal advice before launch.]
- Financial or legal statements shown to users must be reviewed; the app gives no legal/financial advice.

## 15. Observability

- Structured local logs (no file contents/names in remote logs). User-triggered, redacted diagnostic bundle.
- Optional opt-in crash reporting with scrubbed payloads (decision via ADR).
- In-app activity log is the primary "what happened" record; `audit_event` is the source of truth.
- Metrics (local, for debugging): upload throughput, retry counts, verification pass rates, matching tier distribution.

## 16. Phased roadmap (whole product)

Each phase lists deliverables, dependencies, spikes, tests, acceptance criteria, risks, and what is deferred. Do not start a phase before the previous phase's acceptance criteria pass.

### Phase 0 — Foundations and proof-of-concept spikes
**Deliverables:** repo skeleton, CI, module layout, ADR log, threat model draft (`docs/THREATS.md`), cost model template, spike reports in `PLATFORM_CONSTRAINTS.md`.
**Spikes (each with pass/fail criteria and a written result):**
1. MediaStore enumeration + SHA-256 on ≥ 10k files: throughput, memory, battery.
2. SAF tree traversal via `DocumentsContract` on ≥ 50k files; persisted URI permission survival after reboot.
3. Tink streaming AEAD + Argon2id on a low-end device (time, memory); choose Argon2 library.
4. Google Drive authorization + resumable upload from Android with the chosen scope; interrupt/resume; confirm provider checksum availability; confirm what the user sees in Drive; behaviour when the user removes the app's access.
5. Permission strategy: all-files access vs picker/SAF-only for a backup app (prepare the Play declaration; note approval is not guaranteed).
6. Restore to MediaStore preserving dates and paths; behaviour on different Android versions.
7. Keystore behaviour across device loss/restore and Android Auto Backup (verify the "keys don't survive the phone" assumption).
8. Foreground service `dataSync` timeout handling and resume.
9. Evaluate user-initiated data transfer jobs.
**Acceptance:** every spike has a recorded result and the dependent ADRs are Accepted or revised.
**Deferred:** all product features.

### Phase 1 — Inventory
**Deliverables:** device registration; `MediaStoreAdapter` and `SafTreeAdapter`; metadata collection; hashing; PDI schema + migrations; snapshot versioning; coverage records (what could/could not be inspected); basic status UI with honest limitations.
**Tests:** change detection (add/modify/rename/move/delete), large-set performance, permission denial paths, snapshot immutability.
**Acceptance:** user can generate a reliable inventory and see accessible vs inaccessible content; snapshots are immutable and diff-able.

### Phase 2 — Independent encrypted backup
**Deliverables:** key hierarchy + setup/recovery-key flow; `ContentStore` + `DriveStore` + contract tests; encrypted resumable incremental upload; dedupe; verification levels 1–2; retry/backoff; backup status UI; encrypted manifest export; storage quota awareness; background orchestration with WorkManager + FG service handling the 6h limit.
**Tests:** fault injection (network drop, kill, quota full, auth expiry, source changed), tamper tests (bit-flip → verification fails), key-recovery tests using only passphrase or only recovery key.
**Acceptance:** supported files back up, verify, and are shown as such; failures are visible and retryable; a never-backed-up file is never reported as protected.

### Phase 3 — Replacement-device recovery
**Deliverables:** new-device sign-in/unlock; manifest retrieval; new-device inventory; matching tiers 1–2; plan generation for restorable assets; restore writers (MediaStore, SAF); post-restore verification; progress and final report.
**Tests:** the **Disaster Drill** (§17) end to end; interrupted restore; name collisions; different Android versions/manufacturers.
**Acceptance:** on a second device, with the old device unavailable, supported files are restored and the report correctly lists recovered, missing, and inaccessible items.

### Phase 4 — Gaps, integrations, and guided recovery
**Deliverables:** guide engine and guide content (WhatsApp, Secure Folder, authenticators/passkeys/password managers, banking apps, Google account restore, Gmail sign-in); "Recovery readiness" screen; integration status checks (Drive reachability, account presence); Google Photos Picker-based import/verification; installed-app inventory and reinstall checklist; messaging/media-folder inclusion guidance.
**Tests:** guide rendering/versioning, rule engine unit tests, tests ensuring no guide claims automation that does not exist.
**Acceptance:** every unprotectable category is clearly shown with accurate, current guidance before disaster and in the recovery plan after.

### Phase 5 — Contacts, calendars, and (decision) SMS/call log
**Deliverables:** contacts/calendar export to encrypted vCard/ICS, restore with duplicate detection, verification; Play permission declarations. **Decision gate:** SMS/call-log backup requires becoming the default SMS/Phone handler (heavy product commitment) — decide via ADR; the default plan is **not** to build this and instead guide users to a compliant approach.
**Acceptance:** no duplicate contacts after restore; counts and field hashes verified.

### Phase 6 — Additional storage targets and secondary backups
**Deliverables:** second `ContentStore` (SAF folder/SD/USB), optional S3-compatible/WebDAV, multi-target policy, restore-from-folder without cloud account, PC export/restore helper (cross-platform CLI) that can decrypt a backup with the recovery key.
**Acceptance:** user can recover from either target independently; per-target failure scenarios displayed.

### Phase 7 — Advanced matching, versions, polish
**Deliverables:** perceptual/similarity matching (tier 3) with clear "probable" labelling; version history and retention UI; multi-device/multiple old-devices view; recovery report export; trusted-contact recovery key sharing (vetted Shamir library); accessibility and localisation pass; performance/battery tuning; security review fixes; Play release hardening.
**Acceptance:** external security review findings addressed; accessibility audit passed; battery/thermal targets met.

### Phase 8 — Optional backend and first-party storage
**Deliverables:** accounts, device registration, storage quotas, billing (Play Billing), account recovery that cannot leak keys, audit logging, abuse controls, support tooling with no content access. Zero-knowledge design preserved (server stores opaque blobs only).
**Decision gate:** only if cost analysis (§13.2) and product strategy justify it.

### Phase 9 — OEM and app-specific integrations
**Deliverables:** per-OEM migration guidance/integrations where **supported APIs** exist; per-app export/import helpers where the app provides official mechanisms. Add only when feasibility and maintenance cost are understood. No undocumented behaviour.

## 17. Testing strategy

### 17.1 Layers
- **Unit:** state machines (protection, recovery, task), matching, retention, plan generation, crypto round-trips with fixed vectors.
- **Property-based:** snapshot invariants (immutability, derived membership), state transitions never skip verification.
- **Instrumented:** MediaStore/SAF behaviour, restore writers, Room migrations, foreground service lifecycle.
- **Contract tests:** every `ContentStore` implementation.
- **Fault injection harness:** kill process, drop network, full disk, expired token, revoked permission, clock skew, file mutation mid-read.
- **Security tests:** tamper (flip bits in object/manifest), wrong passphrase, missing recovery key, rollback attempts, key-wrap round trips.
- **UX comprehension tests:** non-technical testers complete recovery from the report alone; record misunderstandings of status wording.

### 17.2 The Disaster Drill (central end-to-end scenario)
1. Install on **old device**; seed with a fixture library (photos, videos, documents, duplicates, renamed/moved files, hidden-but-accessible folder, an unreadable folder).
2. Create recovery key; build PDI; back up; verify.
3. Make further changes (add/modify/delete/move); update PDI; back up again.
4. Simulate total loss of the old device (wipe/power off; **do not use it again**).
5. Install on **new device** (different Android version/manufacturer when possible).
6. Sign in; unlock with recovery key only; retrieve latest PDI.
7. Scan new device; match; generate plan; restore; verify.
8. Assert: recovered/missing/inaccessible lists exactly match expected fixtures; the file that was inventoried but never backed up is **not** offered for restore and is reported as unrecoverable; no state was misreported.
Automate with scripted emulators where feasible; run manually on real devices before each release.

### 17.3 Test matrix
Android versions (min supported, current-1, current), manufacturers (Pixel, Samsung, one other), file types, large libraries, large files, duplicates, hidden dirs, user-selected folders, insufficient permissions, interrupted backup/restore, full cloud storage, expired authorization, partial recovery, moved/renamed files, missing integrations, inaccessible app-private data.

## 18. Open questions (track and resolve in DECISIONS.md)

1. Final minimum Android version (proposed 11/API 30) vs target market device mix.
2. All-files access vs picker/SAF-only: depends on spike 5 and Play review.
3. Drive scope: `drive.file` vs app-data folder (visibility, deletion behaviour when access is revoked).
4. Argon2id library and parameters for low-end devices.
5. Whether to become the default SMS app (default: no).
6. Whether/when to build a backend and first-party storage.
7. Product name, package id, branding.
8. Telemetry stance (default: none; opt-in crash reports only).
9. Legal review for target markets.
10. Whether recovery works without a Google account (e.g. SAF folder + recovery key only) — proposed yes via Phase 6.

## 19. Appendix — representative JSON

### 19.1 Decrypted manifest (delta) excerpt
```json
{
  "schema_version": 1,
  "device_id": "d-7f3c...",
  "snapshot": { "seq": 14, "finalized_at": "2026-10-20T08:12:00Z", "base_seq": 13 },
  "coverage": {
    "PHOTO":    { "inspected": true,  "access": "media_permission", "limitations": [] },
    "DOCUMENT": { "inspected": true,  "access": "saf_trees:2",     "limitations": ["Download root not selectable via picker"] },
    "WHATSAPP": { "inspected": false, "access": "none", "reason": "app_private", "guide": "whatsapp_restore" },
    "SECURE_FOLDER": { "inspected": false, "access": "none", "reason": "oem_vault", "guide": "samsung_secure_folder" }
  },
  "changes": {
    "added": [
      {
        "asset_id": "a-91b2...", "category": "PHOTO", "kind": "FILE",
        "version": {
          "content_id": "c-5e11...", "size": 3482211, "mtime": 1760947200,
          "rel_path": "DCIM/Camera/IMG_2031.jpg", "mime": "image/jpeg",
          "metadata": { "width": 4000, "height": 3000 },
          "protection_state": "BACKUP_VERIFIED",
          "backup": { "store": "drive", "object": "objects/5e/c-5e11....enc", "enc_scheme": "tink-streaming-aes256gcm-hkdf-4kb/v1", "verified": { "method": "STAT_CHECKSUM", "at": "2026-10-20T08:10:41Z" } }
        }
      }
    ],
    "modified": [], "closed": []
  },
  "prev_manifest_hash": "sha256:..."
}
```

### 19.2 Recovery task
```json
{
  "task_id": "t-0042", "type": "RESTORE_FILES", "category": "VOICE_RECORDING",
  "reason_key": "missing_with_verified_backup", "depends_on": ["t-0001"],
  "automation": "full",
  "user_actions": [],
  "expected_result": "87 recordings present in Recordings/Restored",
  "verification": "sha256_match_all",
  "failure_handling": "retry_failed_items_only",
  "limitations": []
}
```

### 19.3 Guide item (data-driven)
```json
{
  "guide_id": "whatsapp_restore", "last_reviewed": "2026-10-23",
  "applies_if": { "app_installed_on_old_device": "com.whatsapp" },
  "automation": "none",
  "steps_key": ["wa_install", "wa_same_number", "wa_same_google_account", "wa_restore_prompt", "wa_encrypted_key_warning"],
  "verification": "user_confirmation_and_app_present",
  "sources": ["<official WhatsApp help URL to be added after review>"]
}
```
