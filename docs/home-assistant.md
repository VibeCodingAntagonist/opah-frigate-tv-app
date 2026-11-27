# Home Assistant notifications

Opah can register your TV with Home Assistant so that your automations can send
text messages to it. Home Assistant and Frigate are separate connections; you
can set up either one first.

Your Home Assistant automations choose when to send a message and what it
says. For example, an automation can tell the TV that the washing is finished,
someone rang the doorbell, or it is time to put the bins out. Opah displays
the message while you use the TV, subject to your notification and privacy
choices.

Opah registers through Home Assistant's built-in **Mobile App** integration.
It does not need a separate Opah custom integration. Frigate camera viewing
and direct Frigate alerts continue to use their own connection.

## Connect your TV

For help with each step, open **Settings** > **Connections** and choose
**Guided setup**. Choose Home Assistant alone or alongside Frigate.
The guide saves each connection independently and lets you return to unfinished
steps later. To use the existing connection form directly:

1. Open **Settings** > **Connections** > **Home Assistant**.
2. Enter the Home Assistant address you normally use and a recognizable TV
   name, such as **Opah living room TV**.
3. Choose **Sign in** and sign in on the Home Assistant page. Use your normal
   Home Assistant login and any additional verification it requests.
4. Open **Message settings** and turn on **Receive notifications**. If asked,
   allow Android notifications and use **TV pop-ups** to let Opah appear over
   other apps. Return to **Home Assistant messages** to finish.

The first Home Assistant sign-in does not require an Opah PIN. If Connections
is locked, choose **Set up Home Assistant** on the lock screen. The Settings
PIN still protects changes after a connection is saved. Guest Mode cannot set
up or manage connections.

If your TV opens Opah's app settings, allow notifications there and return to
Opah. In guided setup, use **Apply choices** to enable your selections.

Use an `https://` address when possible. A local HTTP address requires a
confirmation because the sign-in is not encrypted. Sign-in methods that require
opening another website are not supported by the TV sign-in page.

Home Assistant's **Mobile App** integration must be enabled. It is included in
Home Assistant's usual default setup. This is an independent Opah integration;
you do not need the official Home Assistant phone app on the TV.

## Send a message

Use **Settings** > **Notifications** to pause messages and Frigate alerts
together for 15 minutes or 1 hour. **Resume notifications** ends the pause early
without turning either service on or changing Frigate's snoozes and schedules.
Messages received during the pause are not shown later. Your Home Assistant
connection and automations continue running.

To check delivery, open **Settings** > **Connections** > **Check setup**.
Choose **Listen for a Home Assistant test** while notifications are connected.
Send the exact short message shown using this TV's Mobile App notification
action. The check waits up to five minutes and reports success only when that
message arrives from Home Assistant. A fictional preview does not count.

In Home Assistant's automation editor or **Developer Tools** > **Actions**,
choose the Mobile App notification action for the TV name you registered. Use
a title such as **Laundry** and a message such as **The washing is finished**.

Messages appear in Opah and over other TV apps when TV pop-ups are allowed.
Home Assistant details start hidden, so the initial banner says
**Home Assistant** and **New notification**. Turn **Show message details** on
in Opah if you want the title and message visible. Opah's shared-TV privacy
settings still apply, and guest mode keeps message details hidden.

Frigate alerts take priority when notifications arrive together. Both kinds of
alerts remain separate, so clearing one does not clear the other.

## Pictures and buttons

Pictures and automation buttons start off. Enable each separately in
**Settings** > **Notifications** > **Home Assistant messages**. **Show message details** must also be on.

**Show pictures** supports PNG and JPEG images from your configured Home
Assistant server, using `/media/local/`, `/api/camera_proxy/camera.name`, or
`/api/image_proxy/image.name`. Images can use a relative path or the same server
address. Opah rejects other websites and redirects. Unavailable, oversized, or
invalid pictures leave the text message visible.

Pictures follow Opah's notification privacy settings and stay hidden in guest
mode. When camera access or local privacy restrictions could apply, an image
without a verified camera mapping stays hidden. A camera name in a message
does not prove which camera the picture contains. Pictures are temporary;
Opah does not create an image archive.

Turn on **Allow automation buttons** in **Home Assistant messages**.
Buttons temporarily take remote control. Press **Down** to select a button,
**Left/Right** to move, and **OK** to choose it. **Back** closes the pop-up.
Choose **Send request** to confirm.

Turn on **Require PIN for Home Assistant buttons** in **Settings** > **Privacy** > **Protected areas**
to ask for your PIN on every press. With that setting off, only buttons with
`authenticationRequired: true` in the automation ask for a PIN. If you have
not set an Opah PIN, those buttons stay hidden and ordinary buttons still work.

Opah supports up to three ordinary Home Assistant callback actions. These send
a `mobile_app_notification_action` event to the registration that delivered
the message. They cannot open external links, run Android commands, or accept
typed replies. Guest mode disables buttons.

A button sends a response, such as **Done**, back to Home Assistant. Create an
automation to listen for that response and decide what happens next. Adding a
button to a notification does not create its response automation.

For example, send this through the TV's Mobile App notification action:

```yaml
message: The washing is finished
title: Laundry
data:
  tag: laundry
  image: /media/local/laundry.png
  actions:
    - action: LAUNDRY_DONE
      title: Done
      authenticationRequired: true
```

An automation listening for `mobile_app_notification_action` can match
`event.data.action` to `LAUNDRY_DONE` and `event.data.tag` to `laundry`.
The automation decides what happens; a button's title cannot establish that
its action is harmless.

Buttons expire after five minutes, or sooner when the sender sets
`data.timeout` in seconds. Clearing or replacing the message, losing the
connection, or changing privacy invalidates its buttons. A request is attempted
once and never replayed after reconnection. **Request sent** means the request
was accepted, not that the automation completed. If the result is uncertain,
check Home Assistant before sending a new notification.

## What to expect

- The TV must be on and connected to Home Assistant. Opah reconnects when the
  network returns, but messages sent while it is disconnected are not queued
  for later delivery.
- Text remains the default. Optional pictures and callback buttons support the
  subset above. Video, spoken messages, external-link buttons, typed replies,
  and Android commands are not supported.
- Messages stay on the TV for at most five minutes, or a shorter sender timeout.
- A notification with a `data.tag` replaces the previous notification with that
  tag. A `clear_notification` message with the same `data.tag` clears it.
- Turning **Receive notifications** off keeps the saved connection for later.
- **Display time** under **Settings** > **Notifications** > **TV pop-ups**
  controls how long a banner stays visible for either service. **Off** keeps Android notifications without showing a
  pop-up banner.
- **Disconnect** stops messages and removes the saved Home Assistant sign-in
  from the TV. It does not sign out of Frigate. Remove the old Mobile App device
  in Home Assistant if you no longer need it.
- Home Assistant setup does not change Opah's Frigate alert filters or quiet
  times. Choose when to send Home Assistant messages in your automations.

## If messages do not appear

Check that Opah shows **Connected** in **Settings** > **Connections** > **Home Assistant**,
that **Receive notifications** is on, and that Android allows Opah notifications
and TV pop-ups. Confirm that the automation targets the TV you registered.

If Home Assistant reports that the device is not connected to local push
notifications, wait for Opah to reconnect and send a new message. If Opah asks
you to sign in again, disconnect and reconnect Home Assistant. If you changed
the TV name or registered a different TV, choose its new action in your
automations.

See [Privacy and security](security-model.md) for shared-TV settings and removing
Home Assistant access if the server was unavailable when you disconnected.
