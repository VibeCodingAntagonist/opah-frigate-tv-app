# Opah

Opah is an Android TV and Google TV app for viewing cameras and recordings from
an existing [Frigate NVR](https://frigate.video/). Designed
for a television remote, it lets you watch live cameras, use multi-camera views,
review recent activity, search saved video, browse history, and keep or share
important clips. Opah can also show calm TV alerts, receive Home Assistant
messages with optional pictures and response buttons, watch a camera view for
important activity, and summarize what happened since you last watched.

Opah organizes everyday camera tasks into clear, approachable screens that
are comfortable to navigate from the sofa.

Opah connects directly from your TV to your Frigate server and does not operate
a separate camera cloud or recording service. It is an independent community
project and is not affiliated with, sponsored by, endorsed by, or supported by
Frigate, Inc.

## Screenshots

| Cameras | Live camera |
| --- | --- |
| <img src="docs/screenshots/04-home.png" alt="Opah Cameras grid with fictional camera previews" width="720"> | <img src="docs/screenshots/10-live-playback.png" alt="Opah live camera with compact glass controls" width="720"> |

| New activity | Activity history |
| --- | --- |
| <img src="docs/screenshots/08-review.png" alt="Opah New activity with fictional alerts" width="720"> | <img src="docs/screenshots/19-activity-history.png" alt="Opah History with date and time controls" width="720"> |

| Guided setup | Optional Home Assistant buttons |
| --- | --- |
| <img src="docs/screenshots/28-guided-setup.png" alt="Opah guided setup offers either connection or both" width="720"> | <img src="docs/screenshots/35-home-assistant-message.png" alt="Fictional Home Assistant notification with optional response buttons" width="720"> |

See the [complete interface gallery](docs/screenshots.md). All camera scenes,
names, addresses, and data shown in the gallery are fictional examples.

## What you need

- A working Frigate server to view cameras and recordings
- Android TV or Google TV running Android 7.0 or newer
- A Frigate username and password for the recommended authenticated connection
  (not needed for a direct port `5000` connection on a trusted private network)
- Network access from the TV to the Frigate server

Home Assistant is optional. You can connect it separately to receive messages
from your home automations, even before setting up Frigate.

Opah supports Frigate 0.17.2 and the Frigate 0.18 API. Frigate 0.18.2
(`cfa08c5`) is the latest tested 0.18 build. Other compatible
0.18 builds can connect without an exact build-name match, but are not all
individually tested. See the [compatibility guide](docs/compatibility.md) for
tested features, camera video formats, devices, and troubleshooting tips.

## Install Opah

Opah is currently downloaded from GitHub rather than Google Play.

1. Download [`opah-latest.apk`](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/releases/latest/download/opah-latest.apk)
   from the newest Opah release.
2. Send that file to the TV.
3. Follow the [step-by-step installation guide](docs/installation.md).

Only download Opah from this repository. Copies from other websites may have
been changed or may be unsafe.

## Connect for the first time

On a new installation, Opah opens guided setup. You can also open
**Connections** > **Setup & checks** > **Start guided setup**. Choose Frigate,
Home Assistant, or both. Either service can go first, and you can set up the
other one later.

For the recommended authenticated Frigate connection, Opah asks for:

1. your Frigate server address;
2. your Frigate username; and
3. your Frigate password.

Use the same secure Frigate address you normally use in a web browser when
possible. The setup form keeps **Advanced live video options** closed unless
you need a different live-video host or port. **Check connection** checks your
details without saving them; **Sign in and save** saves the connection. Back
keeps entered details while the setup remains open.

The guide lets you choose notifications, review privacy, and add an optional
PIN before requesting the Android permissions those notifications need.
**Apply choices** enables your selections. Allowing an Android permission alone
does not turn notifications on. Message details start hidden on a new Home
Assistant connection, and interactive alerts, pictures, and automation buttons
keep their separate opt-ins.

Some TVs open Opah's app settings when you choose Android notification settings.
Allow notifications there, return to Opah, and apply your choices in the guide.

Use **Check setup** to check saved connections without repeating sign-in.
Connection access, notification settings, TV pop-ups, previews, and actual
Home Assistant delivery have separate results. A fictional preview tests
appearance; it does not prove a message arrived from Home Assistant.

After a successful sign-in, choose **Cameras** to begin watching. Opah can
securely remember the connection and sign in automatically. Open **Connections**
> **Frigate** to change the server, sign out, or forget its saved information.

If your TV and Frigate server share a trusted private network, Opah can also
connect directly to Frigate port `5000` without an account. Enter an address
such as `http://frigate.local:5000` and leave the username and password blank.
This connection has full Frigate access, so never expose port `5000` to the
internet. Most people should use Frigate's authenticated address instead.

## Find your way around

To take a break from both Frigate alerts and Home Assistant messages, open
**Connections** > **Notifications**. Choose **Pause for 15 minutes** or
**Pause for 1 hour**. The page shows when notifications will resume, and
**Resume notifications** ends the pause early.

Pausing removes visible alerts and their buttons. Frigate keeps recording and
Home Assistant automations keep running. Existing Frigate snoozes and schedules
stay in place, and paused notifications are not replayed when you resume.

After Frigate is connected, Opah opens on **Cameras**, with **Activity**,
**Clips**, **Connections**, and **Settings** in the side navigation. Move left from the edge of a page to open navigation, choose a
destination, and press the remote's center button to enter it. Back closes the
nearest dialog or returns to the previous screen.

The glass design uses compact controls, clear focus outlines, and large camera
pictures. Browsing remembers your place when you return from playback. If you
previously chose a different startup camera or view, Opah keeps that choice.

## Make Cameras yours and watch cameras together

The **Cameras** grid shows periodically refreshed previews. Move to a camera
and press **OK** to watch it live. The headings stay visible while you scroll.
Preview age and failure messages help distinguish an older picture from a
working camera. Hold **OK** on a camera and choose **Refresh preview** to
check its picture again.

For an optional way to watch one live camera, hold **OK** on its tile and choose
**Play using HTTPS**. It uses your secure Frigate connection for that viewing
session. It requires an authenticated HTTPS address the TV can verify and
Frigate 0.18.2 or a compatible later 0.18 release. Ordinary **OK** keeps using
the standard live connection. See [HTTPS live video](docs/compatibility.md#optional-https-live-video)
for requirements and controls.

Press and hold **OK** on a camera or view to pin it as a
favorite, move a favorite earlier or later, or hide a camera from the grid.
Choose **Restore** to return to the default camera layout.

To make a multi-camera view, choose **New view**, select two to four cameras,
and choose **Next: name this view**. Enter a name and choose **Save view**.
**Back to cameras** lets you revise the selection without losing the name.
Saved views stay on that device and appear alongside camera groups already
set up in Frigate. Open a view to watch its cameras together. If a Frigate group
has more than four cameras, Opah asks which ones to watch.

On supported TVs, a two-camera group can also pop out over another app. Camera
names and playback controls hide while you watch; press the center button to
show the controls again.

## Find and review activity

Open **Activity** to see alerts and other recorded activity:

- **New** shows activity that still needs review.
- **Reviewed** shows activity you have already marked reviewed.
- **History** lets you browse saved video by camera. Choose **Date & time** to
  jump to a day and hour, or use **Earlier** and **Later** for nearby hours.
- **Search** finds saved activity from a short description when search is set
  up in Frigate.
- **Motion** appears on supported Frigate 0.18 servers and finds movement in a
  selected part of the picture.

Open an item to watch it, mark it reviewed or not reviewed, save its recording,
or find activity that looks similar. **New** offers **Mark all reviewed** for
the items currently shown, after a warning. The selected item stays marked
while you move into its actions. Choose **Load more activity** at the end of the list
to reach older items. Frigate may provide a short summary, a recognized name,
or a license plate.

**Find similar activity** opens Search and immediately looks for pictures
similar to the selected activity. The **Similar to…** label shows the starting
activity. Refreshing or applying filters keeps that starting point. This
requires search to be turned on in Frigate and an activity item with a
detected object.

Open **Filter** > **Review all new activity** to review the current new items
as a queue. Its heading shows which queue you are in. If saving a clip or
marking an item fails, Opah shows the problem beside that action and offers a
retry.

While a saved Activity video is playing, choose **Next activity** to continue
through the New or Reviewed list you opened, without returning to the browser.
In a review queue, it stays within that queue. At the end, the control shows
**Caught up** for a review queue or **No next activity** for ordinary browsing.
Move focus to the playback timeline and use left or right to move by 10 seconds.
Back hides visible playback controls first. Pressing Back while the controls
are hidden returns to the selected item, filters, and position in the Activity
list. Camera views and Monitor use the same hide-first behavior.

## Stay aware without watching constantly

Optional features help you keep up with activity:

- **TV alerts** can notify you about important Frigate activity while another
  TV app is open. You choose the cameras, kinds of activity, quiet times, and
  how much detail a notification may show.
- **Home Assistant** can send text messages from your automations to this TV.
  Open **Connections** > **Home Assistant**, sign in, allow TV pop-ups, and turn
  **Receive notifications** on. Message details start hidden. The TV must be on
  and connected to receive messages. See the [setup guide](docs/home-assistant.md).
- **Monitor** on Cameras watches several cameras and can bring one active
  camera forward when something important happens. You can also start it from
  a saved view or Frigate camera group. Audio is off unless you turn it on.
  Its timer offers **Never**, **30**, **60**, or **120 minutes**.
- **Highlights** on Cameras shows a short briefing when there is unseen
  activity. Choose **Play highlights**, **Review as a list**, or dismiss only
  the summary currently shown.

Shared TVs can use a local PIN to protect selected app areas, administrative
actions, destructive actions, and private cameras. For the strongest
protection, also use a restricted Frigate account for the TV.

Frigate pop-ups normally leave remote control with the app you are watching.
To act on a pop-up, turn on **Settings** > **TV alerts** > **Preview** >
**Interactive Frigate alerts**. Remote control moves temporarily to the alert.
Press down to choose **Watch recording**, **Live camera**, or **Snooze 15 min**
when available. Snooze pauses alerts from that camera. Back closes the pop-up
without marking the activity reviewed. Pop-ups close automatically, including
after a minute of continued interaction. Home Assistant messages remain passive
unless you enable their separate automation-button option.

With interactive Frigate alerts on, **Recording previews in alerts** adds a
**Preview recording** action for available recordings. Previews start muted;
you can pause, turn sound on, or open the recording in Opah. Back returns
control to your current app. A preview stays within the alert's one-minute
limit and keeps its picture, when available, if video cannot play. The alert's
privacy setting must allow full pictures. Previews also require current camera access and stay off in guest
mode or when the recording needs a PIN unlock.

Home Assistant also supports optional pictures and up to three callback buttons.
Both start off, and message details must be enabled. Buttons require the
**Home Assistant actions** PIN lock; pictures follow Opah's camera and preview
privacy rules. See the [Home Assistant guide](docs/home-assistant.md) for setup,
supported attachments, examples, and action limits.

## Save important recordings

Choose **Save recording** while reviewing recorded activity. You can also keep a
selected part of **History**. Open **Clips** to see preview pictures, play a
clip, or share a local copy. Frigate administrators can also rename a clip or
delete it after a confirmation warning.

With a supported Frigate 0.18 server, **Save all camera angles** keeps the same moment
from several authorized cameras. Frigate administrators can group clips into
simple **Incidents**.

Clips remain on the Frigate server. They are not copied into a
separate Opah cloud service or stored as a second recording archive on the TV.

## Control and size cameras

If Frigate reports compatible camera controls, press and hold the camera tile
on **Cameras** and choose **Camera controls**. In control mode, the D-pad moves
the camera, center stops it, and a compact shelf contains supported zoom,
focus, and saved positions.

While watching a camera or Birdseye, use the screen-size control to choose
whether the whole picture fits on the television or fills the screen. Opah
remembers that choice for each camera and for Birdseye on the device. If an
audio track prevents playback, try **Settings** > **Cameras and Playback** >
**Check camera compatibility** and choose a video-only option.

## Adjust appearance and settings

Settings keeps its categories on the left and the selected page on the right.
Press right or the center button on a category to open it. Server information,
storage, appearance, and device details have their own layouts and scroll when
more information is available.

Open **Settings** > **Appearance** to choose **System**, **Dark**, **Light**, or
**Custom**. Custom mode lets you adjust the accent and background while seeing
a preview. **High contrast**, **Reduce motion**, and **Reduce transparency**
offer simpler, more readable presentation. Rounded corners are part of the
design; **Extra rounding** is an optional softer treatment.

Updating Opah keeps your saved appearance and startup choices.

## Get updates

Opah normally checks the official GitHub release page shortly after it starts.
A small dot inside the Settings gear means an update is available. Open
**Settings** > **Update** to read the release notes, download the verified APK,
and open Android's installer. You can turn automatic checks off on the same
page. Opah never downloads or installs an update without your action.

## Optional camera shortcuts

Some Android TV button-mapping and home-automation apps can send Android
commands. They can use this command to open a chosen camera directly:

```text
am start -a android.intent.action.VIEW -d "opah://live/front_door" app.opah.tv/.MainActivity
```

Replace `front_door` with the camera's exact name in Frigate. Capital letters
and underscores must match. Opah must already have a saved, working connection,
and it will open only cameras available to that Frigate account.

Home Assistant's Android TV ADB integration can send the same `am start`
command with its `androidtv.adb_command` action. A button-mapping app can call a
Home Assistant webhook, which then sends the command to the TV.

Tools that use an Android extra instead of a link can use this equivalent
command:

```text
am start -n app.opah.tv/.MainActivity --es camera "front_door"
```

## Important limitations

- Opah needs an existing Frigate server; it does not record cameras by itself.
- Camera audio plays from the TV, but speaking through a camera or doorbell is
  not supported.
- Picture-in-picture depends on support from the TV and Android version.
- Camera groups are muted and use lower-bandwidth streams when available. Some
  TVs may not be able to play four cameras at once.
- PTZ camera controls depend on support from Frigate and the camera.
- TV alerts depend on Android notification and background-service settings.
  A TV may delay alerts or restart after reboot when Android restricts the app.
- Monitor Mode depends on the TV's video hardware. Opah promotes only one live
  camera at a time, but long-running behavior still varies by device.
- Update downloads and installation are manual. Automatic availability checks
  can be turned off in Settings.

## Privacy and safety

- Your Frigate password and saved sign-in are encrypted on the device.
- Opah does not send camera data through an Opah cloud service.
- Opah does not keep its own archive of camera recordings.
- Local PIN and private-camera settings stay on the TV and do not change
  Frigate accounts or permissions.
- Alert rules and briefing progress are stored without camera images or a copy
  of your recordings.
- The Diagnostic data page is designed to leave out passwords, private addresses,
  and camera images.
- Use a secure `https://` Frigate address when one is available.
- Keep the live-video connection inside your trusted home or business network.

Read [Privacy and security](docs/security-model.md) for a fuller explanation.
Report a security concern through GitHub's
[private reporting form](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/security/advisories/new).

## Help and feedback

When reporting a problem, include the Opah version, Frigate version, Android or Google TV device model,
and a short description of what happened. Do not post your password, server
address, camera names or images, or unedited logs.

Developers who want to build or improve Opah should start with
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

Thanks to [Goofyschooler](https://github.com/Goofyschooler) for the optional HTTPS
live-playback work adapted from [PR #18](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/pull/18),
and to [megabitus98](https://github.com/megabitus98) for the detailed camera
playback reports in [#21](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/issues/21)
and [#22](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/issues/22).

Opah is available under the [Apache License 2.0](LICENSE). Release history is in
[CHANGELOG.md](CHANGELOG.md).

Frigate and Frigate NVR are trademarks of Frigate, Inc. Opah uses those names
only to explain what the app works with.
