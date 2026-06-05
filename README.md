<p align="center">
  <a href="https://www.pushengage.com">
    <img src="https://assetscdn.pushengage.com/site_assets/img/pushengage-logo.png" width="300" alt="PushEngage"/>
  </a>
</p>

<p align="center">
  <strong>Android Push Notification SDK</strong><br/>
  Add rich push notifications to your Android app in minutes.
</p>

<p align="center">
  <a href="https://jitpack.io/#awesomemotive/pushengage-android-sdk"><img src="https://jitpack.io/v/awesomemotive/pushengage-android-sdk.svg?style=flat-square" alt="JitPack"/></a>
  <a href="#"><img src="https://img.shields.io/badge/platform-Android%204.1%2B-brightgreen.svg?style=flat-square" alt="Platform"/></a>
  <a href="#"><img src="https://img.shields.io/badge/language-Java%20%7C%20Kotlin-blue.svg?style=flat-square" alt="Language"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green.svg?style=flat-square" alt="License"/></a>
</p>

---

## Why PushEngage?

PushEngage is a complete push notification platform that supports **both web and mobile** from a single dashboard. Unlike using Firebase Cloud Messaging directly, PushEngage gives you a full marketing toolkit on top: audience segmentation, automated drip campaigns, A/B testing, analytics, and a no-code campaign builder that non-technical marketers can use.

**Key features of the Android SDK:**

- **Rich Notifications** -- images, action buttons, custom sounds, and large icons
- **Deep Linking** -- route users to specific activities or fragments
- **Android 13+ Permission Handling** -- built-in runtime permission request with callbacks
- **User Identification** -- tie subscribers to your own user IDs via `identify` / `logout`
- **Custom Event Tracking** -- send custom in-app events to trigger or exit campaign workflows
- **Audience Segmentation** -- static and dynamic segments based on user behavior
- **Triggered Campaigns** -- send notifications based on in-app events
- **Goal Tracking** -- measure conversion events tied to notifications
- **Price Drop & Inventory Alerts** -- e-commerce trigger notifications
- **Subscriber Attributes** -- store custom key-value data per subscriber
- **Notification Analytics** -- track views, clicks, and conversions from the PushEngage dashboard
- **Java & Kotlin** -- full support for both languages

---

## Installation

### Gradle (via JitPack)

**Step 1:** Add the JitPack repository and Google Services classpath.

If your project is not using [centralized repository declaration](https://docs.gradle.org/current/userguide/declaring_repositories.html#sub:centralized-repository-declaration), add the following to your root `build.gradle`:

```groovy
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Add this line
        classpath "com.google.gms:google-services:4.3.10"
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

If your project is using [centralized repository declaration](https://docs.gradle.org/current/userguide/declaring_repositories.html#sub:centralized-repository-declaration), add the following to your `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

And add the Google Services classpath to your project-level `build.gradle` (or `build.gradle.kts`):

```groovy
plugins {
    id 'com.google.gms.google-services' version '4.3.10' apply false
}
```

**Step 2:** Add the dependency and Firebase plugin to your app-level `build.gradle`:

```groovy
plugins {
    id 'com.android.application'
    id 'com.google.gms.google-services'
}

dependencies {
    implementation 'com.github.awesomemotive:pushengage-android-sdk:0.1.0'
    implementation platform('com.google.firebase:firebase-bom:26.1.1')
}
```

> **Note:** PushEngage uses Firebase Cloud Messaging for delivery. Make sure your `google-services.json` is in your app module.

---

## Quick Start

### 1. Initialize the SDK

Create a custom `Application` class and initialize PushEngage in `onCreate`:

**Kotlin:**

```kotlin
import com.pushengage.pushengage.PushEngage

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        PushEngage.Builder()
            .addContext(this)
            .setAppId("YOUR_APP_ID")
            .build()
    }
}
```

**Java:**

```java
import com.pushengage.pushengage.PushEngage;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        new PushEngage.Builder()
            .addContext(this)
            .setAppId("YOUR_APP_ID")
            .build();
    }
}
```

Then register the application class in your `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApplication"
    ... >
    <!-- your activities -->
</application>
```

### 2. Request Notification Permission (Android 13+)

**Kotlin:**

```kotlin
PushEngage.requestNotificationPermission(this) { granted, error ->
    if (granted) {
        Log.d("PushEngage", "Permission granted, user subscribed!")
    } else {
        Log.d("PushEngage", "Permission denied")
    }
}
```

**Java:**

```java
PushEngage.requestNotificationPermission(this, new PushEngagePermissionCallback() {
    @Override
    public void onPermissionResult(boolean granted, Error error) {
        if (granted) {
            Log.d("PushEngage", "Permission granted, user subscribed!");
        } else {
            Log.d("PushEngage", "Permission denied");
            if (error != null) {
                Log.e("PushEngage", "Error: " + error.getMessage());
            }
        }
    }
});
```

The SDK automatically handles the permission request for Android 13+ and calls `PushEngage.subscribe()` when permission is granted. On earlier Android versions, notifications are enabled by default.

### 3. Check Permission Status

**Kotlin:**

```kotlin
val status = PushEngage.getNotificationPermissionStatus()
// Returns "granted" or "denied"
```

**Java:**

```java
String status = PushEngage.getNotificationPermissionStatus();
// Returns "granted" or "denied"
```

---

## API Overview

| Category | Methods |
|----------|---------|
| **Setup** | `Builder.addContext().setAppId().build()`, `enableLogging`, `setSmallIconResource`, `setBadgeCount`, `setFcmConfigErrorListener`, `getSdkVersion` |
| **Permissions** | `requestNotificationPermission`, `getNotificationPermissionStatus` |
| **Subscription** | `subscribe`, `unsubscribe`, `getSubscriptionStatus`, `getSubscriptionNotificationStatus` |
| **User Identity** | `identify`, `logout`, `addProfileId` |
| **Subscriber Data** | `getSubscriberId`, `getSubscriberDetails`, `getDeviceTokenHash` |
| **Attributes** | `addSubscriberAttributes`, `setSubscriberAttributes`, `getSubscriberAttributes`, `deleteSubscriberAttributes` |
| **Segments** | `addSegment`, `removeSegment`, `addDynamicSegment` |
| **Events** | `sendTriggerEvent`, `sendGoal`, `trackEvent`, `addAlert` |
| **Campaigns** | `automatedNotification` (enable/disable) |

Full API reference: [Android SDK Documentation](https://www.pushengage.com/api/mobile-sdk/android-sdk)

---

## SDK Methods

### Track Event

Track a custom event for the current subscriber. Custom events are used to trigger or exit workflows based on subscriber activity in your app, such as adding items to a cart, completing a purchase, or any custom action you define.

### Identify

Associate the current subscriber with one or more user-identifying fields so campaigns and segments can be personalized using your own first-party data. Valid keys are restricted to a fixed set of subscriber fields — `first_name`, `last_name`, `email`, `phone`, `gender`, `dob`, `language`, `profile_id`, `country`, `city`, `state`, `zip` — with String, Number, or Boolean values.

### Logout

Clear identifying fields from the current subscriber while keeping the device subscribed for push. Call this when the user signs out of your app to detach their PII from the push subscription. Passing `null` or an empty list removes the default PII set (`first_name`, `last_name`, `email`, `phone`, `gender`, `dob`, `profile_id`); pass a list of field names to scope the removal.

### Set Badge Count

Control the numeric badge associated with notifications the SDK builds afterwards. Pass `0` to clear the badge; pass a positive integer to set it. The value is applied via `NotificationCompat.Builder.setNumber(count)` and surfaces in the system long-press menu — negative values are coerced to `0`.

### Set FCM Config Error Listener

Register a callback that fires when the SDK detects a mismatch between your app's local Firebase configuration and the configuration registered for your PushEngage site. Use this during integration to catch sender-ID or project-ID drift early — when a mismatch is detected at sync time, the SDK also skips the subscriber-add call so a permanently-undeliverable subscriber is not created on the server. Pass `null` to clear a previously-registered listener.

---

## Example Project

Check out the complete example app in the **`app/`** directory, demonstrating notification permissions, subscriber management, segments, and triggered campaigns.

---

## Documentation

- [Installation & Setup Guide](https://www.pushengage.com/documentation/android-push-notification-sdk-setup/) -- step-by-step setup with Firebase
- [Android SDK API Reference](https://www.pushengage.com/api/mobile-sdk/android-sdk) -- complete API docs
- [PushEngage Dashboard](https://app.pushengage.com) -- manage campaigns and analytics

---

## Requirements

| Requirement | Minimum |
|-------------|---------|
| Android | 4.1+ (API 16) |
| Target SDK | 34 |
| Firebase | Required (FCM for delivery) |
| Java | 11+ |
| Kotlin | 1.6+ |

---

## Support

Having trouble? We're here to help.

- **Issues & Bugs** -- [Open a GitHub issue](https://github.com/awesomemotive/pushengage-android-sdk/issues)
- **General Support** -- Contact us from your [PushEngage dashboard](https://app.pushengage.com) or email [care@pushengage.com](mailto:care@pushengage.com)

---

## License

MIT -- see [LICENSE](LICENSE) for details.
