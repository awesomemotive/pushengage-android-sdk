# PushEngageSDK Unit Test Plan

## Overview

This document tracks the current automated unit-test strategy and execution status for the PushEngage Android SDK.

**Current status (latest run):**
- **348 tests across 32 test suites**
- **321 passing**
- **0 failing**
- **0 skipped**

This includes previously uncovered core areas (`PushEngage`, `PEServiceHandler`, workers, receiver, permission flow, notification manager, FCM service), plus a production fix for the image-loader double-resume race.

## Test Dependencies

Configured in `PushEngageSDK/build.gradle`:

```groovy
testImplementation "junit:junit:4.13.2"
testImplementation "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.3.9"
testImplementation "org.mockito:mockito-inline:4.11.0"
testImplementation "org.mockito.kotlin:mockito-kotlin:4.1.0"
testImplementation "org.robolectric:robolectric:4.11.1"
testImplementation "androidx.test:core:1.5.0"
testImplementation "com.google.code.gson:gson:2.10.1"
testImplementation "com.squareup.okhttp3:mockwebserver:4.8.0"
testImplementation "org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion"
```

Robolectric resources are enabled via:

```groovy
testOptions {
    unitTests {
        includeAndroidResources = true
    }
}
```

## Running Tests

Run full SDK unit suite:

```bash
./gradlew :PushEngageSDK:testDebugUnitTest
```

Run a focused suite:

```bash
./gradlew :PushEngageSDK:testDebugUnitTest --tests "com.pushengage.pushengage.servicehandling.PEServiceHandlerTest"
```

## Coverage Summary by Area

### Core Lifecycle and Orchestration
- `PushEngageCoreSafetyTest` (7): static lifecycle safety, subscription guard behavior, Firebase token call-gating
- `PENotificationManagerTest` (4): notification enable/disable transitions, manual unsubscribe guard, add-subscriber vs status-update routing
- `PEPermissionFragmentTest` (3): null-safety and pre-Android 13 auto-grant + subscribe path
- `PEFirebaseMessagingServiceTest` (2): malformed payload resilience, token-upgrade failure no-crash path

### Service/Network Layer
- `PEServiceHandlerTest` (6): channel fetch success/failure, update subscriber status success/failure, sponsored-notification retry behavior
- `RestClientUrlTest` (18): production/staging/custom URL routing and fallback behavior

### Background and Connectivity
- `DailySyncDataWorkerTest` (3): state transition behavior and call routing
- `WeeklySyncDataWorkerTest` (3): network-gated sync, active/inactive site handling
- `NetworkChangeReceiverTest` (10): online/offline behavior, subscribe trigger conditions, click replay once per row (bursts, secondary process, renamed main process, same-tag rows), replay failure reported not fatal

### Notification Pipeline
- `PENotificationBuilderTest` (32): notification content, actions, intent wiring (each notification keeps its own button intents), style config
- `PENotificationBuilderCrashTest` (22): crash safety for malformed/edge payload values
- `PENotificationChannelHelperTest` (13): channel lookup/configuration/visibility behavior
- `PENotificationChannelHelperCrashTest` (16): channel crash paths (LED/vibration/sound malformed data)
- `PENotificationImageLoaderDoubleResumeTest` (5): callback-order race regression (now passing)
- `PENotificationImageLoaderScenariosTest` (9): image-loading edge paths and completion behavior
- `PENotificationHandlerActivityTest` (9): activity click handling and URL/intent edge paths
- `NotificationServiceCrashTest` (12): service crash safety and retry/offline paths
- `NotificationServiceClickTrackingTest` (10): click tracking online/offline — body taps without an action, missing tag, failed Room insert, error-log reporting, service lifetime
- `NotificationServiceManifestTest` (1): the click-tracking service is not exported

### Manager and Validation Layer
- `PEManagerValidationTest` (10): trigger/goal validation and callback safety
- `PEManagerAutomatedNotificationTest` (8): automated-notification validation and null edge behavior
- `PEManagerAddAlertTest` (19): alert payload composition and validation paths

### Model and Utility Contracts
- `FCMPayloadModelSerializationTest` (9)
- `FCMPayloadEdgeCaseTest` (14)
- `RequestModelSerializationTest` (9)
- `ResponseModelSerializationTest` (12)
- `PEConstantsTest` (11)
- `PEEnumsTest` (15)
- `PELoggerTest` (8)
- `PEPrefsTest` (29)
- `PEUtilitiesTest` (11)
- `DatabaseEntityTest` (8)

## Current Test Suite Inventory

| Suite | Tests |
|------|------:|
| `DataWorker.DailySyncDataWorkerTest` | 3 |
| `DataWorker.WeeklySyncDataWorkerTest` | 3 |
| `Database.DatabaseEntityTest` | 8 |
| `PEManagerAddAlertTest` | 19 |
| `PEManagerAutomatedNotificationTest` | 8 |
| `PEManagerValidationTest` | 10 |
| `PushEngageCoreSafetyTest` | 7 |
| `Receiver.NetworkChangeReceiverTest` | 10 |
| `RestClient.RestClientUrlTest` | 18 |
| `Service.NotificationServiceCrashTest` | 12 |
| `Service.NotificationServiceClickTrackingTest` | 10 |
| `Service.NotificationServiceManifestTest` | 1 |
| `core.PEFirebaseMessagingServiceTest` | 2 |
| `core.PENotificationManagerTest` | 4 |
| `helper.PEConstantsTest` | 11 |
| `helper.PELoggerTest` | 8 |
| `helper.PEPrefsTest` | 29 |
| `helper.PEUtilitiesTest` | 11 |
| `model.PEEnumsTest` | 15 |
| `model.payload.FCMPayloadEdgeCaseTest` | 14 |
| `model.payload.FCMPayloadModelSerializationTest` | 9 |
| `model.request.RequestModelSerializationTest` | 9 |
| `model.response.ResponseModelSerializationTest` | 12 |
| `notificationchannel.PENotificationChannelHelperCrashTest` | 16 |
| `notificationchannel.PENotificationChannelHelperTest` | 13 |
| `notificationhandling.PENotificationBuilderCrashTest` | 22 |
| `notificationhandling.PENotificationBuilderTest` | 32 |
| `notificationhandling.PENotificationHandlerActivityTest` | 9 |
| `notificationhandling.PENotificationImageLoaderDoubleResumeTest` | 5 |
| `notificationhandling.PENotificationImageLoaderScenariosTest` | 9 |
| `permissionhandling.PEPermissionFragmentTest` | 3 |
| `servicehandling.PEServiceHandlerTest` | 6 |
| **TOTAL** | **348** |

## Key Bugs Covered

1. **Image loader double-resume race** in `PENotificationImageLoader.loadImageAsync()` (fixed and regression-tested)
2. **PushEngage static lifecycle safety** (null context/prefs and callback behavior)
3. **Permission flow null-safety + auto-grant behavior** in `PEPermissionFragment`
4. **Service-handler retry paths** for sponsored notifications and view tracking call flow
5. **Worker state-transition correctness** for notification-enabled/disabled sync updates

## Remaining High-Priority Gaps

These are still recommended next additions for deeper race-condition confidence:

1. **Deterministic timer execution tests** for all retry `Timer` branches (without relying on constructor interception)
2. **Concurrent multi-message stress tests** for `PEFirebaseMessagingService.onMessageReceived`
3. **Concurrent subscribe/unsubscribe interleaving tests** at `PushEngage` API level
4. **Receiver thread synchronization tests** for DB replay under rapid online/offline toggles
5. **Instrumentation-level permission+notification E2E tests** for Android 13+ runtime permission UX paths
