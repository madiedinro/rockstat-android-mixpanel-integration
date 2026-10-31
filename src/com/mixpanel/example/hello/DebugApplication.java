package com.mixpanel.example.hello;

import android.app.Application;
import android.content.Context;

import com.mixpanel.android.mpmetrics.AutoTrackOptions;
import com.mixpanel.android.mpmetrics.AutocaptureOptions;
import com.mixpanel.android.mpmetrics.FeatureFlagOptions;
import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.mixpanel.android.mpmetrics.MixpanelOptions;

/**
 * Initialises the Mixpanel SDK (rockstat fork, built from ./sdk) once per process.
 *
 * Automatic events and durable sessions live inside the SDK now and are switched on with
 * {@link AutoTrackOptions}; doing this in the Application rather than an Activity makes sure the
 * first screen and the first "Application Opened" are not missed.
 */
public class DebugApplication extends Application {

    /**
     * Your Mixpanel project token: Mixpanel -> Project Settings -> Access Keys -> Project Token.
     */
    public static final String MIXPANEL_API_TOKEN = "fa45c697d1c664abb760166578f91f24";

    /**
     * When true the SDK sends $ae_first_open, $ae_updated, $ae_session and $ae_crashed on its own.
     * The {@link AutoTrackOptions} events are sent regardless of this flag.
     */
    static final boolean TRACK_AUTOMATIC_EVENTS = true;

    /**
     * SDK autocapture: $mp_click plus rage and dead clicks for every view.
     * Set to false if the click stream drowns out the events you are debugging.
     */
    static final boolean ENABLE_AUTOCAPTURE = true;

    private MixpanelAPI mMixpanel;

    @Override
    public void onCreate() {
        super.onCreate();

        final MixpanelOptions.Builder options = new MixpanelOptions.Builder()
                .featureFlagOptions(new FeatureFlagOptions.Builder().enabled(true).build())
                // All groups on: lifecycle, screens, sessions, deep links, system, ANR, install referrer.
                .autoTrackOptions(new AutoTrackOptions.Builder()
                        .sessionIdleTimeoutMs(AutoTrackOptions.DEFAULT_SESSION_IDLE_TIMEOUT_MS)
                        .anrTimeoutMs(AutoTrackOptions.DEFAULT_ANR_TIMEOUT_MS)
                        .build());
        if (ENABLE_AUTOCAPTURE) {
            options.autocaptureOptions(new AutocaptureOptions.Builder().build());
        }

        mMixpanel = MixpanelAPI.getInstance(this, MIXPANEL_API_TOKEN, TRACK_AUTOMATIC_EVENTS,
                options.build());
        // Same as the EnableDebugLogging meta-data, but explicit so it also works in release builds.
        mMixpanel.setEnableLogging(true);
    }

    public static DebugApplication from(Context context) {
        return (DebugApplication) context.getApplicationContext();
    }

    public MixpanelAPI mixpanel() {
        return mMixpanel;
    }
}
