# Homeward

**Your memories, back home.**

Copy original photos and videos from an Android phone to an external drive attached to your Mac over local Wi-Fi. Transfers resume after interruptions. Files become backed up only after the Mac verifies their full SHA-256 hash.

No hosted cloud, account, internet connection, Docker, or subscription is needed to run it. A one-time pairing code authorizes the phone and pins the Mac's HTTPS certificate. Building from source requires downloading dependencies.

> **Project status:** Phase 1 complete as of October 28, 2026. The local Android-to-Mac backup, resumable transfer, integrity verification, interruption recovery, progress reporting, and verified cleanup workflows passed automated checks and physical-device acceptance testing with approximately 14,000 items and 60 GB of media, including large videos. Larger controlled stress benchmarks remain future validation work.

## Engineering focus

- Resumable, idempotent 4 MiB chunk uploads with durable offsets on both devices
- SHA-256 verification before a file is marked backed up or eligible for cleanup
- Certificate-pinned local HTTPS pairing without accounts or hosted infrastructure
- Persistent Android queueing through Room and WorkManager
- External-volume identity checks, storage reservations, atomic publication, and recovery manifests
- Explicit waiting and failure states for network, battery, thermal, storage, permission, and source-file changes

## Installation

### Before you begin

You need:

- An Android phone running Android 14 or later.
- An Apple Silicon Mac.
- A writable external drive formatted as APFS, HFS+, or exFAT. FAT32 is not supported because it cannot hold large videos.
- At least 5 GiB more free space than the media you want to back up.
- The phone and Mac connected to the same Wi-Fi network. Guest Wi-Fi often blocks devices from seeing each other.
- The `personal-backup` Mac executable and `Homeward.apk` Android installer.

> **Keep existing drive data safe:** formatting is not required. If you decide to reformat a drive for another reason, copy its existing files somewhere safe first.

The examples below use a drive named `YourDrive`. Replace it with the name shown for your drive in Finder. Use the same library path every time:

```text
/Volumes/YourDrive/PersonalBackup
```

### 1. Initialize the external drive library

Open Terminal in the folder containing `personal-backup`, then make it executable:

```sh
chmod +x ./personal-backup
```

Initialize the backup library:

```sh
./personal-backup init --root "/Volumes/YourDrive/PersonalBackup"
```

Run `init` **once only**. It creates Homeward's catalog, certificate, and recovery data on the external drive. For later sessions, start at step 2.

### 2. Start the Mac server

In the same Terminal window, run:

```sh
./personal-backup start --root "/Volumes/YourDrive/PersonalBackup"
```

Leave this Terminal window open while Homeward is syncing. If macOS asks whether to allow incoming connections, choose **Allow**. The server uses local HTTPS port `8787`; router port forwarding is not needed.

You should see a message similar to:

```text
Listening on HTTPS port 8787; library …
```

### 3. Display the pairing QR code

Open a **second Terminal window** in the folder containing `personal-backup`, then run:

```sh
./personal-backup pair --root "/Volumes/YourDrive/PersonalBackup"
```

Keep this window visible for the phone. The QR code expires after ten minutes and works once. Run the command again if it expires.

To confirm the drive and server are available at any time:

```sh
./personal-backup status --root "/Volumes/YourDrive/PersonalBackup"
```

### 4. Install Homeward on the phone

1. Copy `Homeward.apk` to the phone and open it.
2. If Android asks, temporarily allow installation from the browser or file manager you used.
3. Open **Homeward** after installation.

Installing a later Homeward APK over the current app preserves its queue and pairing when it uses the same package name and signing key.

### 5. Grant access and pair

When Android asks, allow:

- **All photos and videos** so Homeward can discover the complete accessible library.
- **Original media/location access** so original bytes and embedded metadata remain available.
- **Local network access** so the phone can reach the Mac without using the internet.
- **Notifications** so transfer progress remains visible.
- **Camera access** if you want to scan the QR code.

In Homeward, choose **Scan Mac code** and scan the QR code from step 3. You can also paste the complete pairing text shown beneath the code.

If Android grants access only to selected photos, Homeward can back up only those items. Cloud-only media and private app folders are outside Phase 1. Download cloud-only originals into the phone's accessible media library before syncing them.

### 6. Start the first backup

Choose **Sync now** in Homeward. Keep the Mac awake, the server running, and the external drive connected. The first scan can take time for a large library.

The app shows discovered media, verified backups, remaining bytes, speed, active files, and failures. An upload is counted as backed up only after the Mac verifies the completed file.

Start with a small test selection if this is your first setup. Open several completed files from `PersonalBackup/originals` on the Mac before expanding to the full library.

### Connection help

If Homeward cannot discover the Mac automatically:

1. On the Mac, open **System Settings → Wi-Fi → Details → TCP/IP** and note the Wi-Fi IP address.
2. Generate a new pairing code using that address:

```sh
./personal-backup pair --root "/Volumes/YourDrive/PersonalBackup" --host 192.168.1.20
```

Replace `192.168.1.20` with the Mac's actual address. Check Android's local-network permission, the macOS firewall, and Wi-Fi device isolation if it still cannot connect. The Wi-Fi network does not need internet access.

If an older build reports a TLS handshake failure during pairing, stop the server with Control-C and refresh its local certificate:

```sh
./personal-backup refresh-certificate --root "/Volumes/YourDrive/PersonalBackup"
```

Restart the server, generate a fresh QR code, and pair again. Refreshing the certificate does not change the media library or catalog.

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
