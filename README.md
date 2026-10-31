# Mixpanel Android SDK debug sample

A minimal Android app for poking at the
[Mixpanel Android SDK](https://github.com/mixpanel/mixpanel-android) (currently **8.12.0**).
Every button is one SDK call, the status panel shows what the SDK resolved from the manifest
(version, `distinct_id`, endpoints, flush settings), and the bottom panel mirrors the SDK's logcat
output so you can watch requests leave the device without attaching a debugger.

## What you can do from the UI

| Section       | Buttons                                   | SDK calls                                                   |
|---------------|-------------------------------------------|-------------------------------------------------------------|
| Events        | Track event, Start/Stop timed event, Flush | `track()`, `timeEvent()`, `flush()`                          |
| Identity      | Identify, Reset, Opt out / Opt in          | `identify()`, `reset()`, `optOutTracking()` / `optInTracking()` |
| People        | Update profile, Track charge               | `People.set()`, `People.increment()`, `People.trackCharge()`, `registerSuperProperties()` |
| Feature flags | Load flags                                 | `getFlags().loadFlags()`, `getFlags().getAllVariantsSync()`  |

Event properties are entered as a JSON object; a `counter` property is appended automatically.
The SDK is initialised in `DebugApplication.java`, where the automatic-event and autocapture flags
and the session timeout live.

## SDK fork in `./sdk`

The app depends on `com.mixpanel.android:mixpanel-android:8.13.0-rockstat.1`, a fork of the
upstream SDK (8.12.0) that lives in `./sdk` (a `git subtree` of
[mixpanel/mixpanel-android](https://github.com/mixpanel/mixpanel-android) with the fork commits on
top; see `sdk/README.md`) and is published to the local Maven repository. Build order:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
(cd sdk && ./gradlew :analytics:publishToMavenLocal)   # builds the fork into ~/.m2
./gradlew assembleDebug                                 # builds the app against it
```

To pull upstream SDK changes into the subtree:

```sh
git subtree pull --prefix=sdk https://github.com/mixpanel/mixpanel-android.git master --squash
```

What the fork changes, in `sdk/README.md`, `sdk/analytics/CHANGELOG.md` and `sdk/context/AUTOTRACK.md`:

* **`$ae_session` survives process death.** Upstream emits it from a 500 ms delayed Runnable after
  the last Activity pauses; a process killed before that (swipe from Recents, OOM, crash) never
  sends it. The fork persists the current foreground stint and back-fills an unfinished one on the
  next launch with its last known timestamp.
* **`$mp_session_id` keeps across short backgrounds.** Upstream regenerates it on every
  foreground. The fork keeps it (and persists it) while the gap is under
  `com.mixpanel.android.MPConfig.SessionIdleTimeout` (30 min default, 0 = old behaviour).
* **Opt-in automatic events** via `MixpanelOptions.Builder.autoTrackOptions(AutoTrackOptions)`,
  switched on in `DebugApplication.java` with every group enabled.

## Automatic events (`AutoTrackOptions`)

| Group | Events | Properties |
|-------|--------|------------|
| lifecycle | `Application Installed`, `Application Updated`, `Application Opened`, `Application Backgrounded` | `version`, `build`, `from_background`, `session_length_s`, `previous_version` |
| sessions | `Session Started`, `Session Ended` (back-dated on timeout / process death) | `reason`, `session_length_s`; super properties `$session_id`, `$session_start` |
| screens | `$screen`, `Screen Left` | `$screen_name`, `$screen_class`, `duration_s` |
| deep links | `Deep Link Opened` | `url`, `referrer` |
| system | `OS Updated`, `Memory Warning`, `Configuration Changed`, `Network Status Changed`, `Time Zone Changed`, `Battery Low` / `Battery Okay` | see `SystemEventTracker.java` in the fork |
| ANR | `ANR Detected` (main thread blocked > `anrTimeoutMs`, 5 s default) | `blocked_ms`, `main_thread_stack` |
| install referrer | `Install Attributed` (needs `com.android.installreferrer:installreferrer`, which this app ships) | `referrer`, `referrer_click_ts`, `install_begin_ts` |

Also from the SDK itself: `$ae_first_open`, `$ae_updated`, `$ae_session`, `$ae_crashed`
(`TRACK_AUTOMATIC_EVENTS`) and `$mp_click` / rage / dead clicks (`ENABLE_AUTOCAPTURE`).
`MixpanelAPI.getAutoTrackSessionId()` returns the current `$session_id`, shown in the status panel.

### Not collected, and why

* **App uninstall / app data cleared** - only detectable server side.
* **Push notification received / opened** - needs FCM, which this sample no longer bundles.
* **Fragment screens** - the app has no fragments; register
  `FragmentManager.FragmentLifecycleCallbacks` in `onActivityCreated` if yours does.
* **Network requests** - needs an OkHttp interceptor; the sample makes no HTTP calls of its own.
* **In-app purchase** - needs Play Billing.

## History

Earlier versions of this sample targeted SDK 5.x and demonstrated Mixpanel push notifications,
in-app messages and the `decide` endpoint. Those features were removed from the SDK in 6.0 / 7.0,
so the Firebase dependency, `google-services.json`, `TakeoverInAppActivity` and
`MixpanelFCMMessagingService` are gone from this project. The old unsupported `SessionManager.java`
example was first replaced by an in-app `AutoTracker`, which then moved into the SDK fork.
