# Rockstat Android SDK (modified Mixpanel Android SDK)

🇬🇧 English · [🇷🇺 Русский](README.ru.md)

> **This is a modified version of the Mixpanel Android SDK, adapted for the Rockstat analytics
> backend only. It is not meant to be used with Mixpanel's service and is not supported by Mixpanel.**
>
> It ships events, property names and session semantics that Mixpanel does not know about
> (`$screen`, `Session Started`, `$session_id`, back-dated `time` on `Application Backgrounded`,
> and so on), changes how `$ae_session` and `$mp_session_id` behave, and is configured through
> custom `EventsEndpoint` / `PeopleEndpoint` / `GroupsEndpoint` meta-data that point at Rockstat.
> Pointing it at `api.mixpanel.com` is untested and unsupported. Do not report issues from this
> fork to Mixpanel; upstream is [mixpanel/mixpanel-android](https://github.com/mixpanel/mixpanel-android).

Forked from upstream **8.12.0**. Artifact: `com.mixpanel.android:mixpanel-android:8.13.0-rockstat.1`
(same coordinates as upstream so it is a drop-in swap; published to a local or private Maven
repository, never to Maven Central).

Sample app that exercises every feature below:
[rockstat-android-mixpanel-integration](https://github.com/rockstat/rockstat-android-mixpanel-integration)
(this fork lives there in the `sdk/` directory as a git subtree).

## What this fork changes

### 1. `$ae_session` survives process death

Upstream emits `$ae_session` from a Runnable posted with a 500 ms delay after the last Activity
pauses. If the process is killed before that (swipe from Recents, low memory, crash) the session is
never reported. The fork's `MixpanelActivityLifecycleCallbacks` persists the current foreground
stint (`start`, `last_activity`, `in_foreground`) on every resume and pause and, on the next launch,
back-fills an unfinished stint as `$ae_session` with `time` set to the last activity. People
counters `$ae_total_app_sessions` / `$ae_total_app_session_length` are incremented as usual.

### 2. `$mp_session_id` keeps across short backgrounds and restarts

Upstream regenerates the `$mp_metadata` session on every foreground. The fork keeps it while the
gap since the last activity is shorter than the new manifest key

```xml
<meta-data android:name="com.mixpanel.android.MPConfig.SessionIdleTimeout" android:value="1800000" />
```

(30 minutes by default; `0` restores upstream behaviour). The session id, start time and sequence
counters are persisted, so a process restart inside the window continues the same session.
`SessionMetadata` is now thread-safe.

### 3. Opt-in automatic events (`AutoTrackOptions`)

```java
MixpanelOptions options = new MixpanelOptions.Builder()
        .autoTrackOptions(new AutoTrackOptions.Builder()   // all groups on by default
                .systemEvents(true)
                .anrTimeoutMs(5000)
                .sessionIdleTimeoutMs(30 * 60 * 1000)
                .build())
        .build();
MixpanelAPI mp = MixpanelAPI.getInstance(context, TOKEN, trackAutomaticEvents, options);
```

| Group (builder switch) | Events | Key properties |
| --- | --- | --- |
| `lifecycleEvents` | `Application Installed`, `Application Updated`, `Application Opened`, `Application Backgrounded` | `version`, `build`, `from_background`, `session_length_s`, `previous_version`, `previous_build`; a kill in the foreground is back-filled with `reason=process_killed` and a past `time`. An app that adopts the SDK after it was already installed gets `Application Updated` (without `previous_*`) on first run, not `Application Installed` |
| `sessionEvents` | `Session Started`, `Session Ended` | `reason` (`first_launch`, `timeout`, `reset`), `session_length_s`; `Session Ended` is back-dated to the last activity when the timeout is noticed later. Super properties `$session_id`, `$session_start` on every event |
| `screenEvents` | `$screen`, `Screen Left` | `$screen_name` (the Activity's own `android:label`, else its class name), `$screen_class`, `duration_s`; deduplicated per Activity class |
| `deepLinkEvents` | `Deep Link Opened` | `url`, `referrer` |
| `systemEvents` | `OS Updated`, `Memory Warning`, `Configuration Changed`, `Network Status Changed`, `Time Zone Changed`, `Battery Low`, `Battery Okay` | `previous_os_version`/`os_version`/`security_patch`; `level_name`/`available_mb`/`heap_used_mb`; `changed[]`/`orientation`/`dark_mode`/`locale`/`font_scale`; `online`/`transport`/`previous_transport`/`metered`/`validated`; `previous_timezone`/`timezone`; `battery_percent`/`charging`. `Network Status Changed` needs `ACCESS_NETWORK_STATE` in the app manifest, otherwise it is skipped |
| `anrTimeoutMs` (0 = off) | `ANR Detected` | `blocked_ms` (uptime clock), `main_thread_stack` (top 12 frames); confirmed by a second check, skipped while a debugger is attached |
| `installReferrerEvent` | `Install Attributed` | `referrer`, `referrer_click_ts`, `install_begin_ts`, `google_play_instant`, `install_version`; requires `com.android.installreferrer:installreferrer` on the app classpath (the SDK only `compileOnly`-depends on it and checks for the class at runtime); sent only for a fresh install, never for an existing install that adopted the SDK |

These events are ordinary `track()` calls: they are not gated by the `trackAutomaticEvents` flag,
carry all super properties, and go to whatever `EventsEndpoint` is configured. `MixpanelAPI.reset()`
ends the current automatic session under the old identity and starts a new one under the new one.
`optOutTracking()` stops the system observers and the ANR watchdog; `optInTracking()` restarts
them and re-registers `$session_id` / `$session_start`. `MixpanelAPI.getAutoTrackSessionId()`
returns the current `$session_id` (or `null` when session events are off).

Everything is package-private except `AutoTrackOptions` and `getAutoTrackSessionId()`. Internals
and persistence files are described in [context/AUTOTRACK.md](context/AUTOTRACK.md); the session
state machine (`AutoTrackSession`) is pure Java and covered by unit tests in
`analytics/src/test`.

### 4. Everything else is upstream

Feature flags, autocapture (`$mp_click`, rage/dead clicks), People, Groups, session replay bridge
and the `$ae_*` events are unchanged from 8.12.0. See [analytics/CHANGELOG.md](analytics/CHANGELOG.md).

## Building

Requires JDK 17 and Android SDK 34 (see `CLAUDE.md` for the full command list).

```sh
./gradlew :analytics:publishToMavenLocal   # ~/.m2/.../mixpanel-android/8.13.0-rockstat.1
./gradlew :analytics:test                  # JVM unit tests (AutoTrackSessionTest and upstream's)
./gradlew :analytics:connectedAndroidTest  # instrumented tests, needs a device
```

Consumers add `mavenLocal()` (or your private repository) and depend on
`com.mixpanel.android:mixpanel-android:8.13.0-rockstat.1`.

## Keeping up with upstream

```sh
git remote add upstream https://github.com/mixpanel/mixpanel-android.git
git fetch upstream
git rebase upstream/master   # fork changes are the commits on top of upstream
```

Files touched by the fork: `MixpanelAPI.java`, `MixpanelOptions.java`, `MPConfig.java`,
`SessionMetadata.java`, `MixpanelActivityLifecycleCallbacks.java`, the new `AutoTrack*.java`,
`SystemEventTracker.java`, `AnrWatchdog.java`, `InstallReferrerTracker.java`, `analytics/build.gradle`,
`analytics/gradle.properties`, `analytics/CHANGELOG.md`, `context/AUTOTRACK.md`.

## Modules

| Module | Artifact | Notes |
| --- | --- | --- |
| [analytics](analytics/README.md) | `com.mixpanel.android:mixpanel-android` | the fork's changes live here |
| [common](common/README.md) | `com.mixpanel.android:mixpanel-android-common` | unchanged, consumed from Maven Central |
| [openfeature-provider](openfeature-provider/README.md) | `com.mixpanel.android:mixpanel-android-openfeature` | unchanged |

## License

Apache 2.0 — see [LICENSE](LICENSE). Mixpanel is a trademark of Mixpanel, Inc.; this fork is not
affiliated with or endorsed by Mixpanel.
