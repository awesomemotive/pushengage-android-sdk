# Changelog
All notable changes to this project will be documented in this file and formatted via this recommendation.

## [0.0.6] - 2025-01-21

### Added
- `requestNotificationPermission()` method for requesting notification permissions with callback support
- `getNotificationPermissionStatus()` method to check current permission status ("granted"/"denied")
- `getSubscriptionStatus()` method to check if user is subscribed to push notifications
- `getSubscriptionNotificationStatus()` method to check if user can receive notifications
- `getSubscriberId()` method to get unique subscriber hash/ID
- `unsubscribe()` method to programmatically unsubscribe users
- `subscribe()` method to programmatically subscribe users to push notifications
- Enhanced initialization with conditional auto-subscription based on permission state

