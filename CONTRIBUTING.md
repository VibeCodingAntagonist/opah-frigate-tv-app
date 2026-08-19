# Contributing to Opah

> This page is for developers and people contributing code. You do not need it
> to install or use Opah. For installation help, see
> [Install Opah](docs/installation.md).

Thank you for helping improve Opah. Changes should continue to work well with a
television remote, run smoothly on modest TV hardware, and protect information
from private Frigate servers.

## Before opening an issue

- Search existing issues.
- Confirm the behavior on the newest Opah release when possible.
- Record the exact Frigate and Android versions.
- Remove passwords, server addresses, camera names and images, and private URLs.
- Use the bug or feature issue template.

## Development setup

Install JDK 17, Android SDK Platform 36, and Build Tools 36.0.0. Then run:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Windows contributors can use `gradlew.bat`. Do not commit local SDK settings,
build outputs, APKs, signing files, device captures, or a real Frigate
configuration.

## Pull requests

1. Keep each change focused and explain user-visible behavior.
2. Add repeatable automated tests where practical.
3. Exercise D-pad navigation for UI changes and avoid touch-only interactions.
4. Never show cameras that the signed-in Frigate account cannot access.
5. Keep diagnostics private-safe and never log passwords or complete server
   responses.
6. Run the complete local verification command before submission.
7. Complete the pull request checklist honestly; an unrun test is not a pass.

Tests on a real Frigate server and TV are welcome, but share only anonymous
results. Never upload raw device captures or a real server configuration.

## Style and architecture

- Kotlin and Compose follow the existing formatting and architecture.
- Networking stays behind the project's existing data-access boundaries.
- Playback uses the `LivePlayer` abstraction so policy can be tested without a
  physical decoder.
- Screen state should remain stable when focus moves, the app enters the
  background, or Android restarts the activity.
- Avoid adding dependencies unless their maintenance, license, and binary cost
  are justified.
