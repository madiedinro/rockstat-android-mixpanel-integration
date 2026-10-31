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

## Automatic events and sessions (`AutoTracker.java`)

`AutoTracker` is installed from `DebugApplication` and mirrors PostHog's Android autocapture on top
of the Mixpanel SDK:

| Event                      | When                                   | Properties                                   |
|----------------------------|----------------------------------------|----------------------------------------------|
| `Application Installed`    | first launch ever                      | `version`, `build`                           |
| `Application Updated`      | first launch of a new `versionCode`    | `previous_version`, `previous_build`, ...    |
| `Application Opened`       | app enters foreground (process level)  | `from_background`, `version`, `build`        |
| `Application Backgrounded` | app leaves foreground (process level)  | `session_length_s`, `version`, `build`       |
| `$screen`                  | every Activity resume (deduplicated)   | `$screen_name`, `$screen_class`              |
| `Deep Link Opened`         | Activity created with an intent URI    | `url`, `referrer`                            |

Every event also carries the super properties `$session_id` and `$session_start`.

### Why the SDK's own session breaks when the app is closed

The SDK's `$ae_session` is produced by `MixpanelActivityLifecycleCallbacks`: when the last Activity
pauses it posts a Runnable with a 500 ms delay, and only that Runnable tracks `$ae_session` and
triggers the background flush. `$mp_session_id` (the `$mp_metadata` session) is regenerated on
every foreground, with no inactivity timeout. Consequences:

* Swipe the app away from Recents, or let the OS kill it, and the Runnable never runs:
  the session has no end event at all.
* Even when `$ae_session` is tracked, the flush is asynchronous; a kill right after leaves it in
  SQLite until the next launch.
* Any trip to another app, however short, starts a new `$mp_session_id` and resets the session
  timer, so one user session shows up as several.
* Sessions shorter than `MinimumSessionDuration` (3 s in this manifest, 10 s by default) are
  dropped silently.

`AutoTracker` works around all four without touching the SDK:

* The session is a UUID in `SharedPreferences`, persisted on every resume/pause/foreground/
  background, and continued across backgrounds and process restarts while the gap is under
  `SESSION_TIMEOUT_MS` (30 min, like PostHog).
* Foreground/background come from `ProcessLifecycleOwner`, not from individual Activities.
* `flush()` is called immediately in `onStop`, before the SDK's 500 ms delay.
* If the process died while foregrounded, the next launch back-fills `Application Backgrounded`
  with the last persisted timestamp (the SDK lets caller properties override `time`), so the
  previous session still ends on the server side.

`MixpanelAPI.reset()` wipes super properties, so `MainActivity` calls
`AutoTracker.onIdentityReset()` afterwards to start a fresh session.

If you prefer Mixpanel's native screen events, `mixpanel.getAutocapture().trackScreenView(name)`
sends `$mp_page_view` instead of `$screen`.

## Configuration

* **Project token**: `MainActivity.MIXPANEL_API_TOKEN`.
* **Everything else** is `<meta-data>` in `AndroidManifest.xml`: debug logging, a 5 s flush
  interval, a bulk-upload limit of 5 records, `$app_open` enabled, and custom
  `EventsEndpoint` / `PeopleEndpoint` / `GroupsEndpoint` pointing at a proxy. Uncomment
  `FlagsEndpoint` to proxy feature flags too. Supported keys are listed in the SDK's
  [`MPConfig.java`](https://github.com/mixpanel/mixpanel-android/blob/master/analytics/src/main/java/com/mixpanel/android/mpmetrics/MPConfig.java).

  Keep `UseIpAddressForGeolocation` unset when you use custom endpoints: when the key is present
  the SDK appends `?ip=0|1` to the URLs, when it is absent the URLs are used verbatim.

## Building

Requirements: JDK 17, Android SDK platform 35 and build-tools 35.0.0 (Android Studio installs
these for you).

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # or your JDK 17 path
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/sample-android-mixpanel-integration-debug.apk
```

Or open the directory in Android Studio and run the `sample-android-mixpanel-integration`
configuration.

`local.properties` must point `sdk.dir` at your Android SDK.

## Watching traffic

The in-app log panel shows lines with the `MixpanelAPI.*` tags. The same output is available with:

```sh
adb logcat -v time | grep MixpanelAPI
```

## History

Earlier versions of this sample targeted SDK 5.x and demonstrated Mixpanel push notifications,
in-app messages and the `decide` endpoint. Those features were removed from the SDK in 6.0 / 7.0,
so the Firebase dependency, `google-services.json`, `TakeoverInAppActivity` and
`MixpanelFCMMessagingService` are gone from this project, and the old unsupported
`SessionManager.java` example was replaced by `AutoTracker.java`.
