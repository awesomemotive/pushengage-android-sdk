# Changelog

All notable changes to the PushEngage Android SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] - 2026-10-07

### Fixed
- **Offline notification tap crash.** Tapping the body of a notification while the device
  was offline crashed the SDK's background `:RegisterReceiverService` process with
  `SQLiteConstraintException: NOT NULL constraint failed: ClickRequest.action`, and that click
  was never reported. Body taps are now queued and reported with an empty action, the service
  stays alive until the queued click is written, and a click that cannot be tracked (no
  notification tag, or the queue write fails) is reported to the SDK's error log instead of
  crashing.
- **Click replay could crash the app at launch.** Re-sending clicks queued while offline ran on
  an unguarded background thread in the app's main process, on every connectivity change
  including the one delivered at SDK initialization. A database failure there is now reported
  to the SDK's error log instead of crashing the app.
- **In-app messages on devices without a working WebView.** Devices whose WebView provider is
  missing, disabled or mid-update threw while creating the message view and crashed the app.
  The message is now skipped and the queue moves on.
- **Offline clicks were counted twice.** A click queued while offline was re-sent once per
  connectivity broadcast and once per app process, so the dashboard showed two clicks for one
  tap. Replay now runs only in the app's main process, one pass at a time, sends each queued
  click once and deletes exactly that row when the server accepts it.
- **Action buttons acted on the wrong notification.** With two or more notifications with
  action buttons in the notification shade, tapping a button on an older one opened the newest
  notification's button URL, credited the click to the newest notification and dismissed it
  instead. Each notification's buttons now keep their own URL, tag and notification id.

### Security
- `NotificationService` is no longer exported. It is only ever started from inside the app,
  and the exported declaration let any installed app dismiss the host app's notifications and
  forge click analytics for its subscriber.

## [1.0.0] - 2026-09-29

### Added
- **In-App Messaging.** Design messages on the PushEngage dashboard and show them inside
  your app — no push subscription required.
  - Four layouts: top banner, bottom banner, center modal and full screen, rendered exactly
    as designed in the dashboard editor.
  - Show a message when the app opens (optionally after a delay) or when your app reports
    an event with `triggerIAMEvent(...)`, including conditions on the event's parameters.
  - Target messages with audience rules, segments and subscriber attributes, schedule start
    and end dates, and control how often each user sees them (one time, recurring or
    capped) and which shows first when several are eligible.
  - Button actions to open a URL, dismiss, or run a custom action in your app via
    `setIAMCustomActionHandler(...)`, which receives the dashboard's action name in
    `parameters["action"]`.
  - A "request notification permission" button that shows the system prompt and
    subscribes the user as soon as they allow it — no extra code in your app.
  - Impressions, clicks and closes reported to the dashboard, queued while offline and
    sent automatically when connectivity returns.
  - Works with edge-to-edge apps on Android 15 and later, and with React Native and
    Flutter apps that initialize the SDK after launch.

### Improved
- Works out of the box in apps minified with R8/ProGuard — the SDK now ships its own
  consumer rules, so no extra configuration is needed.

## [0.1.0] - 2026-06-05

### Added
- **User identification.** `identify(...)` links a subscriber to your own user fields
  (name, email, phone, profile ID and more), and `logout(...)` clears them while keeping
  the device subscribed.
- **Custom event tracking.** `trackEvent(...)` sends in-app actions such as add-to-cart or
  purchase, so they can start or stop workflow campaigns.
- **Notification badge count.** `setBadgeCount(...)` sets the number shown with
  notifications.
- **Firebase configuration check.** `setFcmConfigErrorListener(...)` alerts you when the
  app's Firebase project does not match your PushEngage site, so misconfigured builds are
  caught during integration.

## [0.0.6] - 2025-08-21

### Added
- Notification permission APIs: `requestNotificationPermission()` and
  `getNotificationPermissionStatus()`.
- Subscription control: `subscribe()`, `unsubscribe()`, `getSubscriptionStatus()`,
  `getSubscriptionNotificationStatus()` and `getSubscriberId()`.

### Improved
- Initialization auto-subscribes only when the notification permission allows it.

## [0.0.5] - 2024-06-05

### Added
- Trigger campaigns.
- Goal tracking.

## [0.0.4] - 2023-12-01

### Added
- Android 13 notification permission support.

### Improved
- General performance improvements.

## [0.0.3] - 2021-08-26

### Added
- Method overloads across the public API for simpler calls.

## [0.0.2] - 2021-08-25

### Added
- Initial release: push notifications for Android apps via Firebase Cloud Messaging.

[1.0.0]: https://github.com/awesomemotive/pushengage-android-sdk/releases/tag/1.0.0
[0.1.0]: https://github.com/awesomemotive/pushengage-android-sdk/releases/tag/0.1.0
[0.0.6]: https://github.com/awesomemotive/pushengage-android-sdk/releases/tag/0.0.6
[0.0.5]: https://github.com/awesomemotive/pushengage-android-sdk/releases/tag/0.0.5
[0.0.4]: https://github.com/awesomemotive/pushengage-android-sdk/releases/tag/0.0.4
[0.0.3]: https://github.com/awesomemotive/pushengage-android-sdk/releases/tag/0.0.3
[0.0.2]: https://github.com/awesomemotive/pushengage-android-sdk/releases/tag/0.0.2
