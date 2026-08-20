# Compatibility and troubleshooting

This page explains which Frigate and TV versions have been tested and what to
try when a camera does not play.

## Tested versions

| Item | Tested status |
| --- | --- |
| Frigate 0.17.2 | Fully tested stable version |
| Frigate 0.18.0 beta 3 (`344efb6`) | This exact beta build was tested |
| Android TV / Google TV | Android 7.0 or newer |
| Main test device | 2024 onn. 4K Pro with Android 14 |
| Additional test device | NVIDIA Shield TV with Android 11 |
| H.264 camera video | Tested |
| H.265 camera video | Tested on a TV that supports H.265 |
| Birdseye | Tested |
| Review recordings | Browsing, filtering, reviewed status, seeking, and playback tested |
| Picture-in-picture | Tested on the main device; support varies by TV |

Newer Frigate releases may work before they are listed here, but they have not
completed the same checks yet. Future Opah updates will aim to support new
Frigate versions without breaking the versions already listed.

## Camera video formats

Camera settings often use names such as H.264, H.264+, H.265, H.265+, or Smart
Codec.

- **H.264** is the most widely supported choice and is a good first option.
- **H.265** can provide high-quality video with less network traffic, but the TV
  must support it.
- **H.264+**, **H.265+**, and **Smart Codec** are camera-maker features. Their
  behavior varies and they may delay or prevent playback on some devices.

If a camera stays on **Preparing** or does not start:

1. Turn on **Force RTP over TCP** in Opah's playback options.
2. Try the camera's lower-resolution stream.
3. Turn off Smart Codec, H.264+, or H.265+ in the camera settings.
4. Make sure the camera creates a full video frame regularly. Camera interfaces
   may call this the keyframe or I-frame interval.
5. Try H.264 to determine whether the problem is specific to H.265 support.

## Audio and live controls

Opah can play audio from the camera through the TV. It cannot send microphone
audio back to a camera or doorbell.

Pausing a live camera freezes the current picture. Opah does not record a
temporary copy of live video, so live rewind is not available.

## Network setup

In a typical setup, Opah connects to:

- the Frigate web service, usually on port 8971; and
- Frigate's live-video service, usually on port 8554.

Most users do not need to enter the second address separately. Advanced
connection settings are available when the secure Frigate web address and the
local live-video address are different.

Use `https://` for the Frigate server address when possible. Keep the live-video
service inside a trusted local network rather than exposing it to the internet.

## Report a compatibility problem

Please include:

- the Opah version;
- the exact Frigate version;
- the TV brand/model and Android version;
- the camera video format and resolution; and
- whether **Force RTP over TCP** or the lower-resolution stream works.

Do not post your password, server address, camera names or images, Frigate
configuration, or unedited logs. See [Contributing to Opah](../CONTRIBUTING.md)
for more guidance.
