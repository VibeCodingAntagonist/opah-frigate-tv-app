# Changelog

This page lists the user-visible changes in each Opah release.

## 0.7.2

- Hold **OK** on a camera and choose **Play using HTTPS** for an optional live
  connection through your secure Frigate address. This choice applies only to
  that viewing session and requires Frigate 0.18.2 or a compatible later 0.18
  release.
- Show a clearer explanation when a camera omits setup information needed for
  live playback, with another stream or HTTPS offered when available.
- Check the TV's reported video-size limits more carefully and offer another
  configured stream when the current one is too large to play.

Camera permissions, PIN protection, guest mode, and normal certificate checks
continue to apply. HTTPS does not reduce the camera's resolution or remove the
TV's video-format limits.

Thanks to [Goofyschooler](https://github.com/Goofyschooler) for the HTTPS work
adapted from [PR #18](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/pull/18),
and to [megabitus98](https://github.com/megabitus98) for the detailed reports in
[#21](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/issues/21) and
[#22](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/issues/22).

## 0.7.1

- Keep Frigate TV alerts and automatic Monitor activity updates available
  after compatible Frigate 0.18 server updates, including Frigate 0.18.2.
- Improve activity synchronization when connecting or reconnecting to Frigate.
- Update compatibility information for Frigate 0.18.2 while retaining camera
  permissions, privacy settings, and the existing Frigate 0.17.2 restrictions.

## 0.7.0

- Open a camera with **OK** and hold **OK** for its options. Camera headings
  stay visible as you scroll through the grid.
- Choose **Find similar activity** to start a visual search immediately, with
  a clear starting activity that stays selected when you refresh or filter.

- Pause Frigate and Home Assistant notifications together for 15 minutes or
  1 hour, with an early Resume control and no replay of paused activity.

- Follow guided setup for Frigate, Home Assistant, or both, with separate
  notification choices, privacy review, and connection checks. Resume unfinished
  steps or check existing connections without signing in again.

- Receive optional Home Assistant pictures and up to three automation buttons,
  with separate opt-ins, PIN protection, and temporary media and actions.

- Choose optional interactive Frigate pop-ups to watch a recording, open a live
  camera, or snooze that camera's alerts using the TV remote. Passive pop-ups
  remain the default, and existing privacy protections still apply.

- Enable recording previews in interactive alerts to watch an available
  recording in the pop-up. Previews start muted and let you pause, turn sound
  on, or open the recording in Opah.

- Open **Connections** from the side menu to set up Frigate and Home Assistant
  in one place, with the same PIN protection as Settings.
- Register this TV with Home Assistant and show text messages from your home
  automations alongside Frigate alerts, including while another TV app is open.
- Choose whether Home Assistant messages show their details on a shared TV.
  Notifications reconnect when the TV's network returns.

- Stop camera video and audio when leaving Opah for the Home screen, freeing
  playback resources for other apps.
- Show the complete camera picture by default in Monitor and camera groups,
  without cropping the edges.
- Apply each camera's existing **Fit video to screen** or **Stretch video to
  screen** choice consistently to previews and live video.

## 0.6.0

### A complete visual redesign

- Explore a new glass-style interface with camera pictures at the center,
  compact controls, clear focus outlines, and less unused space.
- Open on **Cameras**, with **Activity**, **Clips**, and **Settings** close by
  in the side navigation. Existing startup choices are kept.
- Use the new design in System, Dark, Light, and Custom appearance modes
  without losing saved colors. Reduce transparency joins high contrast and
  reduced motion options.
- Read redesigned Settings, Appearance, server information, Device Info, and
  About pages with more useful layouts and scrolling.
- See more of the picture through compact live-video controls. Recorded video
  keeps its selectable timeline.

### Easier browsing and review

- Return to your place, selection, and filters after viewing a camera or
  recording, with more predictable remote navigation.
- Press Back to hide visible controls while viewing cameras or recordings.
  Press Back with the controls hidden to leave the view.
- Create a saved camera view in two clear steps, and revise its cameras or name
  without starting over.
- Open the intended items from Highlights and see which review queue you are
  watching. Continue through it with **Next activity**.
- Keep **Next activity** within the New or Reviewed list you opened.
- Keep the selected Activity item visible while choosing its actions, and use
  **Load more activity** at the end of the list to reach older activity.
- Jump directly to a date and hour in History.

### Clearer feedback and useful fixes

- See when a camera preview was checked and retry a failed preview. Camera
  groups and Monitor show failures clearly, and retrying one group camera
  keeps the others playing.
- See an explanation and retry when saving a clip or marking activity fails.
- Correct connection details or test the connection without losing the form.
- Set Monitor's timer to Never, 30, 60, or 120 minutes.
- Read larger text without clipped camera descriptions or overlapping Settings
  headings. Adjustable controls provide clearer accessibility information.
- Rounded corners are built into the design; additional rounding is optional
  and off by default for new settings.

## 0.5.2 - 2026-09-08

- Made remote navigation predictable across Activity, Clips, Motion Search,
  Settings, and full-screen camera views, including clear movement between
  tabs, lists, choices, and results.
- Added a selectable recorded-video timeline. Use left or right to move by
  10 seconds while watching saved or reviewed activity.
- Restored the colored update indicator inside the Settings gear.

## 0.5.1 - 2026-09-07

- Restored a visible **Mark all reviewed** action on the New Activity page
  and made it work with whichever activity type and filters are currently
  shown.
- Added direct connections to Frigate port `5000` without a username or
  password for trusted private networks. Opah clearly identifies the
  full-access connection and never sends or stores account credentials for it.

## 0.5.0 - 2026-09-05

### A clearer television interface

- Use a rebuilt Android TV interface with compact navigation, consistent focus
  outlines, and layouts that make better use of the screen.
- See new and reviewed Activity separately, use visual search controls, and
  read recorded-video dates directly in the player.
- Use visual Appearance, Server Performance, and Server Storage dashboards
  instead of long text-only settings lists.
- Set up the normal Frigate address first and reveal a separate live-video
  address only when the network needs it.

### Calm awareness

- Receive optional native TV alerts for important Frigate activity while
  another TV app is open.
- Choose important activity, custom filters, cameras, schedules, Frigate Modes,
  notification detail, and snooze options.
- See when Android permission or channel settings are blocking alerts and send
  a local test alert without waiting for camera activity.

### Shared-TV privacy

- Protect Activity, History, Search, Clips, Incidents, Settings, and selected
  actions with a local PIN.
- Mark selected cameras as private and include them in the local PIN protection.
- Lock or unlock each protected area independently without changing the
  Frigate account or its permissions.

### Monitor and briefing

- Start Monitor Mode from a saved View or Frigate camera group and let Opah
  bring one important active camera forward.
- Choose Fixed, Calm, Active, or Patrol behavior, optional audio, a timer, and
  whether the TV should stay awake.
- See a factual Home briefing when activity happened since you last watched.
- Play a bounded highlight sequence, open the new items as a Review queue, or
  dismiss only the items currently shown.

### More reliable playback

- Let Opah test valid stream, transport, audio, and decoder choices and remember
  what works for each camera and TV.
- Run **Check camera compatibility** from Cameras and Playback settings and
  reset remembered choices when troubleshooting.

Opah remains compatible with Frigate 0.17.2. Frigate 0.18 is the primary API
generation for features that need newer Review, Mode, and activity metadata.

## 0.4.0 - 2026-08-25

### A new living-room design

- Use a simpler Home, Activity, Clips, and Settings layout designed for a TV
  remote.
- Preview a camera in a large Home image before opening it.
- Pin favorites, reorder them, or hide cameras you do not need on Home.
- Choose whether Opah starts on Home, the last view, a camera, or a saved view.

### Live camera actions

- Go back 30 seconds and return to live video automatically.
- Take a snapshot of the current camera picture and share it from the TV.
- Start and stop an on-demand recording as a Frigate administrator on supported
  Frigate 0.18 servers.
- View the current Frigate 0.18 Mode and, as an administrator, switch it with
  confirmation and Undo.

### Faster activity review

- Review new activity as a continuous queue.
- Choose **Next activity** while a saved Activity video is playing to keep
  moving through the same list without leaving the player.
- Return from playback to the same Activity item, filters, and list position.
- Optionally mark an item reviewed when its video finishes and see when the
  review queue is caught up.
- Mark every new alert currently shown as reviewed after a warning.
- Move through saved video on a continuous timeline with recording, motion,
  detection, and alert markers.
- Switch cameras without losing the selected time and choose how much time the
  timeline shows.
- Find motion in a selected part of the picture on supported Frigate 0.18
  servers.
- Use recent searches, television voice input when available, and one-press
  suggestions such as Person, Package, Today, and Overnight.

### Clips and Incidents

- Browse kept video in the redesigned Clips page.
- Share clips from the TV. Frigate administrators can also rename or delete
  them.
- Organize clips into simple Incidents on supported Frigate 0.18 servers.
- Save the same moment from several authorized cameras with Save all angles.

### Personalization and updates

- Create a custom color style with simple television-remote controls.
- Turn on high contrast or reduce interface motion.
- Let Opah check quietly for releases after it starts, or turn automatic checks
  off.
- Read longer release notes directly on the Update page with a television
  remote.

Opah remains compatible with Frigate 0.17.2. Features that require Frigate
0.18 stay hidden on older servers.

## 0.3.0 - 2026-08-22

### Camera groups

- Watch two to four live cameras together.
- Save favorite camera groups on the device.
- Open camera groups already set up in Frigate.
- Use picture-in-picture with two cameras on compatible TVs.

### Activity

- Browse saved video by camera, day, and hour.
- See where more motion happened in saved video.
- See how much activity still needs review and review everything currently
  shown at once.
- See clearer activity details, including Frigate summaries, recognized names,
  and license plates when available.
- Find activity that looks similar to an item you are reviewing.
- Search saved activity using a short description when the feature is set up
  in Frigate 0.17.2 or 0.18.
- Filter activity by camera, object, recognized name, area, license plate,
  time, and review status.
- Mark activity as reviewed or not reviewed from its details or video.
- Save a selected part of History as a recording.

### Saved recordings

- Save a recording from activity details or video playback.
- Browse saved recordings with preview pictures and recording details.
- Play saved recordings from the new Saved page.
- Permanently delete a saved recording after a confirmation warning when
  signed in as a Frigate administrator.

### Camera controls

- Move, zoom, focus, and open saved positions on compatible cameras.

### Video display

- Fill the television screen with a camera picture and remember the choice for
  each camera.

### Updates

- See an indicator when a newer Opah release is available.
- Read the new release notes from the Update page.
- Download an official Opah update and open it with Android's installer.
- Download the newest Opah APK using the stable `opah-latest.apk` filename.

## 0.2.2 - 2026-08-20

### Easier setup and Review

- Improved first-time setup when using a television remote, keyboard, or mouse.
- Kept connection details in place after **Test connection**.
- Added a warning before leaving setup with unfinished changes.
- Added **Mark as reviewed** to Review details.
- Added a visible **Reviewed** label to reviewed items.

### Camera shortcuts

- Added a camera link that compatible button-mapping and home-automation apps
  can use to open a chosen live camera directly.

## 0.2.1 - 2026-08-20

### Maintenance update

- Updated the text and artwork shown in the TV launcher and while Opah starts.
- Added an About page with the app version, privacy information, license,
  project link, and support information.

### Important update note

- Android cannot install `0.2.1` over `0.2.0` because the releases use different
  security keys. Uninstall `0.2.0` first, then install `0.2.1` and connect to
  Frigate again. Later updates should install normally.

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
