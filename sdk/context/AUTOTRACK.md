# Automatic events (`AutoTrackOptions`)

Opt-in via `MixpanelOptions.Builder.autoTrackOptions(...)`. Everything is package-private in
`com.mixpanel.android.mpmetrics` except `AutoTrackOptions` and `MixpanelAPI.getAutoTrackSessionId()`.

| Class | Responsibility |
|-------|----------------|
| `AutoTrackManager` | Installs the trackers below for one `MixpanelAPI` instance; every step isolated by try/catch. |
| `AutoTracker` | `ActivityLifecycleCallbacks` + `ProcessLifecycleOwner` observer: lifecycle, `$screen`/`Screen Left`, `Deep Link Opened`, and the `AutoTrackSession` listener that registers `$session_id`/`$session_start` and emits `Session Started/Ended`. |
| `AutoTrackSession` | Pure-Java session state machine (injected clock values and `Store`), unit-tested in `src/test`. Resume within idle timeout, back-dated `Session Ended` on timeout, `onUnfinishedForeground` after process death. |
| `SystemEventTracker` | `ComponentCallbacks2`, `ConnectivityManager` callback and system broadcasts: `OS Updated`, `Memory Warning`, `Configuration Changed`, `Network Status Changed`, `Time Zone Changed`, `Battery Low/Okay`. |
| `AnrWatchdog` | HandlerThread posting a tick to the main looper; `ANR Detected` with `blocked_ms` and the main thread stack. |
| `InstallReferrerTracker` | `Install Attributed` once per install; Play library is `compileOnly`, referenced only inside a nested class after a `Class.forName` check. |

Persistence: `SharedPreferences` file `com.mixpanel.android.mpmetrics.MixpanelAPI.AutoTrack_<instanceKey>`
(session, version, install-referrer flag) and `...AutoTrack.System` (OS version).

Related core change: `MixpanelActivityLifecycleCallbacks` persists the current `$ae_session` stint
in `...MixpanelAPI.Session_<instanceKey>` and back-fills it after process death; `SessionMetadata`
keeps `$mp_session_id` across backgrounds shorter than `MPConfig.SessionIdleTimeout`.
