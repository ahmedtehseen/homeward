# Validation status

This file records checks confirmed for the current deliverable. It distinguishes automated evidence from hardware acceptance work that still has to be run.

## Confirmed automated checks

- The Go server test suite passed with the race detector enabled: `go test -race ./...`.
- Go static analysis passed: `go vet ./...`.
- The race-enabled server suite includes and passed the synthetic `TestCatalog100000Items` SQLite catalog test.
- The Apple Silicon server executable was built as a signed Mach-O arm64 binary. Release binaries are kept outside source control.
- `docs/openapi.yaml` parses as YAML. Its documented paths and response shapes were checked against the current `/v1` HTTP handlers.

## Physical-device progress

Pairing and sustained transfer from a physical Android phone to an external exFAT drive on an Apple Silicon Mac are working over local Wi-Fi. This is an active test run rather than completed acceptance evidence.

The external-drive removal, sleep/restart, Wi-Fi interruption, process termination, Android reboot, permission-denial, thermal, background scheduling, and system Trash flows remain unverified on real hardware.

The 100 GB external-drive transfer and 100,000-item physical-device/UI acceptance runs have not been performed. The passing synthetic 100,000-item server catalog test does not verify phone scanning, paging, thumbnails, or UI behavior at that size. Files larger than 4 GiB, real removable-storage behavior, sustained throughput, memory use, and UI responsiveness at that scale therefore remain unverified.

Follow [test-plan.md](test-plan.md) on the intended phone, Mac, network, and external drive before relying on cleanup or using this as the only copy of irreplaceable media.
