# Screenshot assets

> This page is for developers regenerating the public screenshots. Users can
> view the [interface gallery](../screenshots.md) instead.

The screenshots come from a special documentation copy of Opah that uses fixed
fictional data and generated camera scenes. It never connects to a Frigate
server or reads information saved by the normal Opah app. Android installs it as
the separate package `app.opah.tv.docs`.

The camera scenes were generated specifically for this documentation set. They
do not depict the maintainer's cameras, home, account, server, network, or
Review history. Names, server addresses, dates, device details, versions,
performance values, and storage values visible in the screenshots are fictional
examples.

## Regenerate the set

Build and install the documentation APK, then pass the target shown by
`adb devices -l` explicitly:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
.\gradlew.bat testDebugUnitTest lintDocumentation assembleDocumentation
.\.toolchains\android-sdk\platform-tools\adb.exe -s SERIAL install -r `
    .\app\build\outputs\apk\documentation\app-documentation.apk
.\tools\capture-documentation-screenshots.ps1 -Serial SERIAL -Force
```

The capture script wakes the selected device, opens each example screen, checks
that every PNG is 1920×1080, removes its temporary copy from the device, checks
the picture-in-picture example, and returns the documentation app to Home. It
never clears or launches the normal Opah package.

Review every regenerated image before committing it. The documentation build is
designed to be safe for public use, but a person must still inspect every image.
