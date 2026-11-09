# Compatibility and troubleshooting

This page explains which Frigate and TV versions have been tested and what to
try when a camera does not play.

## Tested versions

| Item | Tested status |
| --- | --- |
| Frigate 0.17.2 | Supported stable version |
| Frigate 0.18.0 RC1 (`a745070`) | Verified against this exact prerelease, including camera browsing, Activity, Clips, and Monitor on the main TV |
| Android TV / Google TV | Android 7.0 or newer |
| Main test device | 2024 onn. 4K Pro with Android 14 |
| H.264 camera video | Tested |
| H.265 camera video | Tested on a TV that supports H.265 |
| Birdseye | Tested |
| Activity recordings | Browsing, filtering, reviewed status, seeking, saving, and playback tested |
| Clips | Preview pictures, playback, navigation, rename, share, and administrator deletion have automated local coverage |
| Frigate 0.18 Modes | Display, confirmed administrator switch, verification, and Undo have automated local coverage |
| Frigate 0.18 Motion Search | Start, progress, results, cancellation, no-result, failure, and timeout paths have automated local coverage |
| Frigate 0.18 Incidents | Authorization filtering and exact create, edit, assignment, and deletion contracts have automated local coverage |
| Activity search | Available on Frigate 0.17.2 and 0.18 when search is turned on; visual similarity search verified on the main TV |
| Activity summaries | Shown when Frigate has created a summary for that activity |
| Camera groups | Two and four cameras tested on the main device; results depend on the TV's video hardware |
| Picture-in-picture | Requires Android TV 14 or newer and support from the TV |
| Camera controls | Built for compatible Frigate PTZ cameras; physical-camera testing is still needed |
| Native TV alerts | Pop-up display, remote controls, privacy, recording previews, and timeouts tested on the main TV |
| Home Assistant notifications | Real text delivery and PIN-protected button responses verified on the main TV; pictures, privacy, and reconnection also have local test coverage |
| Monitor Mode | Deterministic promotion, recovery, privacy, timer, and cleanup paths have automated coverage; ONN endurance testing is pending |
| Highlights briefing | Briefing progress, private-camera protection, and review queues have automated coverage and TV checks; a first briefing covers up to 24 hours, with later briefings bounded to seven days |
| Automatic playback compatibility | Strategy selection, persistence, invalidation, and fallback paths have automated coverage; the full device/codec matrix is pending |

Newer parseable Frigate 0.18 builds are not rejected merely because their exact
build name is not listed here. They may work before they complete the same
checks, and Opah shows their tested status separately from basic compatibility.

## Camera video formats

Camera settings often use names such as H.264, H.264+, H.265, H.265+, or Smart
Codec.

- **H.264** is the most widely supported choice and is a good first option.
- **H.265** can provide high-quality video with less network traffic, but the TV
  must support it.
- **H.264+**, **H.265+**, and **Smart Codec** are camera-maker features. Their
  behavior varies and they may delay or prevent playback on some devices.

If a camera stays on **Preparing** or does not start:

1. Open **Settings** > **Cameras and Playback** > **Check camera compatibility**,
   choose the camera, and start with **Automatic**.
2. If Automatic cannot verify a choice, try **Reliable video only** to separate
   an audio problem from a video problem.
3. Try the camera's lower-resolution stream.
4. Turn off Smart Codec, H.264+, or H.265+ in the camera settings.
5. Make sure the camera creates a full video frame regularly. Camera interfaces
   may call this the keyframe or I-frame interval.
6. Try H.264 to determine whether the problem is specific to H.265 support.

## Camera previews and connection problems

The **Cameras** grid shows refreshed still previews. Move to a camera and press
**OK** to open live video. The preview status says when Opah last checked the
picture. If a preview fails, hold **OK** on that camera and choose
**Refresh preview**. The same options menu contains its favorites and layout
choices.

If one camera in a multi-camera view fails, select its retry action. The other
cameras keep playing. Monitor also keeps an unavailable message visible after
playback or a preview fails.

If the whole server is unavailable, check that it can be reached from your
network, then use **Try again** or **Change connection**. Connection setup
retains the entries while you test or correct them.

## Audio and live controls

Opah can play audio from the camera through the TV. It cannot send microphone
audio back to a camera or doorbell.

Choose **30 seconds earlier** to open available Frigate recording for the
current camera. Opah returns to live video when that short recording ends.
This depends on Frigate having recording for that moment.

The screen-size control can fit the whole camera picture inside the television
or fill the screen. The fill choice is saved separately for each camera.

## Activity playback

Choose **Next activity** while a saved Activity video is playing to continue
through the New or Reviewed list you opened. An explicit Highlights or New
activity queue stays within its selected items. Opah skips missing items and
items without a recording, and it does not start over after the last item.
Pressing Back returns to the Activity browser with its filters and list
position.

The recorded-video timeline can receive focus. Use left or right on the
timeline to move by 10 seconds in saved or reviewed activity.

In **Activity** > **History**, use **Date & time** to select a date and hour in
the TV's local time zone. An empty period can mean Frigate has no recording for
that hour. Use **Earlier** or **Later** to check nearby hours.

If a save or review change fails, read the message beside the affected action
and use its retry. If a save involving several cameras has an uncertain result,
check **Clips** before trying again to avoid saving a second copy.

## Activity search

Search appears only when Frigate reports that the feature is available. Search
must also be turned on and ready in Frigate. A short description such as
`red car` usually works better than a long sentence.

For activity with a detected object, **Find similar activity** opens Search and
starts a visual search using that activity. **Similar to…** identifies the
search. Refreshing or changing its filters keeps the same starting activity;
entering a new description starts a text search instead.

Both kinds of search require Frigate's search feature to be available. Camera
and local privacy restrictions still apply. Losing access to the starting
camera clears a similarity search.

## Camera groups and controls

Camera groups use muted video and choose lower-bandwidth streams when they are
available. Two cameras may also use picture-in-picture on a supported Android
TV 14 device. If a group struggles to play, try fewer cameras or lower camera
resolutions.

Camera movement, zoom, focus, and saved positions appear only when both Frigate
and the camera report support. These controls have not yet been tested with a
physical PTZ camera, so support may vary.

## TV alerts and Monitor Mode

TV alerts require Android notification permission on Android 13 or newer. A
notification channel can also be turned off separately in Android settings.
Some TVs delay background work or reboot delivery even when alerts are enabled.
Open **Settings** > **TV alerts** to see Opah's current status.

Start **Monitor** from Cameras, a saved view, or a Frigate camera group. It uses
snapshots for the quiet baseline and brings at most one camera to live video
at a time. If a TV struggles, use fewer cameras or lower-resolution streams.
Audio starts off unless you turn it on. **Timer** cycles through Never, 30,
60, and 120 minutes.

## Reading and navigating the interface

**Settings** > **Appearance** offers **High contrast**, **Reduce motion**, and
**Reduce transparency**, as well as System, Dark, Light, and Custom themes.
Your TV's text-size setting also affects Opah.

Press right on a Settings category to enter that page. Longer information pages
scroll with the remote. Back closes the nearest dialog or returns to the
previous screen. During playback, the first center-button press shows hidden
controls; a second press activates the focused action.

## Clips and Incidents

Clips stay on the Frigate server. If a clip does not play, first confirm that
the same clip opens in Frigate. Renaming or deleting clips and changing an
existing Incident require a Frigate administrator account. Switching Modes and
starting an on-demand recording also require an administrator account. Deleting
cannot be undone.

Incidents, Save all angles, Modes, on-demand recording, and Find motion here
require the exact supported Frigate 0.18 contract. Opah hides these choices on
Frigate 0.17.2 instead of showing empty or nonworking controls.

## Network setup

In a typical setup, Opah connects to:

- the Frigate web service, usually on port 8971; and
- Frigate's live-video service, usually on port 8554.

Most users do not need to enter the second address separately. Advanced
connection settings are available when the secure Frigate web address and the
local live-video address are different.

Use `https://` for the Frigate server address when possible. Keep the live-video
service inside a trusted local network rather than exposing it to the internet.

Opah can also connect directly to Frigate port `5000` without a username or
password. Frigate treats this as anonymous administrator access, so use it only
when the TV and server share a trusted private network. Do not expose port
`5000` to the internet. The authenticated Frigate address remains the
recommended choice for most installations.

## Report a compatibility problem

Please include:

- the Opah version;
- the exact Frigate version;
- the TV brand/model and Android version;
- the camera video format and resolution; and
- whether **Check camera compatibility**, **Reliable video only**, or the
  lower-resolution stream works.

Do not post your password, server address, camera names or images, Frigate
configuration, or unedited logs. See [Contributing to Opah](../CONTRIBUTING.md)
for more guidance.
