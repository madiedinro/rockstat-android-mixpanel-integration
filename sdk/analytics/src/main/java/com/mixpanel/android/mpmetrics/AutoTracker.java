package com.mixpanel.android.mpmetrics;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.mixpanel.android.util.MPLog;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lifecycle, screen, deep link and session events for {@link AutoTrackOptions}. Installed by
 * {@link AutoTrackManager}; one instance per MixpanelAPI instance.
 *
 * <p>Foreground/background come from {@code ProcessLifecycleOwner} (whole process), screens from
 * {@code ActivityLifecycleCallbacks}. Session rules live in {@link AutoTrackSession}.
 */
/* package */ final class AutoTracker
        implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver, AutoTrackSession.Listener {

    static final String EVENT_APP_INSTALLED = "Application Installed";
    static final String EVENT_APP_UPDATED = "Application Updated";
    static final String EVENT_APP_OPENED = "Application Opened";
    static final String EVENT_APP_BACKGROUNDED = "Application Backgrounded";
    static final String EVENT_SESSION_STARTED = "Session Started";
    static final String EVENT_SESSION_ENDED = "Session Ended";
    static final String EVENT_SCREEN = "$screen";
    static final String EVENT_SCREEN_LEFT = "Screen Left";
    static final String EVENT_DEEP_LINK = "Deep Link Opened";

    static final String PROP_SESSION_ID = "$session_id";
    static final String PROP_SESSION_START = "$session_start";

    private static final String LOGTAG = "MixpanelAPI.AutoTrack";
    private static final String KEY_VERSION_NAME = "version_name";
    private static final String KEY_VERSION_CODE = "version_code";

    private final Application mApp;
    private final MixpanelAPI mMixpanel;
    private final AutoTrackOptions mOptions;
    private final SharedPreferences mPrefs;
    private final AutoTrackSession mSession; // null when session events are disabled
    private final String mVersionName;
    private final long mVersionCode;

    private boolean mHasBeenForegrounded;
    private String mCurrentScreen;
    private long mScreenEnteredMs;

    AutoTracker(Application app, MixpanelAPI mixpanel, AutoTrackOptions options, SharedPreferences prefs) {
        mApp = app;
        mMixpanel = mixpanel;
        mOptions = options;
        mPrefs = prefs;

        final PackageInfo info = packageInfo(app);
        mVersionName = info != null && info.versionName != null ? info.versionName : "unknown";
        mVersionCode = info != null ? versionCode(info) : -1L;

        if (options.isSessionEventsEnabled()) {
            mSession = new AutoTrackSession(new PrefsStore(prefs), options.getSessionIdleTimeoutMs(), this);
            mSession.restore(now());
        } else {
            mSession = null;
        }
        if (options.isLifecycleEventsEnabled()) {
            trackInstallOrUpdate();
        }
    }

    @Nullable
    String getSessionId() {
        return mSession == null ? null : mSession.getSessionId();
    }

    /** {@link MixpanelAPI#reset()} wipes super properties: re-register them under a new session. */
    void onIdentityReset() {
        if (mSession != null) {
            mSession.reset(now());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // AutoTrackSession.Listener

    @Override
    public void onSessionStarted(String sessionId, long startMs, String reason) {
        final JSONObject superProps = new JSONObject();
        put(superProps, PROP_SESSION_ID, sessionId);
        put(superProps, PROP_SESSION_START, startMs);
        mMixpanel.registerSuperProperties(superProps);

        final JSONObject props = new JSONObject();
        put(props, "reason", reason);
        track(EVENT_SESSION_STARTED, props);
    }

    @Override
    public void onSessionEnded(String sessionId, long startMs, long endMs, String reason) {
        final JSONObject props = new JSONObject();
        put(props, PROP_SESSION_ID, sessionId);
        put(props, PROP_SESSION_START, startMs);
        put(props, "session_length_s", seconds(endMs - startMs));
        put(props, "reason", reason);
        if (endMs < now() - 1000L) {
            put(props, "time", endMs); // back-date: caller properties override the SDK's time
        }
        track(EVENT_SESSION_ENDED, props);
    }

    @Override
    public void onUnfinishedForeground(String sessionId, long startMs, long lastActivityMs) {
        if (!mOptions.isLifecycleEventsEnabled()) {
            return;
        }
        final JSONObject props = appProperties();
        put(props, PROP_SESSION_ID, sessionId);
        put(props, PROP_SESSION_START, startMs);
        put(props, "session_length_s", seconds(lastActivityMs - startMs));
        put(props, "reason", "process_killed");
        put(props, "time", lastActivityMs);
        track(EVENT_APP_BACKGROUNDED, props);
    }

    // ---------------------------------------------------------------------------------------------
    // ProcessLifecycleOwner

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        try {
            final long now = now();
            if (mSession != null) {
                mSession.onForeground(now);
            }
            if (mOptions.isLifecycleEventsEnabled()) {
                final JSONObject props = appProperties();
                put(props, "from_background", mHasBeenForegrounded);
                track(EVENT_APP_OPENED, props);
            }
            mHasBeenForegrounded = true;
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onStart failed", e);
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        try {
            final long now = now();
            if (mOptions.isLifecycleEventsEnabled()) {
                final JSONObject props = appProperties();
                if (mSession != null) {
                    put(props, "session_length_s", seconds(now - mSession.getStartMs()));
                }
                track(EVENT_APP_BACKGROUNDED, props);
            }
            if (mSession != null) {
                mSession.onBackground(now);
            }
            // Do not wait for the 500 ms delayed background flush: the process may not live that long.
            mMixpanel.flush();
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onStop failed", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ActivityLifecycleCallbacks

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        if (!mOptions.isDeepLinkEventsEnabled() || savedInstanceState != null) {
            return; // recreated after rotation / process restore, not a new deep link
        }
        try {
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
        } catch (Exception e) {
            MPLog.e(LOGTAG, "deep link tracking failed", e);
        }
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        try {
            final long now = now();
            mScreenEnteredMs = now;
            if (mOptions.isScreenEventsEnabled()) {
                final String screenName = screenName(activity);
                if (!screenName.equals(mCurrentScreen)) {
                    mCurrentScreen = screenName;
                    final JSONObject props = new JSONObject();
                    put(props, "$screen_name", screenName);
                    put(props, "$screen_class", activity.getClass().getSimpleName());
                    track(EVENT_SCREEN, props);
                }
            }
            if (mSession != null) {
                mSession.touch(now);
            }
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onActivityResumed failed", e);
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        try {
            final long now = now();
            if (mOptions.isScreenEventsEnabled()) {
                final JSONObject props = new JSONObject();
                put(props, "$screen_name", screenName(activity));
                put(props, "$screen_class", activity.getClass().getSimpleName());
                put(props, "duration_s", seconds(now - mScreenEnteredMs));
                track(EVENT_SCREEN_LEFT, props);
            }
            if (mSession != null) {
                mSession.touch(now);
            }
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onActivityPaused failed", e);
        }
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
        try {
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
        } catch (Exception e) {
            MPLog.e(LOGTAG, "install/update tracking failed", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private void track(String eventName, JSONObject props) {
        mMixpanel.track(eventName, props);
        MPLog.v(LOGTAG, eventName + " " + props);
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
        } catch (Exception ignored) {
            // fall through to the class name
        }
        return activity.getClass().getSimpleName();
    }

    private static PackageInfo packageInfo(Application app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        } catch (Exception e) {
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
            json.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException e) {
            MPLog.e(LOGTAG, "Cannot put " + key, e);
        }
    }

    private static double seconds(long millis) {
        return Math.round(millis / 100.0) / 10.0;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** {@link AutoTrackSession.Store} over SharedPreferences. */
    private static final class PrefsStore implements AutoTrackSession.Store {
        private final SharedPreferences mPrefs;

        PrefsStore(SharedPreferences prefs) {
            mPrefs = prefs;
        }

        @Override public String getString(String key, String defaultValue) { return mPrefs.getString(key, defaultValue); }
        @Override public long getLong(String key, long defaultValue) { return mPrefs.getLong(key, defaultValue); }
        @Override public boolean getBoolean(String key, boolean defaultValue) { return mPrefs.getBoolean(key, defaultValue); }
        @Override public void put(String key, String value) { mPrefs.edit().putString(key, value).apply(); }
        @Override public void put(String key, long value) { mPrefs.edit().putLong(key, value).apply(); }
        @Override public void put(String key, boolean value) { mPrefs.edit().putBoolean(key, value).apply(); }
    }
}
