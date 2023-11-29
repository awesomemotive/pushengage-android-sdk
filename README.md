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
 - Handle runtime notification permission:
   In your desired activity check for notification permission.
   This code can also be found in the MainActivity of the sample project.
   ``` java
       @Override
       protected void onCreate(Bundle savedInstanceState) {
           super.onCreate(savedInstanceState);
           setContentView(R.layout.activity_main);

           //For Android 13 and above, check for notification permission
           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
               requestNotificationPermissionIfNeeded();
           }
       }
      
       @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
       private void requestNotificationPermissionIfNeeded() {
           int permissionState = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS);
           // If the permission is not granted, request it.
           if (permissionState == PackageManager.PERMISSION_DENIED) {
               ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
           }
       }

       @Override
       public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
           super.onRequestPermissionsResult(requestCode, permissions, grantResults);
           if(requestCode == 100) {
               // Check if the user granted the permission.
               if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                   PushEngage.subscribe();
               } else {
                   Log.d("MainActivity", "onRequestPermissionsResult: Permission denied");
               }
           }
       }
   ```

### Support

For issues and queries please contact PushEngage support from your PushEngage account or email us at <care@pushengage.com>
