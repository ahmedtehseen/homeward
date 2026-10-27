# Validation status

This file records checks confirmed for the current deliverable. It distinguishes automated evidence from hardware acceptance work that still has to be run.

## Confirmed automated checks

- The Go server test suite passed with the race detector enabled: `go test -race ./...`.
- Go static analysis passed: `go vet ./...`.
- The race-enabled server suite includes and passed the synthetic `TestCatalog100000Items` SQLite catalog test.
- The Apple Silicon server executable was built as a signed Mach-O arm64 binary. Release binaries are kept outside source control.
- `docs/openapi.yaml` parses as YAML. Its documented paths and response shapes were checked against the current `/v1` HTTP handlers.

## Physical-device progress

Pairing and sustained transfer from a physical Android phone to an external exFAT drive on an Apple Silicon Mac are working over local Wi-Fi. The app correctly waited and resumed after phone Wi-Fi was disabled, after the phone changed to another Wi-Fi network and returned home, after a server stop/restart during upload, and after Mac sleep/wake. Transfers resumed near their acknowledged progress after server restart, and no duplicates were found in the manually inspected sample.

An accidental external-drive disconnect during a write also recovered after reconnecting the same drive and restarting the server. This confirms the tested recovery path, but abrupt exFAT removal remains unsafe and should not be used as a routine test because the filesystem itself can be damaged.

Adding videos during an active backup updated totals and queued the new files. Deleting those sources correctly produced explicit inaccessible-source failures. Version 1.0.1 adds a UI action to clear inaccessible items from the visible queue and totals without deleting Mac backups; this action still needs confirmation on the physical phone.

Version 1.1.0 renames the app to Homeward and adds total transferred bytes, remaining bytes, speed, percentage, and an overall progress bar to its active-transfer notification. The Android release build, lint checks, unit tests, signing verification, and Go server tests pass; the new notification layout still needs confirmation on the physical phone.

Force-killed server recovery, Android process termination/reboot, permission denial, thermal gating, long-term background scheduling, system Trash cleanup, and restoration of a previously dismissed source remain unverified on real hardware.

The 100 GB external-drive transfer and 100,000-item physical-device/UI acceptance runs have not been performed. The passing synthetic 100,000-item server catalog test does not verify phone scanning, paging, thumbnails, or UI behavior at that size. Files larger than 4 GiB, real removable-storage behavior, sustained throughput, memory use, and UI responsiveness at that scale therefore remain unverified.

Follow [test-plan.md](test-plan.md) on the intended phone, Mac, network, and external drive before relying on cleanup or using this as the only copy of irreplaceable media.
