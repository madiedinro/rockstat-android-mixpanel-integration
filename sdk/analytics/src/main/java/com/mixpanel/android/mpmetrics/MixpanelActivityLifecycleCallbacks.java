package com.mixpanel.android.mpmetrics;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.mixpanel.android.util.MPLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * Tracks foreground/background transitions and the {@code $ae_session} automatic event.
 *
 * <p>The background check runs from a 500 ms delayed Runnable so that an Activity-to-Activity
 * transition is not mistaken for a background. The cost used to be that a process killed inside
 * that window, or any time later while still in the foreground (swipe from Recents, low memory,
 * crash), never produced {@code $ae_session} at all. The current foreground stint is therefore
 * persisted ({@code session_start}, {@code last_activity}, {@code in_foreground}) and, on the
 * next launch, a stint that was never closed is back-filled with its last known timestamp.
 */
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
/* package */ class MixpanelActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private static final String LOGTAG = "MixpanelAPI.Lifecycle";
    private static final String KEY_STINT_START_MS = "ae_session_start_ms";
    private static final String KEY_STINT_LAST_ACTIVITY_MS = "ae_session_last_activity_ms";
    private static final String KEY_IN_FOREGROUND = "ae_session_in_foreground";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable check;
    private boolean mIsForeground = false;
    private boolean mPaused = true;
    private static Double sStartSessionTime;
    public static final int CHECK_DELAY = 500;

    public MixpanelActivityLifecycleCallbacks(MixpanelAPI mpInstance, MPConfig config) {
        this(mpInstance, config, null);
    }

    public MixpanelActivityLifecycleCallbacks(MixpanelAPI mpInstance, MPConfig config,
                                              SharedPreferences sessionStore) {
        mMpInstance = mpInstance;
        mConfig = config;
        mSessionStore = sessionStore;
        if (sStartSessionTime == null) {
            sStartSessionTime = (double) System.currentTimeMillis();
        }
        backfillUnfinishedSession();
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }

    @Override
    public void onActivityPaused(final Activity activity) {
        mPaused = true;

        if (check != null) {
            mHandler.removeCallbacks(check);
        }
        mCurrentActivity = null;
        // Remember when the user was last seen: if the process dies before the delayed check
        // below runs, this is the end of the session.
        persistStint(sStartSessionTime, System.currentTimeMillis(), true);

        mHandler.postDelayed(check = new Runnable(){
            @Override
            public void run() {
                if (mIsForeground && mPaused) {
                    mIsForeground = false;
                    final long now = System.currentTimeMillis();
                    trackSession(sStartSessionTime, now, null);
                    persistStint(sStartSessionTime, now, false);
                    mMpInstance.onBackground();
                }
            }
        }, CHECK_DELAY);
    }

    @Override
    public void onActivityDestroyed(Activity activity) { }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }

    @Override
    public void onActivityResumed(Activity activity) {
        mCurrentActivity = new WeakReference<>(activity);

        mPaused = false;
        boolean wasBackground = !mIsForeground;
        mIsForeground = true;

        if (check != null) {
            mHandler.removeCallbacks(check);
        }

        if (wasBackground) {
            // App is in foreground now
            sStartSessionTime = (double) System.currentTimeMillis();
            mMpInstance.onForeground();
        }
        persistStint(sStartSessionTime, System.currentTimeMillis(), true);
    }

    @Override
    public void onActivityStopped(Activity activity) { }

    protected boolean isInForeground() {
        return mIsForeground;
    }

    /**
     * Emits {@code $ae_session} for a stint that ran from {@code startMs} to {@code endMs}.
     * {@code eventTimeMs}, when not null, back-dates the event (the caller's "time" property
     * overrides the SDK's), which is how a stint cut short by process death is reported on the
     * next launch at the moment the user actually left.
     */
    private void trackSession(Double startMs, long endMs, Long eventTimeMs) {
        if (startMs == null || !mMpInstance.getTrackAutomaticEvents()) {
            return;
        }
        try {
            double sessionLength = endMs - startMs;
            if (sessionLength >= mConfig.getMinimumSessionDuration()
                    && sessionLength < mConfig.getSessionTimeoutDuration()) {
                double elapsedTime = sessionLength / 1000;
                double elapsedTimeRounded = Math.round(elapsedTime * 10.0) / 10.0;
                JSONObject sessionProperties = new JSONObject();
                sessionProperties.put(AutomaticEvents.SESSION_LENGTH, elapsedTimeRounded);
                if (eventTimeMs != null) {
                    sessionProperties.put("time", eventTimeMs);
                }
                mMpInstance.getPeople().increment(AutomaticEvents.TOTAL_SESSIONS, 1);
                mMpInstance.getPeople().increment(AutomaticEvents.TOTAL_SESSIONS_LENGTH, elapsedTimeRounded);
                mMpInstance.track(AutomaticEvents.SESSION, sessionProperties, true);
            }
        } catch (JSONException e) {
            MPLog.e(LOGTAG, "Cannot build $ae_session properties", e);
        }
    }

    private void backfillUnfinishedSession() {
        if (mSessionStore == null) {
            return;
        }
        try {
            if (!mSessionStore.getBoolean(KEY_IN_FOREGROUND, false)) {
                return;
            }
            final long start = mSessionStore.getLong(KEY_STINT_START_MS, 0L);
            final long lastActivity = mSessionStore.getLong(KEY_STINT_LAST_ACTIVITY_MS, 0L);
            // Clear first so a crash inside track() cannot replay the same stint forever.
            mSessionStore.edit().putBoolean(KEY_IN_FOREGROUND, false).apply();
            if (start <= 0L || lastActivity < start) {
                return;
            }
            MPLog.v(LOGTAG, "Process died in the foreground; back-filling $ae_session for a stint of "
                    + (lastActivity - start) + " ms");
            trackSession((double) start, lastActivity, lastActivity);
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot back-fill unfinished session", e);
        }
    }

    private void persistStint(Double startMs, long lastActivityMs, boolean inForeground) {
        if (mSessionStore == null || startMs == null) {
            return;
        }
        try {
            mSessionStore.edit()
                    .putLong(KEY_STINT_START_MS, startMs.longValue())
                    .putLong(KEY_STINT_LAST_ACTIVITY_MS, lastActivityMs)
                    .putBoolean(KEY_IN_FOREGROUND, inForeground)
                    .apply();
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot persist session state", e);
        }
    }

    private final MixpanelAPI mMpInstance;
    private final MPConfig mConfig;
    private final SharedPreferences mSessionStore;
    private WeakReference<Activity> mCurrentActivity;
}
