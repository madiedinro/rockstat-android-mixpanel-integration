package com.mixpanel.example.hello;

import android.app.Application;
import android.content.Context;

import com.mixpanel.android.mpmetrics.AutocaptureOptions;
import com.mixpanel.android.mpmetrics.FeatureFlagOptions;
import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.mixpanel.android.mpmetrics.MixpanelOptions;

/**
 * Initialises the Mixpanel SDK once per process and installs {@link AutoTracker}.
 *
 * Doing this in the Application (not in an Activity) matters for automatic events: the tracker
 * has to be registered before the first Activity resumes, otherwise the first screen and the
 * first "Application Opened" are missed.
 */
public class DebugApplication extends Application {

    /**
     * Your Mixpanel project token: Mixpanel -> Project Settings -> Access Keys -> Project Token.
     */
    public static final String MIXPANEL_API_TOKEN = "fa45c697d1c664abb760166578f91f24";

    /**
     * When true the SDK sends $ae_first_open, $ae_updated, $ae_session and $ae_crashed on its own.
     * {@link AutoTracker} sends its PostHog-style equivalents regardless of this flag.
     */
    static final boolean TRACK_AUTOMATIC_EVENTS = true;

    /**
     * Autocapture (GA since SDK 8.12.0) tracks $mp_click, rage clicks and dead clicks for every
     * view. Off by default here because it floods the queue while you debug hand-made events.
     */
    static final boolean ENABLE_AUTOCAPTURE = false;

    /** Inactivity gap after which {@link AutoTracker} starts a new session (PostHog uses 30 min). */
    static final long SESSION_TIMEOUT_MS = 30L * 60L * 1000L;

    private MixpanelAPI mMixpanel;
    private AutoTracker mAutoTracker;

    @Override
    public void onCreate() {
        super.onCreate();

        final MixpanelOptions.Builder options = new MixpanelOptions.Builder()
                .featureFlagOptions(new FeatureFlagOptions.Builder().enabled(true).build());
        if (ENABLE_AUTOCAPTURE) {
            options.autocaptureOptions(new AutocaptureOptions.Builder().build());
        }

        mMixpanel = MixpanelAPI.getInstance(this, MIXPANEL_API_TOKEN, TRACK_AUTOMATIC_EVENTS,
                options.build());
        // Same as the EnableDebugLogging meta-data, but explicit so it also works in release builds.
        mMixpanel.setEnableLogging(true);

        mAutoTracker = AutoTracker.install(this, mMixpanel, SESSION_TIMEOUT_MS);
    }

    public static DebugApplication from(Context context) {
        return (DebugApplication) context.getApplicationContext();
    }

    public MixpanelAPI mixpanel() {
        return mMixpanel;
    }

    AutoTracker autoTracker() {
        return mAutoTracker;
    }
}
