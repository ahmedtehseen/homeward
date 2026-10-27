# Validation status

This file records checks confirmed for the Phase 1 deliverable. Phase 1 was accepted on October 28, 2026 after the automated suite and intended phone, Mac, Wi-Fi, and external-drive workflows completed successfully.

## Confirmed automated checks

- The Go server test suite passed with the race detector enabled: `go test -race ./...`.
- Go static analysis passed: `go vet ./...`.
- The race-enabled server suite includes and passed the synthetic `TestCatalog100000Items` SQLite catalog test.
- The Apple Silicon server executable was built as a signed Mach-O arm64 binary. Release binaries are kept outside source control.
- `docs/openapi.yaml` parses as YAML. Its documented paths and response shapes were checked against the current `/v1` HTTP handlers.

## Physical-device acceptance

Pairing and sustained transfer from a physical Android phone to an external exFAT drive on an Apple Silicon Mac are working over local Wi-Fi. The app correctly waited and resumed after phone Wi-Fi was disabled, after the phone changed to another Wi-Fi network and returned home, after a server stop/restart during upload, and after Mac sleep/wake. Transfers resumed near their acknowledged progress after server restart, and no duplicates were found in the manually inspected sample.

An accidental external-drive disconnect during a write also recovered after reconnecting the same drive and restarting the server. This confirms the tested recovery path, but abrupt exFAT removal remains unsafe and should not be used as a routine test because the filesystem itself can be damaged.

Adding videos during an active backup updated totals and queued the new files. Deleting those sources correctly produced explicit inaccessible-source failures. The UI action clears inaccessible items from the visible queue and totals without deleting Mac backups.

Version 1.1.0 renames the app to Homeward and adds total transferred bytes, remaining bytes, speed, percentage, and an overall progress bar to its active-transfer notification. The Android release build, lint checks, unit tests, signing verification, Go server tests, in-place phone update, and notification behavior passed.

The remaining Phase 1 device scenarios, including process interruption, restart recovery, permission and waiting states, background resumption, and verified cleanup behavior, were reported complete and working on October 28, 2026. The completed library run covered approximately 14,000 items and 60 GB of photos and videos, including large video files. Phase 1 is accepted for the intended personal phone, Mac, network, and drive setup.

## Deferred scale validation

The Phase 1 scale result is approximately 14,000 items and 60 GB of real media, including large videos. The controlled 100 GB external-drive benchmark and 100,000-item physical-device/UI benchmark were not separately measured. The passing synthetic 100,000-item server catalog test does not measure phone scanning, paging, thumbnails, memory use, or UI responsiveness at that size. These larger stress benchmarks remain documented in [test-plan.md](test-plan.md) for a later performance phase and do not block the completed personal-backup workflow.
