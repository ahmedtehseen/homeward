# Homeward

**Your memories, back home.**

Copy original photos and videos from an Android phone to an external drive attached to your Mac over local Wi-Fi. Transfers resume after interruptions. Files become backed up only after the Mac verifies their full SHA-256 hash.

No hosted cloud, account, internet connection, Docker, or subscription is needed to run it. A one-time pairing code authorizes the phone and pins the Mac's HTTPS certificate. Building from source requires downloading dependencies.

> **Project status:** Active development and hardware testing. Pairing, sustained transfers, Wi-Fi loss/network switching, server restart, Mac sleep, and external-drive reconnect recovery are working on physical hardware. Process/phone termination, large-library behavior, and verified cleanup still need acceptance testing before the app should be trusted as the only backup path.

## Engineering focus

- Resumable, idempotent 4 MiB chunk uploads with durable offsets on both devices
- SHA-256 verification before a file is marked backed up or eligible for cleanup
- Certificate-pinned local HTTPS pairing without accounts or hosted infrastructure
- Persistent Android queueing through Room and WorkManager
- External-volume identity checks, storage reservations, atomic publication, and recovery manifests
- Explicit waiting and failure states for network, battery, thermal, storage, permission, and source-file changes

## What you need

- Android 14 or later; the app targets Android 17.
- An Apple Silicon Mac and a writable external drive using APFS, HFS+, or exFAT. FAT32 cannot hold large videos and is rejected. **Do not format an existing drive to try this app.** Choose a suitable existing volume or back it up separately before any filesystem changes.
- Phone and Mac on the same Wi-Fi network, with device-to-device traffic allowed. Guest networks often block this.
- Enough drive space for the library plus a 5 GiB reserve. The Mac must remain awake and the drive mounted while syncing.

Start with a small folder and check the copied files before attempting your full library. Hardware acceptance tests and their status are in [docs/test-plan.md](docs/test-plan.md).

## Start the Mac server

Keep the `personal-backup` executable in a stable folder. Open Terminal in that folder. Replace `YourDrive` in these commands with your drive's actual name:

```sh
chmod +x ./personal-backup
./personal-backup init --root "/Volumes/YourDrive/PersonalBackup"
./personal-backup start --root "/Volumes/YourDrive/PersonalBackup"
```

Run `init` once only. It creates the library, its certificate, and SQLite catalog on that drive. `start` stays in the foreground; leave this Terminal open. Allow incoming local connections if macOS prompts. Default HTTPS port: **8787**. Do not configure router port forwarding.

In a second Terminal, from the same executable folder:

```sh
./personal-backup pair --root "/Volumes/YourDrive/PersonalBackup"
./personal-backup status --root "/Volumes/YourDrive/PersonalBackup"
```

If an older build reports a TLS handshake failure during pairing, stop the
server, refresh its local certificate, restart it, and generate a new QR code:

```bash
./personal-backup refresh-certificate --root "/Volumes/YourDrive/PersonalBackup"
```

Refreshing the certificate requires pairing the phone again. It does not alter
the media library or catalog.

The pairing code expires in ten minutes and can be used once. Generate another if necessary. Keep the code private until it expires. `status` checks the selected library and whether a process is listening on the configured port; the phone validates the server's identity when connecting.

## Install and pair the phone

1. Copy `Homeward.apk` to the phone using your preferred file-transfer method, then open it. Temporarily allow installation from that file manager/browser if Android requests it. Future updates must use the same app signing key.
2. Open **Homeward**. Grant photos **and** videos access to all media for a full-library backup, original-media/location access to preserve unredacted originals, and local-network access when Android requests it. Notifications make manual transfer progress visible. Camera permission is only needed to scan the pairing QR code.
3. Scan the Mac's pairing QR code, or paste the complete pairing JSON shown below it. The JSON includes the Mac address, library identity, certificate fingerprint, and temporary secret.
4. Choose **Sync now**. Check the progress and unresolved-item messages. Folder exclusions and charging-only mode can reduce the first transfer's scope.

Granting access only to selected photos backs up only those accessible items. Cloud-only media and private app folders are not scanned. Download originals into the phone's accessible media library first if you want them included. Keep the originals permission granted; the app must not silently back up redacted or transcoded substitutes.

### If discovery does not work

Use your Mac's Wi-Fi IP address from System Settings → Wi-Fi → Details → TCP/IP. Generate pairing data containing that address:

```sh
./personal-backup pair --root "/Volumes/YourDrive/PersonalBackup" --host 192.168.1.20
```

Use your actual address. Once paired, automatic local discovery searches for the same library; the app also allows entering an updated HTTPS server address manually. Certificate pinning remains required after an address change. Check local-network permissions, the Mac firewall, and guest-network isolation if the connection still fails. Wi-Fi does not need internet access.

## Everyday use

Automatic work runs in short Android-scheduled batches. **Sync now** requests a visible user-initiated job. Android may delay background work; force-stopping the app prevents it until you reopen it. A disconnected network, sleeping Mac, disconnected drive, low battery, severe heat, charging-only setting, or manual pause leaves work waiting for a later attempt.

Progress distinguishes queued, uploading, verifying, backed-up, and failed items. Uploaded bytes are not yet a verified backup. Totals remain provisional while scanning; estimates can change as new media is discovered. Repeated failures include a retry action. Original phone deletions do not delete the Mac copy.

Browse originals in Finder under `PersonalBackup/originals`. Dated files are grouped by UTC capture year/month; files without a capture date use `Undated`. A content hash prefixes each stored filename to avoid collisions. Identical content is stored once, with separate source metadata. Avoid modifying or moving files inside the managed library; copy a file elsewhere before editing it.

### Free up phone space

Choose backed-up items and use **Free up phone space**. The phone must still be paired and online. Each selected source is read and hashed again, its current version/size checked, and the Mac copy independently rehashed. Any mismatch, missing file, unavailable server, or wrong library blocks that item's cleanup.

Android then shows its own confirmation to move the eligible items to Trash. Canceling preserves them. Android Trash may retain the bytes until it is emptied or expires, so moving items to Trash does not necessarily reclaim storage immediately. Cleanup is never automatic; the Mac originals remain after the phone's items are trashed. Hash verification proves the stored bytes match at verification time, not that a drive cannot fail later. Keep another copy of irreplaceable media before relying on a single drive.

### Optional launch at login

First stop the foreground server with Control-C. Keep the executable at its permanent path, then run:

```sh
./personal-backup install-service --root "/Volumes/YourDrive/PersonalBackup"
```

This creates the current user's `dev.personal.backup` LaunchAgent and starts it. It retries startup while the selected drive is unavailable; it does not keep the Mac awake. To remove it:

```sh
./personal-backup uninstall-service --root "/Volumes/YourDrive/PersonalBackup"
```

Run removal while the drive is mounted. If the drive is permanently unavailable, stop and remove the LaunchAgent directly:

```sh
launchctl bootout "gui/$(id -u)" "$HOME/Library/LaunchAgents/dev.personal.backup.plist"
rm "$HOME/Library/LaunchAgents/dev.personal.backup.plist"
```

## Storage failures and recovery

The server checks the volume UUID during startup and the volume/library identity during operations. If the drive disappears, it refuses further writes instead of creating a replacement library on internal storage. Reconnect the same drive and restart the server if needed. Do not run `init` on the empty mount path.

- **Drive full:** free unrelated space or move unrelated data elsewhere. Active uploads reserve their remaining capacity (inactive reservations expire after seven days, preserving staged bytes); the app keeps at least 5 GiB free. Retry after capacity is available.
- **Read-only drive:** resolve its permissions or filesystem problem in macOS, then restart. Do not delete staged files to fix a permission problem.
- **Interrupted transfer:** resume normally. The phone queries the durable offset; unacknowledged bytes may be sent again safely.
- **Missing/corrupted original:** cleanup is denied. Keep the phone source and follow repair below.
- **Wrong library/certificate:** reconnect to the intended server or pair deliberately with the new destination. Do not bypass certificate checks.

Stop the server before maintenance (Control-C, or uninstall the login service). Make a separate copy of the library before recovery work, including `config.json`, `.personal-backup-library`, `server.crt`, `server.key`, catalog, `originals`, `manifests`, and `sources`.

```sh
./personal-backup check --root "/Volumes/YourDrive/PersonalBackup"
./personal-backup rebuild --root "/Volumes/YourDrive/PersonalBackup"
```

`check` reads and hashes every committed original. `rebuild` reconstructs catalog records from manifests only after checking each file; it can complete a publish interrupted between staging and final storage. It does not invent missing media. If the SQLite catalog itself is corrupt, preserve it and its journal separately while the server is stopped, then move them out of the library before `rebuild`. Losing the catalog also loses paired-device credentials, so generate a fresh pairing code afterward. Interrupted uploads without a final manifest will need to restart.

To quarantine one damaged object and permit uploading it again, use its 64-character SHA-256 from the integrity error/catalog:

```sh
./personal-backup repair --root "/Volumes/YourDrive/PersonalBackup" --sha REPLACE_WITH_64_CHARACTER_HASH
```

Repair preserves damaged files in `quarantine` and removes the object's catalog entry. Restart the server and retry/rescan from a phone that still has the original. Repair cannot restore missing bytes without another good copy. Do not manually delete `quarantine` until recovery has been checked.

## Rebuild from source

Sources are in `server/` and `android/`; [docs/openapi.yaml](docs/openapi.yaml) describes the local protocol, and [docs/architecture.md](docs/architecture.md) describes integrity boundaries.

For the Mac server, install Go 1.26 or later and Xcode Command Line Tools (required by SQLite's CGO driver). On an Apple Silicon Mac:

```sh
cd server
go test -race ./...
CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -trimpath -o ../personal-backup .
```

For Android, use JDK 17 or later and Android SDK Platform 37.0 (`platforms;android-37.0`). The included Gradle wrapper uses checksum-pinned Gradle 9.3.1 with Android Gradle Plugin 9.1.1. Set `ANDROID_HOME` to your SDK directory or configure `sdk.dir` in `android/local.properties`. Dependencies are pinned in the Gradle files. The app supports API 34+ and compiles/targets API 37.

Create `android/signing.properties` locally with these keys, pointing to your existing private signing keystore:

```properties
storeFile=/absolute/private/path/personal-backup-release.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=personal-backup
keyPassword=YOUR_PRIVATE_PASSWORD
```

Then, from `android/`:

```sh
./gradlew testDebugUnitTest assembleRelease
```

The release APK is under `app/build/outputs/apk/release/`. Do not distribute an unsigned APK. Increase `versionCode` for updates, and retain the same package name and signing key. Never commit or share `signing.properties`, the keystore, or its password with the source bundle.

Keep the signing key and its password outside the repository, back them up to an encrypted location, and retain them securely. Losing this key prevents installing later builds as updates to this APK; uninstalling the app to change keys erases its local queue and pairing settings, though the Mac originals remain.

See [docs/validation.md](docs/validation.md) for confirmed evidence and [docs/test-plan.md](docs/test-plan.md) for the remaining device-scale checks.
