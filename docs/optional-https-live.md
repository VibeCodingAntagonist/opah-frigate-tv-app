# Optional HTTPS live playback

Normal camera clicks, startup targets, Monitor, compatibility checks and recordings
keep their existing behavior. No preference or existing profile is migrated.

In the native Cameras screen, open a camera tile's options and choose **Play using
HTTPS**. This is an explicit choice for that session only; leaving and opening the
camera normally returns to the original player. The action is offered only for a
supported HTTPS profile. It requires the Frigate MSE WebSocket route and an existing
named go2rtc stream. A missing route/stream produces an error, not a silent RTSP
fallback. Existing stream-quality selection and start-muted preference are used.

This does not add pinning, modify the Monitor view, or change the original RTSP
compatibility runner. It adds no test app variant, updater override or server config.

The HTTPS session retains TLS verification and authentication, disallows redirects,
revalidates access, and releases its transport and exclusive player lease when
leaving/backgrounding. Shared output tests cover lease contention and release.

## Validation

Unit tests cover default-off selection, explicit per-session selection, exclusion
of HTTP/recordings/diagnostics/existing RTSP capabilities, URI restrictions,
authorization, cancellation/resume, stream identity, buffering and codec negotiation.
CI runs unit tests, lint and a debug build without fork signing credentials.

The transport was tested in a separate fork on a Hisense with three cameras.
This new upstream-only menu integration has not been installed on that TV.
Maintainer/device acceptance should exercise: ordinary RTSP playback with HTTP and
HTTPS profiles; explicit HTTPS with and without a reachable MSE route; camera
switching/back/background/resume; unchanged Fixed/Patrol Monitor behavior;
recording playback and explicit compatibility diagnostics. Server restream setup
remains the administrator's responsibility.
