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
    private final AnrWatchdog mAnrWatchdog;

    @Nullable
    static AutoTrackManager install(Context context, MixpanelAPI mixpanel, String token,
                                    @Nullable String instanceName, AutoTrackOptions options) {
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
            return new AutoTrackManager(app, mixpanel, options, prefs);
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot install automatic event tracking", e);
            return null;
        }
    }

    private AutoTrackManager(Application app, MixpanelAPI mixpanel, AutoTrackOptions options,
                             SharedPreferences prefs) {
        AutoTracker autoTracker = null;
        if (options.isLifecycleEventsEnabled() || options.isScreenEventsEnabled()
                || options.isSessionEventsEnabled() || options.isDeepLinkEventsEnabled()) {
            try {
                autoTracker = new AutoTracker(app, mixpanel, options, prefs);
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

        if (options.isSystemEventsEnabled()) {
            try {
                SystemEventTracker.install(app, mixpanel);
            } catch (Exception e) {
                MPLog.e(LOGTAG, "Cannot install system event tracker", e);
            }
        }

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
                InstallReferrerTracker.trackOnce(app, mixpanel, prefs);
            } catch (Exception e) {
                MPLog.e(LOGTAG, "Cannot read install referrer", e);
            }
        }
    }

    @Nullable
    String getSessionId() {
        return mAutoTracker == null ? null : mAutoTracker.getSessionId();
    }

    void onIdentityReset() {
        try {
            if (mAutoTracker != null) {
                mAutoTracker.onIdentityReset();
            }
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onIdentityReset failed", e);
        }
    }
}
