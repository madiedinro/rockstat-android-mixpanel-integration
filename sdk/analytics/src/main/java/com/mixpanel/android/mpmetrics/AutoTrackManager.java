package com.mixpanel.android.mpmetrics;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.mixpanel.android.util.MPLog;

/**
 * Installs the {@link AutoTrackOptions} trackers for one MixpanelAPI instance. Every step is
 * isolated: a failure in one tracker never prevents the others, and never reaches the host app.
 */
/* package */ final class AutoTrackManager {
    private static final String LOGTAG = "MixpanelAPI.AutoTrack";

    private final AutoTracker mAutoTracker; // null when no lifecycle/screen/session/deep-link group is on
    private final SystemEventTracker mSystemEventTracker;
    private final AnrWatchdog mAnrWatchdog;

    @Nullable
    static AutoTrackManager install(Context context, MixpanelAPI mixpanel, String token,
                                    @Nullable String instanceName, AutoTrackOptions options,
                                    boolean flushOnBackground) {
        try {
            if (!(context.getApplicationContext() instanceof Application)) {
                MPLog.w(LOGTAG, "Context is not an Application, automatic events are unavailable.");
                return null;
            }
            final Application app = (Application) context.getApplicationContext();
            final String instanceKey = instanceName != null ? instanceName : token;
            final SharedPreferences prefs = app.getSharedPreferences(
                    "com.mixpanel.android.mpmetrics.MixpanelAPI.AutoTrack_" + instanceKey,
                    Context.MODE_PRIVATE);
            return new AutoTrackManager(app, mixpanel, options, prefs, flushOnBackground);
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot install automatic event tracking", e);
            return null;
        }
    }

    private AutoTrackManager(Application app, MixpanelAPI mixpanel, AutoTrackOptions options,
                             SharedPreferences prefs, boolean flushOnBackground) {
        AutoTracker autoTracker = null;
        if (options.isLifecycleEventsEnabled() || options.isScreenEventsEnabled()
                || options.isSessionEventsEnabled() || options.isDeepLinkEventsEnabled()) {
            try {
                autoTracker = new AutoTracker(app, mixpanel, options, prefs, flushOnBackground);
                app.registerActivityLifecycleCallbacks(autoTracker);
                final AutoTracker observer = autoTracker;
                // LifecycleRegistry requires the main thread; if the app is already started the
                // observer receives onStart synchronously, so a cold start is not missed.
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        ProcessLifecycleOwner.get().getLifecycle().addObserver(observer);
                    } catch (Exception e) {
                        MPLog.e(LOGTAG, "Cannot observe process lifecycle", e);
                    }
                });
            } catch (Exception e) {
                MPLog.e(LOGTAG, "Cannot install lifecycle tracker", e);
            }
        }
        mAutoTracker = autoTracker;

        SystemEventTracker systemEventTracker = null;
        if (options.isSystemEventsEnabled()) {
            try {
                systemEventTracker = SystemEventTracker.install(app, mixpanel, prefs);
            } catch (Exception e) {
                MPLog.e(LOGTAG, "Cannot install system event tracker", e);
            }
        }
        mSystemEventTracker = systemEventTracker;

        AnrWatchdog watchdog = null;
        if (options.getAnrTimeoutMs() > 0) {
            try {
                watchdog = AnrWatchdog.start(mixpanel, options.getAnrTimeoutMs());
            } catch (Exception e) {
                MPLog.e(LOGTAG, "Cannot start ANR watchdog", e);
            }
        }
        mAnrWatchdog = watchdog;

        if (options.isInstallReferrerEventEnabled()) {
            try {
                // An app that adopts the SDK after it was installed has no fresh install to
                // attribute; AutoTracker knows, fall back to PackageManager when it is off.
                final boolean freshInstall = autoTracker != null
                        ? autoTracker.isFreshInstall() : AutoTracker.isFreshInstall(packageInfo(app));
                InstallReferrerTracker.trackOnce(app, mixpanel, prefs, freshInstall);
            } catch (Exception e) {
                MPLog.e(LOGTAG, "Cannot read install referrer", e);
            }
        }
    }

    @Nullable
    private static android.content.pm.PackageInfo packageInfo(Application app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    String getSessionId() {
        return mAutoTracker == null ? null : mAutoTracker.getSessionId();
    }

    /** Before {@link MixpanelAPI#reset()} wipes the identity: ends the session under the old one. */
    void onBeforeIdentityReset() {
        try {
            if (mAutoTracker != null) {
                mAutoTracker.onBeforeIdentityReset();
            }
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onBeforeIdentityReset failed", e);
        }
    }

    /** After {@link MixpanelAPI#reset()}: starts a session under the new identity. */
    void onAfterIdentityReset() {
        try {
            if (mAutoTracker != null) {
                mAutoTracker.onAfterIdentityReset();
            }
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onAfterIdentityReset failed", e);
        }
    }

    /** {@link MixpanelAPI#optOutTracking()}: stop observing the system, nothing may be recorded. */
    void onOptOut() {
        try {
            if (mSystemEventTracker != null) {
                mSystemEventTracker.stop();
            }
            if (mAnrWatchdog != null) {
                mAnrWatchdog.pause();
            }
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onOptOut failed", e);
        }
    }

    /** {@link MixpanelAPI#optInTracking()}: resume observers and put the session super properties back. */
    void onOptIn() {
        try {
            if (mSystemEventTracker != null) {
                mSystemEventTracker.start();
            }
            if (mAnrWatchdog != null) {
                mAnrWatchdog.resume();
            }
            if (mAutoTracker != null) {
                mAutoTracker.onOptIn();
            }
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onOptIn failed", e);
        }
    }
}
