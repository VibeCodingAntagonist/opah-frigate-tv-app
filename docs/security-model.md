# Privacy and security

This page explains what Opah saves, how it communicates with Frigate, and what
you can do to keep the connection safe.

## In everyday terms

- Your Frigate username, password, and saved sign-in are encrypted on the
  device.
- Opah connects directly from the TV to the Frigate addresses you provide. It
  does not send your camera data through an Opah cloud service.
- Opah does not create its own camera recording archive.
- The Diagnostics page is designed to leave out passwords, private addresses,
  camera images, and detailed server responses.
- Signing out removes the saved sign-in. Choosing **Forget server** also removes
  the saved server information.

## Information kept on the TV

Opah keeps only the information needed to remember your preferences and connect
to Frigate:

- the server connection settings;
- display and playback preferences;
- the current signed-in session; and
- your username and password if automatic sign-in is enabled.

Camera previews are held temporarily while they are displayed. Opah does not
keep a separate long-term copy of Frigate recordings.

## Keeping the connection safe

- Use a secure `https://` Frigate address when one is available.
- Keep Frigate's live-video service inside a trusted home or business network.
- Do not expose the live-video port directly to the internet just to use Opah.
- Keep the TV and Frigate server updated and limit physical access to them.
- A modified or rooted TV may be able to bypass normal Android protections.

Frigate decides which cameras an account may use. Opah also filters information
to the cameras available to the signed-in account, but it cannot protect against
a compromised Frigate server.

## Technical details for security reviewers

This section is optional and is intended for developers and security reviewers.

- Saved credentials and sessions use Android Keystore-backed AES-256-GCM
  encryption and are excluded from Android backup.
- Web requests reject redirects so a password or signed-in session is not
  automatically resent to a different address.
- Secure connections use Android's normal certificate and hostname checks.
- Diagnostics use safe error categories rather than raw responses, cookies,
  addresses, or stack traces.
- Release automation checks the Android package, version, publisher identity,
  and download checksum before publishing an APK.

No application can prevent someone from photographing the television, using an
HDMI capture device, or taking an Android screenshot when the device allows it.
