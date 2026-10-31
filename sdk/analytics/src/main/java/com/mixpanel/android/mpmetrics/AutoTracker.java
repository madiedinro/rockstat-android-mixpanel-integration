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
    private final boolean mFreshInstall;
    private final boolean mFlushOnBackground;

    private boolean mHasBeenForegrounded;
    private String mCurrentScreenClass; // fully qualified Activity class name
    private long mScreenEnteredMs;

    AutoTracker(Application app, MixpanelAPI mixpanel, AutoTrackOptions options, SharedPreferences prefs,
                boolean flushOnBackground) {
        mApp = app;
        mMixpanel = mixpanel;
        mOptions = options;
        mPrefs = prefs;
        mFlushOnBackground = flushOnBackground;

        final PackageInfo info = packageInfo(app);
        mVersionName = info != null && info.versionName != null ? info.versionName : "unknown";
        mVersionCode = info != null ? versionCode(info) : -1L;
        mFreshInstall = isFreshInstall(info);

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

    boolean isFreshInstall() {
        return mFreshInstall;
    }

    /**
     * {@link MixpanelAPI#optOutTracking()} wipes super properties and {@code registerSuperProperties}
     * is a no-op while opted out, so {@code $session_id} must be put back when the user opts in.
     */
    void onOptIn() {
        if (mSession == null) {
            return;
        }
        final JSONObject superProps = new JSONObject();
        put(superProps, PROP_SESSION_ID, mSession.getSessionId());
        put(superProps, PROP_SESSION_START, mSession.getStartMs());
        mMixpanel.registerSuperProperties(superProps);
    }

    /**
     * First half of {@link MixpanelAPI#reset()}, called before the identity is wiped so
     * {@code Session Ended} still carries the old distinct_id and super properties.
     */
    void onBeforeIdentityReset() {
        if (mSession != null) {
            mSession.endForReset(now());
        }
    }

    /** Second half of {@link MixpanelAPI#reset()}: super properties are gone, start a new session under the new identity. */
    void onAfterIdentityReset() {
        if (mSession != null) {
            mSession.startAfterReset(now());
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
            if (mFlushOnBackground) {
                mMixpanel.flush();
            }
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
                // Dedupe on the component, not the label: unlabeled Activities all share the app label.
                final String screenClass = activity.getClass().getName();
                if (!screenClass.equals(mCurrentScreenClass)) {
                    mCurrentScreenClass = screenClass;
                    track(EVENT_SCREEN, screenProperties(activity));
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
                final JSONObject props = screenProperties(activity);
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
                // No record yet: either a fresh install, or an existing install whose app just
                // adopted this SDK. PackageManager tells them apart; only the former is an install.
                if (mFreshInstall) {
                    track(EVENT_APP_INSTALLED, appProperties());
                } else {
                    track(EVENT_APP_UPDATED, appProperties());
                }
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

    private JSONObject screenProperties(Activity activity) {
        final JSONObject props = new JSONObject();
        put(props, "$screen_name", screenName(activity));
        put(props, "$screen_class", activity.getClass().getSimpleName());
        return props;
    }

    /**
     * The Activity's own {@code android:label} when it declares one, otherwise its simple class
     * name. {@code loadLabel()} is not used blindly: for an unlabeled Activity it falls back to the
     * application label, which would name every screen after the app.
     */
    private String screenName(Activity activity) {
        try {
            final PackageManager pm = mApp.getPackageManager();
            final ActivityInfo info = pm.getActivityInfo(activity.getComponentName(), 0);
            if (info.labelRes != 0 || info.nonLocalizedLabel != null) {
                final CharSequence label = info.loadLabel(pm);
                if (label != null && label.length() > 0) {
                    return label.toString();
                }
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

    /**
     * True until the package is updated for the first time: Android keeps {@code lastUpdateTime}
     * equal to {@code firstInstallTime} for a never-updated install. Unknown package info counts as
     * fresh so a broken PackageManager does not hide every install.
     */
    static boolean isFreshInstall(@Nullable PackageInfo info) {
        if (info == null) {
            return true;
        }
        return info.lastUpdateTime <= info.firstInstallTime;
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

    /**
     * {@link AutoTrackSession.Store} over SharedPreferences. Writes between {@code begin()} and
     * {@code commit()} share a single {@code apply()}; a write outside a batch is applied at once.
     */
    private static final class PrefsStore implements AutoTrackSession.Store {
        private final SharedPreferences mPrefs;
        private SharedPreferences.Editor mBatch; // non-null between the outermost begin() and commit()
        private int mDepth; // begin()/commit() nest: persist() runs inside onForeground() etc.

        PrefsStore(SharedPreferences prefs) {
            mPrefs = prefs;
        }

        @Override public String getString(String key, String defaultValue) { return mPrefs.getString(key, defaultValue); }
        @Override public long getLong(String key, long defaultValue) { return mPrefs.getLong(key, defaultValue); }
        @Override public boolean getBoolean(String key, boolean defaultValue) { return mPrefs.getBoolean(key, defaultValue); }

        @Override public void put(String key, String value) {
            if (mBatch != null) mBatch.putString(key, value); else mPrefs.edit().putString(key, value).apply();
        }
        @Override public void put(String key, long value) {
            if (mBatch != null) mBatch.putLong(key, value); else mPrefs.edit().putLong(key, value).apply();
        }
        @Override public void put(String key, boolean value) {
            if (mBatch != null) mBatch.putBoolean(key, value); else mPrefs.edit().putBoolean(key, value).apply();
        }

        @Override public void begin() {
            if (mDepth++ == 0) {
                mBatch = mPrefs.edit();
            }
        }

        @Override public void commit() {
            if (mDepth > 0 && --mDepth == 0 && mBatch != null) {
                mBatch.apply();
                mBatch = null;
            }
        }
    }
}
