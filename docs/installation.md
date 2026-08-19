# Install Opah on Android TV or Google TV

Opah is currently installed from GitHub rather than Google Play. The download
is an APK, which is simply an Android installation file.

## Before you begin

You need:

- an Android TV or Google TV running Android 7.0 or newer;
- a working Frigate server that the TV can reach;
- a Frigate username and password; and
- a way to send the downloaded APK to the TV, such as a USB drive or a trusted
  file-transfer app.

## Download the correct file

1. Open the project's [GitHub Releases page](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/releases).
2. Open the newest release.
3. Under **Assets**, download the file named like `opah-v0.2.0.apk`.

Do not download Opah from an unofficial mirror or APK website.

## Install it on the TV

1. Copy the APK to the TV using a USB drive or a trusted file-transfer method.
2. Open the APK with the TV's file manager.
3. If Android asks for permission to install apps from that file manager, open
   the displayed settings page and allow it. This is Android's normal warning
   for apps installed outside Google Play.
4. Return to the APK and choose **Install**.
5. When installation finishes, open **Opah for Frigate** from the Apps row.
6. For extra safety, you may turn off the file manager's install permission
   after Opah is installed.

The exact wording and location of these options varies by television.

## Connect to Frigate

Enter your Frigate server address, username, and password. Use the same secure
`https://` address you normally use in a browser when possible.

Most people should leave **Advanced connection settings** unchanged. Open them
only if your Frigate setup uses a separate local address for live video.

The on-screen keyboard opens only after you select a field. After the first
successful sign-in, Opah can remember the connection and sign in automatically.

## Update Opah

Download the newer Opah APK from this repository and open it on the TV. Android
should offer to update the existing app. Your saved server and sign-in should
remain in place.

If Android says the update is not compatible with the installed copy, stop and
make sure both copies came from this official repository. Do not uninstall the
working app unless you are prepared to enter your connection details again.

## Remove Opah

Uninstall Opah through the TV's normal app settings. Uninstalling removes the
saved Frigate connection and sign-in information from that TV.

## Optional: check the download

Each release includes a small `.sha256` file. Advanced users can compare it
with the APK to confirm the download was not damaged or changed.

On Windows PowerShell:

```powershell
Get-FileHash .\opah-v0.2.0.apk -Algorithm SHA256
Get-Content .\opah-v0.2.0.apk.sha256
```

On macOS or Linux:

```bash
sha256sum opah-v0.2.0.apk
cat opah-v0.2.0.apk.sha256
```

The long strings of letters and numbers should match exactly.

## Optional: install with ADB

ADB is an Android developer tool. You do not need it for a normal installation.
If you already use ADB, connect to the intended TV and run:

```text
adb install -r opah-v0.2.0.apk
```

Turn off wireless debugging when you finish.

## About Android's installation rules

Android's rules for apps installed outside Google Play are changing over time
and can vary by country and device. Opah will continue to document the current
GitHub installation method. Google's current explanation is available in its
[Android developer verification guide](https://developer.android.com/developer-verification/guides).
