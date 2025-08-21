# Changelog

## [0.0.6] - 2025-01-21

### 🎉 New Features in PushEngage.java

#### Permission Management APIs
- **`requestNotificationPermission(ComponentActivity, PushEngagePermissionCallback)`**: New method for requesting notification permissions on Android 13+ with callback support for both ComponentActivity (Compose) and FragmentActivity
- **`getNotificationPermissionStatus()`**: Returns current notification permission status as "granted" or "denied"

#### Subscription Management APIs
- **`getSubscriptionStatus(PushEngageResponseCallback)`**: Check if user is subscribed to push notifications with detailed validation logic
- **`getSubscriptionNotificationStatus(PushEngageResponseCallback)`**: Check if user can receive notifications (subscribed AND permission granted)
- **`getSubscriberId(PushEngageResponseCallback)`**: Retrieve the unique subscriber hash/ID for subscribed users
- **`unsubscribe(PushEngageResponseCallback)`**: Programmatically unsubscribe users while preserving subscription record

### 🔧 Technical Improvements in PushEngage.java

#### Enhanced Initialization Logic
- **Smart Subscription**: Modified constructor to only auto-subscribe if notification permission is granted and user is not manually unsubscribed
- **Conditional Auto-Subscribe**: Added checks for `getNotificationPermissionStatus()` and `!prefs.isManuallyUnsubscribed()` before automatic subscription

#### Improved Error Handling
- **Firebase Token Retrieval**: Enhanced `subscribe()` method with proper error logging using `PELogger.error()`
- **OnFailureListener**: Added `addOnFailureListener()` to Firebase token retrieval for comprehensive error handling
- **Callback Validation**: Added null checks and proper error messages for all new API methods

#### Enhanced Subscription Logic
- **Manual Unsubscribe Support**: Added logic to respect manual unsubscribe state throughout the subscription flow
- **Server Sync Integration**: Enhanced subscription status validation with server-side data fetching
- **State Management**: Improved local and remote subscription state synchronization

### 🐛 Bug Fixes & Improvements in PushEngage.java

#### Subscription State Validation
- **Multi-Field Validation**: Enhanced subscription status checking with `has_unsubscribed` and `notification_disabled` fields
- **Consistent State Logic**: User is considered subscribed only when both `hasUnsubscribed = 0` AND `notificationDisabled = 0`
- **Error Recovery**: Improved error handling for API failures with graceful fallbacks

#### Context and Preference Management
- **Static Context Handling**: Improved static context management for better reliability
- **Preference Validation**: Enhanced null checks and validation for preference operations
- **Thread Safety**: Better handling of concurrent operations and state changes

### 📚 New API Documentation

#### Permission Management
```java
// Request notification permission (Android 13+)
PushEngage.requestNotificationPermission(this, new PushEngagePermissionCallback() {
    @Override
    public void onPermissionResult(boolean granted, Error error) {
        if (granted) {
            // User granted permission and is now subscribed
        } else {
            // Handle permission denied
        }
    }
});

// Check current permission status
String status = PushEngage.getNotificationPermissionStatus(); // "granted" or "denied"
```

#### Subscription Management
```java
// Check subscription status
PushEngage.getSubscriptionStatus(new PushEngageResponseCallback() {
    @Override
    public void onSuccess(Object result) {
        Boolean isSubscribed = (Boolean) result;
    }
    @Override
    public void onFailure(Integer errorCode, String errorMessage) {
        // Handle error
    }
});

// Check if user can receive notifications
PushEngage.getSubscriptionNotificationStatus(callback);

// Get subscriber ID
PushEngage.getSubscriberId(callback);

// Unsubscribe user
PushEngage.unsubscribe(callback);
```

### 🔄 Migration Notes

#### Backward Compatibility
- All existing `PushEngage.java` methods remain unchanged and fully functional
- New APIs are additive and optional
- Existing initialization and subscription flows continue to work without modification

#### Enhanced Functionality
- Automatic subscription now respects permission state and manual unsubscribe preferences
- Improved error handling provides better debugging and user experience
- New APIs enable fine-grained control over subscription and permission states

---

**Full Changelog**: [View on GitHub](https://github.com/awesomemotive/pushengage-android-sdk-internal/commit/d57cd4b5cd41b9d91f1694ef0c1a4d424cabe343)
