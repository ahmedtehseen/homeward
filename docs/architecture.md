# Architecture and integrity boundaries

Personal Backup has two components: an Android client (Kotlin, Compose, Room, OkHttp) and a Mac CLI/server (Go, SQLite, ordinary files). The data path is phone MediaStore → pinned HTTPS on the local network → external-drive staging → verified original. No remote service participates.

## Persistent state

The phone stores source identity/version, transfer hash/offset/state, and retry information in Room. Pairing credentials are encrypted with an Android Keystore key. Initial source hashing persists encoded SHA-256 state and byte offset every 16 MiB, so interrupted large-file hashing can resume after source-version checks. Cleanup always uses a fresh hash. Media is streamed from its content URI; upload chunks are bounded, and full video copies are not staged on the phone.

The selected Mac volume holds the catalog, certificate/private key, device-token hashes, staging files, final originals, content manifests, and source manifests. The SQLite catalog uses full synchronous writes. No server media-deletion API exists.

Content identity is SHA-256. Multiple sources or versions with identical bytes share one object, while source metadata is recorded separately. Edited content gets a different hash; previously stored versions remain. Source IDs are scoped to the paired device. Date folders use UTC capture timestamps in milliseconds, with an `Undated` fallback.

## Transfer invariants

1. The phone identifies the library and pins the exact server certificate before exchanging credentials or media. Discovery changes an address, never the trusted identity.
2. The server validates the destination volume/library before writes. Startup verifies its persisted volume UUID. Missing/remounted storage cannot silently become an internal fallback directory.
3. Upload creation reserves remaining bytes across active uploads plus a 5 GiB free-space floor. Space is rechecked as chunks arrive. An uploading reservation expires after seven inactive days without deleting staged bytes; a resumed upload must reserve its remaining space again.
4. Each chunk has an explicit offset and SHA-256. The server flushes file data, then persists the new offset, before acknowledging it. Repeated old offsets must match durable bytes; partial overlap and changed bytes are rejected.
5. Completing upload bytes changes state to `verifying`. The Mac hashes the whole staged file, checks length, saves a recovery manifest, atomically renames it into originals, flushes directories, then records `committed`.
6. Startup resumes interrupted verification. Rebuild independently validates manifests against their files; a manifest alone is never proof of a backup.
7. Cleanup needs a fresh current-source hash/version/size match and a fresh server hash verification for the same library. Only then can Android show its system Trash confirmation. Canceling the confirmation does not remove the source.

## Scheduling and failure handling

Automatic WorkManager batches and user-initiated transfer jobs use one persistent queue. Scans are incremental with periodic full reconciliation. Folder exclusions and media permissions constrain the visible library; inaccessible content cannot be counted as successfully backed up. Queue operations bound concurrent uploads to two; chunks normally use 4 MiB and fall to 1 MiB after repeated failures.

Offline waiting, user pause, charging/battery/thermal conditions, and missing storage are visible states. Retryable transfer failures use bounded exponential backoff with jitter. A lost response is reconciled by querying the durable server offset. Integrity errors require investigation rather than falsely setting the backup state to complete.

## Limits

- Android controls background scheduling. Force-stop, OEM battery restrictions, and unavailable local-network/media permissions can prevent automatic work.
- The server needs an awake Mac and the mounted selected drive. Network discovery may be blocked; a manual address is supported with the same pin.
- Only locally accessible MediaStore images/videos are included. Cloud-only items, private app storage, and media excluded by permissions are not backed up.
- SHA-256 verification is a point-in-time integrity check. It cannot prevent future disk failure or edits by other software; a single external drive is still one copy.
- Server credentials authorize the local library as a whole; this is a personal deployment, not a multi-user tenancy system. Remote access and internet exposure are outside the design.
- Large-library throughput, UI responsiveness on physical phones, actual thermal behavior, removable-storage behavior, and the 100 GB external-drive run require the hardware acceptance tests.
