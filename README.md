<img src="https://assetscdn.pushengage.com/site_assets/img/pushengage-logo.png" width="240px"/>

## PushEngage Android SDK
[![](https://jitpack.io/v/awesomemotive/pushengage-android-sdk.svg)](https://jitpack.io/#awesomemotive/pushengage-android-sdk)

PushEngage Android SDK allows you to integrate Push Notification service into your Android application. 

### Installation

#### Android Studio ( or Gradle )

1. If your project is not using [centralized repository declaration](https://docs.gradle.org/current/userguide/declaring_repositories.html#sub:centralized-repository-declaration), add the following lines to your root `build.gradle`:
   
```groovy
// Project level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
   repositories {
       google()
       jcenter()
   }
   dependencies {
       // ...
       // Add this line
       classpath "com.google.gms:google-services:4.3.10" 
       
   }
}

allprojects {
   repositories {
      // ...
      // Check that you have the following line (if not, add it):
      google()
      maven {url 'https://jitpack.io' }
   }
}
```
If your project is using [centralized repository declaration](https://docs.gradle.org/current/userguide/declaring_repositories.html#sub:centralized-repository-declaration), add the following lines to your `settings.gradle` file:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        //add this line
        maven { url 'https://jitpack.io' }
    }
}
```
   
2. Add the following lines to your application's `build.gradle`
   
```groovy
plugins {
   id 'com.android.application'
   // Add this line
   id 'com.google.gms.google-services'
}

dependencies {
   // ...
   // Add the following lines
   implementation 'com.github.awesomemotive:pushengage-android-sdk:<latestVersion>'
   implementation platform('com.google.firebase:firebase-bom:26.1.1')
}
```

For details step by step guide, Please follow the instructions mentioned in the [Installation Guide Documentation](https://www.pushengage.com/documentation/android-push-notification-sdk-setup/)
### Firebase Setup

Follow the instructions as mentioned in the [Firebase Setup Guide](https://www.pushengage.com/documentation/android-push-notification-sdk-setup/)

### PushEngage Android SDK public APIs

Follow the instructions as mentioned in the [PushEngage Android Public APIs Documentation](https://www.pushengage.com/documentation/android-push-notification-sdk-setup/)

### Language and Version Support

 - Our SDK supports **Java** as well as **Kotlin** which are both native Android Application Languages.
 - SDK supports from Android 4.1 and above.

### Android 13 notification permission

The PushEngage SDK provides an easy-to-use method for requesting notification permissions. The SDK automatically handles the permission request for Android 13+ and calls `PushEngage.subscribe()` when permission is granted.

#### Recommended Approach (Using SDK Method)
Use the built-in SDK method to request notification permission. This approach is demonstrated in the sample app:

```java
/**
 * Request notification permission using the PushEngage SDK
 * SDK automatically calls subscribe when permission is granted
 */
private void requestNotificationPermissionUsingSDK() {
    PushEngage.requestNotificationPermission(this, new PushEngagePermissionCallback() {
        @Override
        public void onPermissionResult(boolean granted, Error error) {
            if (granted) {
                // Permission granted - SDK automatically calls subscribe
                Log.d("MainActivity", "Notification permission granted");
                Toast.makeText(MainActivity.this, "Permission granted and subscribed!", Toast.LENGTH_SHORT).show();
            } else {
                // Permission denied, handle accordingly
                Log.d("MainActivity", "Notification permission denied");
                Toast.makeText(MainActivity.this, "Permission denied!", Toast.LENGTH_SHORT).show();
                if (error != null) {
                    Log.e("MainActivity", "Permission error: " + error.getMessage());
                }
            }
        }
    });
}
```

#### Check Permission Status
You can also check the current notification permission status:

```java
String permissionStatus = PushEngage.getNotificationPermissionStatus();
switch (permissionStatus) {
    case "granted":
        Log.d("Permission", "Notifications are allowed");
        break;
    case "denied":
        Log.d("Permission", "Notifications are denied");
        break;
    default:
        Log.d("Permission", "Unknown permission status");
        break;
}
```

**Note:** The SDK handles all the complexity of permission requests across different Android versions and automatically subscribes the user when permission is granted, making manual permission handling unnecessary.

### Support

For issues and queries please contact PushEngage support from your PushEngage account or email us at <care@pushengage.com>
