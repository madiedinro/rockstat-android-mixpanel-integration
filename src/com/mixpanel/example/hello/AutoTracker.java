package com.mixpanel.example.hello;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.mixpanel.android.mpmetrics.MixpanelAPI;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/**
 * PostHog-style automatic events on top of the Mixpanel SDK, plus a session that survives
 * process death.
 *
 * <h3>Events</h3>
 * <ul>
 *   <li>{@code Application Installed} / {@code Application Updated} - first launch of a build.</li>
 *   <li>{@code Application Opened} ({@code from_background}) - process-level foreground.</li>
 *   <li>{@code Application Backgrounded} - process-level background, flushes immediately.</li>
 *   <li>{@code $screen} ({@code $screen_name}, {@code $screen_class}) - every Activity resume.</li>
 *   <li>{@code Deep Link Opened} ({@code url}, {@code referrer}) - Activity launched with a URI.</li>
 * </ul>
 *
 * <h3>Session</h3>
 * The SDK's own {@code $ae_session} is emitted from a 500 ms delayed Runnable after the last
 * Activity pauses and its {@code $mp_session_id} is regenerated on every foreground. When the
 * process is killed (swipe from Recents, OOM, crash) the Runnable never runs and the session
 * simply vanishes; when the user switches apps for a second, a new session starts.
 *
 * This tracker keeps its own session instead: a UUID attached to every event as the
 * {@code $session_id} super property, continued across backgrounds and process restarts as long
 * as the gap is shorter than {@code sessionTimeoutMs}, and persisted after every lifecycle change.
 * If the process died while in the foreground, the next launch back-fills
 * {@code Application Backgrounded} with the last persisted timestamp so the previous session still
 * gets an end marker.
 */
final class AutoTracker implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    static final String EVENT_APP_INSTALLED = "Application Installed";
    static final String EVENT_APP_UPDATED = "Application Updated";
    static final String EVENT_APP_OPENED = "Application Opened";
    static final String EVENT_APP_BACKGROUNDED = "Application Backgrounded";
    static final String EVENT_SCREEN = "$screen";
    static final String EVENT_DEEP_LINK = "Deep Link Opened";

    static final String PROP_SESSION_ID = "$session_id";
    static final String PROP_SESSION_START = "$session_start";

    private static final String LOGTAG = "MixpanelAuto";
    private static final String PREFS_NAME = "mixpanel_auto_tracker";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_SESSION_START = "session_start_ms";
    private static final String KEY_LAST_ACTIVITY = "last_activity_ms";
    private static final String KEY_IN_FOREGROUND = "in_foreground";
    private static final String KEY_VERSION_NAME = "version_name";
    private static final String KEY_VERSION_CODE = "version_code";

    private final Application mApp;
    private final MixpanelAPI mMixpanel;
    private final SharedPreferences mPrefs;
    private final long mSessionTimeoutMs;
    private final String mVersionName;
    private final long mVersionCode;

    private String mSessionId;
    private long mSessionStartMs;
    private long mLastActivityMs;
    private boolean mHasBeenForegrounded;
    private String mCurrentScreen;

    static AutoTracker install(Application app, MixpanelAPI mixpanel, long sessionTimeoutMs) {
        final AutoTracker tracker = new AutoTracker(app, mixpanel, sessionTimeoutMs);
        app.registerActivityLifecycleCallbacks(tracker);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(tracker);
        return tracker;
    }

    private AutoTracker(Application app, MixpanelAPI mixpanel, long sessionTimeoutMs) {
        mApp = app;
        mMixpanel = mixpanel;
        mSessionTimeoutMs = sessionTimeoutMs;
        mPrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        final PackageInfo info = packageInfo(app);
        mVersionName = info != null && info.versionName != null ? info.versionName : "unknown";
        mVersionCode = info != null ? versionCode(info) : -1;

        restoreSession();
        trackInstallOrUpdate();
    }

    // ---------------------------------------------------------------------------------------------
    // Session

    String sessionId() {
        return mSessionId;
    }

    long sessionStartMs() {
        return mSessionStartMs;
    }

    long lastActivityMs() {
        return mLastActivityMs;
    }

    long sessionTimeoutMs() {
        return mSessionTimeoutMs;
    }

    /** Call after {@link MixpanelAPI#reset()}: it wipes super properties, and a logout is a new session. */
    void onIdentityReset() {
        startSession(now(), "identity reset");
    }

    private void restoreSession() {
        mSessionId = mPrefs.getString(KEY_SESSION_ID, null);
        mSessionStartMs = mPrefs.getLong(KEY_SESSION_START, 0L);
        mLastActivityMs = mPrefs.getLong(KEY_LAST_ACTIVITY, 0L);
        final long now = now();

        if (mSessionId == null) {
            startSession(now, "first launch");
            return;
        }

        if (mPrefs.getBoolean(KEY_IN_FOREGROUND, false)) {
            // The process died while foregrounded: neither our onStop nor the SDK's delayed
            // $ae_session Runnable had a chance to run. Close the old session retroactively.
            final JSONObject props = appProperties();
            put(props, PROP_SESSION_ID, mSessionId);
            put(props, PROP_SESSION_START, mSessionStartMs);
            put(props, "session_length_s", seconds(mLastActivityMs - mSessionStartMs));
            put(props, "reason", "process_killed");
            put(props, "time", mLastActivityMs); // caller-supplied "time" overrides the SDK's
            mMixpanel.track(EVENT_APP_BACKGROUNDED, props);
            log("back-filled " + EVENT_APP_BACKGROUNDED + " for session " + mSessionId
                    + " at " + mLastActivityMs);
        }

        final long idleMs = now - mLastActivityMs;
        if (idleMs > mSessionTimeoutMs) {
            startSession(now, "idle " + seconds(idleMs) + " s > timeout");
        } else {
            applySessionSuperProperties();
            log("resumed session " + mSessionId + " after " + seconds(idleMs) + " s idle");
        }
    }

    private void startSession(long now, String reason) {
        mSessionId = UUID.randomUUID().toString();
        mSessionStartMs = now;
        mLastActivityMs = now;
        persistSession();
        applySessionSuperProperties();
        log("new session " + mSessionId + " (" + reason + ")");
    }

    private void applySessionSuperProperties() {
        final JSONObject props = new JSONObject();
        put(props, PROP_SESSION_ID, mSessionId);
        put(props, PROP_SESSION_START, mSessionStartMs);
        mMixpanel.registerSuperProperties(props);
    }

    private void touch() {
        mLastActivityMs = now();
        persistSession();
    }

    private void persistSession() {
        mPrefs.edit()
                .putString(KEY_SESSION_ID, mSessionId)
                .putLong(KEY_SESSION_START, mSessionStartMs)
                .putLong(KEY_LAST_ACTIVITY, mLastActivityMs)
                .apply();
    }

    // ---------------------------------------------------------------------------------------------
    // Process lifecycle (ProcessLifecycleOwner): true foreground / background of the whole app

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        final long now = now();
        final long idleMs = now - mLastActivityMs;
        if (idleMs > mSessionTimeoutMs) {
            startSession(now, "idle " + seconds(idleMs) + " s > timeout");
        }
        mLastActivityMs = now;
        persistSession();
        mPrefs.edit().putBoolean(KEY_IN_FOREGROUND, true).apply();

        final JSONObject props = appProperties();
        put(props, "from_background", mHasBeenForegrounded);
        mHasBeenForegrounded = true;
        track(EVENT_APP_OPENED, props);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        final long now = now();
        final JSONObject props = appProperties();
        put(props, "session_length_s", seconds(now - mSessionStartMs));
        track(EVENT_APP_BACKGROUNDED, props);

        mLastActivityMs = now;
        persistSession();
        mPrefs.edit().putBoolean(KEY_IN_FOREGROUND, false).apply();

        // Do not wait for the SDK's 500 ms delayed flush: the process may not live that long.
        mMixpanel.flush();
    }

    // ---------------------------------------------------------------------------------------------
    // Activity lifecycle: screens and deep links

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            return; // recreated after rotation / process restore, not a new deep link
        }
        final Intent intent = activity.getIntent();
        final Uri data = intent != null ? intent.getData() : null;
        if (data == null) {
            return;
        }
        final JSONObject props = new JSONObject();
        put(props, "url", data.toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            final Uri referrer = activity.getReferrer();
            if (referrer != null) {
                put(props, "referrer", referrer.toString());
            }
        }
        track(EVENT_DEEP_LINK, props);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        final String screenName = screenName(activity);
        if (!screenName.equals(mCurrentScreen)) {
            mCurrentScreen = screenName;
            final JSONObject props = new JSONObject();
            put(props, "$screen_name", screenName);
            put(props, "$screen_class", activity.getClass().getSimpleName());
            track(EVENT_SCREEN, props);
        }
        touch();
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        touch();
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) { }

    @Override
    public void onActivityStopped(@NonNull Activity activity) { }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) { }

    // ---------------------------------------------------------------------------------------------
    // Install / update

    private void trackInstallOrUpdate() {
        final String previousName = mPrefs.getString(KEY_VERSION_NAME, null);
        final long previousCode = mPrefs.getLong(KEY_VERSION_CODE, -1L);

        if (previousName == null) {
            track(EVENT_APP_INSTALLED, appProperties());
        } else if (previousCode != mVersionCode) {
            final JSONObject props = appProperties();
            put(props, "previous_version", previousName);
            put(props, "previous_build", previousCode);
            track(EVENT_APP_UPDATED, props);
        }

        if (previousName == null || previousCode != mVersionCode) {
            mPrefs.edit()
                    .putString(KEY_VERSION_NAME, mVersionName)
                    .putLong(KEY_VERSION_CODE, mVersionCode)
                    .apply();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private void track(String eventName, JSONObject props) {
        mMixpanel.track(eventName, props);
        log(eventName + " " + props);
    }

    private JSONObject appProperties() {
        final JSONObject props = new JSONObject();
        put(props, "version", mVersionName);
        put(props, "build", mVersionCode);
        return props;
    }

    private String screenName(Activity activity) {
        try {
            final PackageManager pm = mApp.getPackageManager();
            final ActivityInfo info = pm.getActivityInfo(activity.getComponentName(), 0);
            final CharSequence label = info.loadLabel(pm);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // fall through to the class name
        }
        return activity.getClass().getSimpleName();
    }

    private static PackageInfo packageInfo(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double seconds(long millis) {
        return Math.round(millis / 100.0) / 10.0;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void log(String message) {
        Log.d(LOGTAG, String.format(Locale.US, "%s", message));
    }
}
