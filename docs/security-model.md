# Privacy and security

This page explains what Opah saves, how it communicates with Frigate, and what
you can do to keep the connections safe.

## In everyday terms

- Your Frigate username, password, and saved sign-in are encrypted on the
  device.
- Opah connects directly from the TV to the Frigate addresses you provide. It
  does not send your camera data through an Opah cloud service.
- Opah contacts the official GitHub release service to check for updates. It
  does not send camera images, recordings, or Frigate sign-in information to
  GitHub.
- Opah does not create its own camera recording archive.
- The Diagnostic data page is designed to leave out passwords, private addresses,
  camera images, and detailed server responses.
- Signing out removes the saved sign-in. Choosing **Forget server** also removes
  the saved server information and its local briefing progress.
- A local PIN can protect selected app areas, actions, and private cameras on a
  shared TV. It adds protection on the TV but does not replace Frigate account
  permissions.
- **Connections** uses the same PIN protection as Settings, including before a
  Frigate server is connected.

## Information kept on the TV

Opah keeps only the information needed to remember your preferences and connect
to Frigate:

- the server connection settings;
- display and playback preferences, including saved camera groups;
- alert choices, snoozes, and notification history needed to prevent repeated
  alerts;
- successful camera playback choices, stored under opaque local identifiers;
- local PIN verification and lockout records, never the PIN itself;
- bounded briefing activity IDs and acknowledgement progress, without preview
  images or recordings;
- the current signed-in session; and
- your username and password if automatic sign-in is enabled.

Camera previews are held temporarily while they are displayed. Opah does not
keep a separate long-term copy of Frigate recordings. Recordings saved through
Opah remain on the Frigate server.

Notification preview images are temporary and are removed when an alert is
dismissed, expires, or its privacy scope changes. Text-only alerts do not fetch
a preview. Opah's background awareness connection goes directly from the TV to
the signed-in Frigate server; there is no Opah notification relay.

Opah remembers the latest official release information so it does not need to
check GitHub every time it opens. An update APK is downloaded only after you
choose to download it. Opah verifies the file before asking Android to open the
installer.

Frigate pop-ups remain passive unless you enable **Interactive Frigate alerts**.
Interactive pop-ups require a deliberate selection before an action can run.
Queued pop-ups recheck the current account, camera permissions, notification
rules, and local privacy before appearing. Opening footage and snoozing also
check current access; an earlier notification cannot unlock a private camera.
Changing privacy or losing access removes affected pop-ups and their actions.
Home Assistant messages remain passive unless their separate automation-button
option is enabled. Frigate pop-up settings do not enable Home Assistant actions.

## Home Assistant notifications

Home Assistant is an optional, separate connection. Sign in on the Home
Assistant page shown by Opah. Opah saves the resulting sign-in tokens, server
address, TV name, and device registration in a separate encrypted store on the
TV. It does not save your Home Assistant password. These saved tokens are
excluded from backups and device transfers.

Opah connects directly to your Home Assistant server to receive messages. It
does not use an Opah notification relay, send location updates, or copy Frigate
credentials into Home Assistant. Signing out of Frigate does not disconnect
Home Assistant; manage each connection in **Connections**.

Message details start hidden. Enabling **Show message details** allows titles
and text on the TV, subject to Opah's notification privacy settings. Guest mode
keeps Home Assistant message details hidden. Turning notifications off clears
Home Assistant notices without clearing Frigate alerts.

Image previews require their own opt-in and permitted preview privacy. Opah
fetches only bounded PNG or JPEG attachments from supported routes at the
configured Home Assistant origin, without redirects or disk caching. Guest
mode, restricted camera access, private cameras, protected media areas, and
recognition restrictions suppress unclassified pictures. A message's camera
label cannot bypass those checks.

Automation buttons require a separate opt-in, the dedicated **Home Assistant
actions** PIN lock, and current PIN authorization. Only an explicit choice can
send a callback. Each button is bound to its originating registration and an
in-memory, expiring ticket. Replacement, clearing, disconnection, or privacy
revocation invalidates it. A notification can submit only one callback,
including when the network result is uncertain. Opah never automatically
retries or queues requests. Supported callbacks send a notification action
event; they cannot supply arbitrary event types, Android intents, service
calls, or external URLs.

Messages, pictures, and callback tickets are bounded and temporary, with a
maximum lifetime of five minutes. Privacy changes cancel pending image loads
and callback requests. A request already received by Home Assistant cannot be
withdrawn by closing the TV pop-up.

Use HTTPS when possible. Opah allows HTTP only for local addresses and asks you
to confirm before opening an unencrypted sign-in. Never expose an unencrypted
Home Assistant sign-in to the internet.

**Disconnect** removes Home Assistant credentials from the TV and attempts to
revoke that sign-in on the server. If the server is unavailable, remove Opah's
refresh token from your Home Assistant profile's **Security** page when you
can reach it. You can also remove the old Mobile App device in Home Assistant.

## Keeping the connection safe

Inline Frigate recording previews require separate opt-in, interactive alerts,
full notification pictures, and permission to open the recording. An alert
picture never grants playback access. Guest mode and a required PIN unlock
keep inline playback unavailable. Opening the recording in Opah uses the
normal authorization and PIN checks again.

Only an explicit remote action starts a preview. There is one preview at a
time, muted initially, with no persistent video cache. Back, timeout, pause,
permission loss, and privacy changes release its video and audio resources.
The original alert's one-minute maximum still applies. Recording requests stay
with the configured server, and redirects are rejected.

The shared notification pause hides both sources, removes visible alerts and
automation buttons, and prevents old activity from reappearing after resume.
It does not change camera access, privacy choices, service opt-ins, recordings,
or Home Assistant automations. The controls use the existing Settings PIN and
guest restrictions, including on TVs that use only Home Assistant.

Only local timing information is saved for the pause, with no notification
text, media, server addresses, or credentials. It is excluded from backup and
device transfer. Unreadable pause settings keep presentation paused until an
authorized user resumes it. Resuming still requires all normal privacy and
permission checks before new notifications can appear.

Guided setup preserves the same Settings PIN and guest-mode restrictions,
including before a Frigate connection exists. Notification choices require an
explicit **Apply choices**; resuming setup or returning from Android permission
settings does not enable a service. Existing privacy choices stay in place
unless you explicitly change them.

Unfinished form entries stay in memory while the guide is open. The password
is cleared when the guide closes. Saved setup progress contains only service
choices, notification choices, and the current step; it contains no addresses,
passwords, tokens, or connection-test results and is excluded from backup and
device transfer. Successful sign-ins use the existing protected credential
stores.

Connection checks do not discover new servers or send data to an outside test
service. A Frigate credential check uses a temporary session separate from the
saved connection. Home Assistant delivery checks use a short-lived test message
kept only in memory, tied to the current TV registration and cleared when the
connection stops. Previews use fictional text and never count as delivery.

- Use a secure `https://` Frigate address when one is available.
- Keep Frigate's live-video service inside a trusted home or business network.
- Do not expose the live-video port directly to the internet just to use Opah.
- Keep the TV and Frigate server updated and limit physical access to them.
- A modified or rooted TV may be able to bypass normal Android protections.

Frigate decides which cameras an account may use. Opah also filters information
to the cameras available to the signed-in account, but it cannot protect against
a compromised Frigate server.

Use a dedicated restricted Frigate account on a shared TV when possible. Opah's
local PIN cannot hide information that the Frigate account or another app on
the TV already exposes.

## Technical details for security reviewers

This section is optional and is intended for developers and security reviewers.

- Saved credentials and sessions use Android Keystore-backed AES-256-GCM
  encryption and are excluded from Android backup.
- The local PIN uses a salted, deliberately slow verifier protected by Android
  Keystore material. Failed attempts use a persisted lockout delay.
- Structured alert, playback, and briefing records use Room transactions.
  Profile, camera, authorization, and audience keys are locally keyed opaque
  digests rather than raw private URLs.
- Every notification, Monitor, briefing, deep-link, and playback action checks
  current Frigate camera authorization and local privacy again before use.
- Local briefing dismissal changes only Opah's bounded acknowledgement state;
  it does not mark the item reviewed in Frigate.
- Web requests reject redirects so a password or signed-in session is not
  automatically resent to a different address.
- Secure connections use Android's normal certificate and hostname checks.
- Diagnostics use safe error categories rather than raw responses, cookies,
  addresses, or stack traces.
- Before opening a downloaded update, Opah checks its Android package, version,
  publisher identity, and checksum.

No application can prevent someone from photographing the television, using an
HDMI capture device, or taking an Android screenshot when the device allows it.
