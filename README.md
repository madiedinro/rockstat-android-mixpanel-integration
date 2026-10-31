# Rockstat Android SDK sample (modified Mixpanel SDK)

🇬🇧 English · [🇷🇺 Русский](README.ru.md)

> **This repository contains a modified version of the Mixpanel Android SDK, adapted for the
> Rockstat analytics backend.** It is not an official Mixpanel project, it is not supported by
> Mixpanel, and it is not meant to send data to Mixpanel's service. The SDK fork lives in
> [`sdk/`](sdk/README.md); the Android app in the repository root is a debug playground for it.

## Layout

| Path | What it is |
| --- | --- |
| `/` (root) | Debug app: one button per SDK call, a status panel with the resolved configuration, and a live mirror of the SDK's logcat output. |
| [`sdk/`](sdk/README.md) | Fork of [mixpanel/mixpanel-android](https://github.com/mixpanel/mixpanel-android) 8.12.0, added as a `git subtree`. Published as `com.mixpanel.android:mixpanel-android:8.13.0-rockstat.1` (same coordinates as upstream, drop-in swap). |

## What the fork changes

Full details: [`sdk/README.md`](sdk/README.md), [`sdk/analytics/CHANGELOG.md`](sdk/analytics/CHANGELOG.md),
[`sdk/context/AUTOTRACK.md`](sdk/context/AUTOTRACK.md).

1. **`$ae_session` survives process death.** Upstream emits it from a Runnable posted 500 ms after
   the last Activity pauses; a process killed before that (swipe from Recents, OOM, crash) never
   sends it. The fork persists the current foreground stint and back-fills an unfinished one on the
   next launch with its last known timestamp.
2. **`$mp_session_id` keeps across short backgrounds and restarts.** Upstream regenerates it on
   every foreground. The fork keeps and persists it while the gap is under the new manifest key
   `com.mixpanel.android.MPConfig.SessionIdleTimeout` (30 min by default, `0` = upstream behaviour).
3. **Opt-in automatic events** via `MixpanelOptions.Builder.autoTrackOptions(AutoTrackOptions)`,
   modelled on PostHog / Amplitude / Firebase / Segment autocapture (table below). The app enables
   every group in `DebugApplication.java`.

### Automatic events (`AutoTrackOptions`)

| Group | Events | Key properties |
| --- | --- | --- |
| lifecycle | `Application Installed`, `Application Updated`, `Application Opened`, `Application Backgrounded` | `version`, `build`, `from_background`, `session_length_s`, `previous_version`, `previous_build`; a kill in the foreground is back-filled with `reason=process_killed` |
| sessions | `Session Started`, `Session Ended` (back-dated on timeout / process death) | `reason`, `session_length_s`; super properties `$session_id`, `$session_start` on every event |
| screens | `$screen`, `Screen Left` | `$screen_name`, `$screen_class`, `duration_s` |
| deep links | `Deep Link Opened` | `url`, `referrer` |
| system | `OS Updated`, `Memory Warning`, `Configuration Changed`, `Network Status Changed`, `Time Zone Changed`, `Battery Low` / `Battery Okay` | OS version and patch, memory levels, orientation / dark mode / locale / font scale, transport and metered flag, time zone, battery percent |
| ANR | `ANR Detected` (main thread blocked > 5 s by default) | `blocked_ms`, `main_thread_stack` |
| install referrer | `Install Attributed` (needs `com.android.installreferrer:installreferrer`, which this app ships) | `referrer`, `referrer_click_ts`, `install_begin_ts` |

Also from the SDK itself: `$ae_first_open`, `$ae_updated`, `$ae_session`, `$ae_crashed`
(`TRACK_AUTOMATIC_EVENTS`) and `$mp_click` / rage / dead clicks (`ENABLE_AUTOCAPTURE`).
`MixpanelAPI.getAutoTrackSessionId()` returns the current `$session_id`, shown in the status panel.

Not collected, and why: app uninstall / data cleared (server side only), push notifications (no
FCM in this sample), Fragment screens (no fragments here; hook `FragmentLifecycleCallbacks` if you
have them), network requests (needs an OkHttp interceptor), in-app purchases (needs Play Billing).

## The debug app

| Section | Buttons | SDK calls |
| --- | --- | --- |
| Events | Track event, Start/Stop timed event, Flush | `track()`, `timeEvent()`, `flush()` |
| Identity | Identify, Reset, Opt out / Opt in | `identify()`, `reset()`, `optOutTracking()` / `optInTracking()` |
| People | Update profile, Track charge | `People.set()`, `People.increment()`, `People.trackCharge()`, `registerSuperProperties()` |
| Feature flags | Load flags | `getFlags().loadFlags()`, `getFlags().getAllVariantsSync()` |

Event properties are entered as a JSON object; a `counter` property is appended automatically.
The bottom panel shows every logcat line with the `MixpanelAPI.*` tags, so requests, responses and
automatic events are visible without a debugger.

### Configuration

* **Project token**: `DebugApplication.MIXPANEL_API_TOKEN`.
* **Everything else** is `<meta-data>` in `AndroidManifest.xml`: debug logging, a 5 s flush
  interval, a bulk-upload limit of 5 records, `$app_open` enabled, and the Rockstat ingestion
  endpoints in `EventsEndpoint` / `PeopleEndpoint` / `GroupsEndpoint` (uncomment `FlagsEndpoint` to
  proxy feature flags too). Keep `UseIpAddressForGeolocation` unset: when the key is present the
  SDK appends `?ip=0|1` to custom endpoints, when absent they are used verbatim.

## Building

Requirements: JDK 17, Android SDK platform 35 and build-tools 35.0.0 (the SDK fork itself targets 34).

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # or your JDK 17 path
(cd sdk && ./gradlew :analytics:publishToMavenLocal)   # 1. build the fork into ~/.m2
./gradlew assembleDebug                                 # 2. build the app against it
adb install -r build/outputs/apk/debug/sample-android-mixpanel-integration-debug.apk
```

`local.properties` must point `sdk.dir` at your Android SDK.

To pull upstream Mixpanel changes into the fork:

```sh
git subtree pull --prefix=sdk https://github.com/mixpanel/mixpanel-android.git master --squash
```

## History

This repository started as Mixpanel's `sample-android-mixpanel-integration` for SDK 5.x
(push notifications, in-app messages, the `decide` endpoint). Those features left the SDK in 6.0 /
7.0, so Firebase, `TakeoverInAppActivity` and `MixpanelFCMMessagingService` are gone
(`google-services.json` is no longer used and is git-ignored). The sample was then upgraded to SDK 8.12.0, turned into a debug playground, and finally
paired with the Rockstat fork of the SDK in `sdk/`.

## License

Apache 2.0, same as upstream. Mixpanel is a trademark of Mixpanel, Inc.; this project is not
affiliated with or endorsed by Mixpanel.
