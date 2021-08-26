<img src="https://assetscdn.pushengage.com/site_assets/img/pushengage-logo.png" width="240px"/>

## PushEngage Android SDK
[![](https://jitpack.io/v/awesomemotive/pushengage-android-sdk.svg)](https://jitpack.io/#awesomemotive/pushengage-android-sdk)

PushEngage Android SDK allows you to integrate Push Notification service into your android app. 

### Installation.

#### Android Studio ( or Gradle )
To add PushEngage Android SDK in your android app:

1. Add the following line in your root `build.gradle`
   
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
   
2. Add the following line in your app's `build.gradle`
   
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
### Firebase Setup.

Follow the instructions as mentioned in the [Firebase Setup Guide](https://www.pushengage.com/documentation/android-push-notification-sdk-setup/)

### Android SDK public APIs.

Follow the instructions as mentioned in the [PushEngage Android Public APIs Documentation](https://www.pushengage.com/documentation/android-push-notification-sdk-setup/)

### Language and Version Support

 - Our SDK supports **Java** as well as **Kotlin** both native Android Application Language.
 - SDK supports from Android 4.1 and above.


### Support

For issues and queries please contact PushEngage support from your PushEngage account or email us at <care@pushengage.com>
