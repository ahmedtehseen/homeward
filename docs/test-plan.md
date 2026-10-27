# Validation and acceptance tests

This is a reproducible acceptance checklist, not a claim that every case has run. Build/test evidence from the implementation session belongs in `validation.md`. No physical Android phone or 100 GB external-drive transfer is assumed tested unless explicitly recorded there.

## Automated checks

Run server tests with `go test -race ./...` from `server/`, and Android unit tests with `./gradlew testDebugUnitTest` from `android/`. Build the Mac executable and signed release APK. Confirm the APK signer, package ID, min SDK 34, and target SDK 37 with Android build tools. Keep the signing private key out of deliverables.

The critical server cases are byte-identical restoration, chunk checksum rejection, duplicate chunk acknowledgment, conflicting repeats, wrong offsets, duplicate-content/source records, exhausted storage reservations, wrong/missing library markers, whole-file corruption, interrupted finalization, rebuild, and fresh cleanup verification. Android rules should deny cleanup for changed source bytes/size/version, a different library, or an unverified server response, and keep retry delays bounded.

## Physical-device acceptance

| Scenario | Procedure | Required result |
|---|---|---|
| First pairing | Scan a fresh QR code, then retry its use; also try an expired code | First pairing succeeds; reused/expired secrets fail |
| Pin identity | Substitute a server certificate or connect to another library at the same address | Connection rejected; no upload or cleanup |
| Permissions | Deny all access, grant selected photos only, then grant full images/videos and originals access | Accurate limited-access messaging; only accessible originals eligible |
| Local-only Wi-Fi | Use a Wi-Fi network without internet | Pairing and media transfer work locally |
| Discovery | Change the Mac DHCP address, then block multicast and enter the new address manually | Same library reconnects; pin remains enforced |
| Formats | Transfer JPEG, HEIC, RAW, PNG, GIF and supported video files, including a video over 4 GiB | Server files hash-identical; embedded metadata unchanged |
| Source changes | Edit or remove a source during hashing/upload | Changed source restarts as new version; missing source remains unresolved |
| Duplicates | Same filenames with different bytes; same bytes under different sources | No collision; identical bytes share one object with separate metadata |
| Interruption | Disable Wi-Fi mid-video; terminate/reboot phone; restart/sleep Mac; lose response after chunk write | Resume from durable offset; no duplicated bytes or false completion |
| Drive removal | Unmount/disconnect destination during upload and verification | No internal fallback library; no false backup/cleanup; recovery on reconnect |
| Capacity/permissions | Exhaust unreserved capacity or make the drive read-only | Visible waiting/error state; existing originals preserved |
| Throttling | Slow/stall the network and set a bandwidth cap | Bounded memory, progress remains responsive, retries/chunk fallback work |
| Scheduling | Low battery, charging-only mode, high thermal state, force-stop and reopen | Work respects conditions; force-stop limitation visible |
| Cleanup cancel | Select verified backups, complete verification, cancel Android Trash dialog | Phone files remain |
| Cleanup mismatch | Change source or corrupt/remove server copy before fresh cleanup verification | Affected items denied; no Trash request for them |
| Cleanup offline | Disconnect server or change paired destination before verification | Cleanup denied |
| Cleanup success | Reverify matching originals; approve Android Trash dialog | Phone items trashed, Mac originals intact; storage may remain occupied until Trash is emptied |
| Recovery | Interrupt after intent manifest/rename, run recovery; rebuild with catalog preserved aside | Only hash-valid originals restored; lost credentials require pairing |

## Scale run

Use a disposable test library on the intended external filesystem, retaining all source media elsewhere. Transfer at least **100 GB** including files larger than 4 GiB, and exercise a **100,000-item** catalog. Record device/OS, Mac/OS, drive/filesystem, network conditions, elapsed time, peak client/server memory, visible progress correctness, UI scrolling responsiveness, retry/recovery behavior, and final independent file hashes.

Run an interruption in each phase: hashing, chunk upload, server verification, final publication, and cleanup preflight. Resume and compare byte hashes before considering the run successful. Estimates may change during scanning, but verified counts/bytes must never include pending or corrupt items. A synthetic catalog benchmark does not replace a physical-device UI test, and a small transfer does not replace the 100 GB drive run.

## Release gate

Enable phone cleanup only after the automated integrity/recovery checks pass. First-use device testing should use disposable copies and keep cleanup separate until verified. Any omitted hardware tests must remain explicitly unverified in the release notes; compile success alone is not an end-to-end transfer test.

**Phase 1 result — passed October 28, 2026.** The automated integrity and recovery checks and the intended phone/Mac acceptance workflows were completed successfully. The controlled 100 GB and 100,000-item performance benchmarks remain deferred to a later scale-validation phase.
