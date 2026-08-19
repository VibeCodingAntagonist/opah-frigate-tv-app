# Changelog

This page lists the user-visible changes in each Opah release.

## Unreleased

No changes have been announced yet.

## 0.2.0 - 2026-08-19

### New features

- Home, Cameras, Birdseye, Review, Information, and Settings pages designed for
  a television remote
- Live playback for cameras using H.264 or H.265 video when supported by the TV
- Camera audio with mute and a video-only troubleshooting option
- A network compatibility option called **Force RTP over TCP**
- Picture-in-picture live video without audio on supported televisions
- Frigate alert and detection browsing with filters, details, and recordings
- Recorded-video controls with a timeline and easy seeking
- Frigate storage and performance information
- Securely saved connection details and automatic sign-in
- Light, dark, system, and custom color themes
- Automatic recovery when a live stream is slow to start or stops updating

### Privacy and safety

- Saved passwords and sign-in information are encrypted on the TV.
- Opah refuses unexpected web redirects that could send sign-in information to
  the wrong server.
- Camera lists and results are limited to the cameras available to the signed-in
  Frigate account.
- Diagnostics leave out passwords, private addresses, camera images, and other
  sensitive details.
- Automated release checks help prevent private test data or signing files from
  being published.

### Tested with

- Frigate 0.17.2
- The exact Frigate 0.18.0 beta 3 build `344efb6`
- The 2024 onn. 4K Pro with Android 14
- H.264 and H.265 live video, Birdseye, Review recordings, and
  picture-in-picture

### Known limitations

- Live video cannot be rewound.
- Audio travels from the camera to the TV only; two-way talk is not available.
- Video support depends on the formats supported by the TV and camera.
- Later Frigate 0.18 builds are not automatically covered by the beta 3 test.

[Unreleased]: https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/releases/tag/v0.2.0
