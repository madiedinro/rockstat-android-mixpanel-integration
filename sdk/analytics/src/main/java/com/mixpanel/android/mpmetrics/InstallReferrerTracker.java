package com.mixpanel.android.mpmetrics;

import android.content.Context;
import android.content.SharedPreferences;

import com.mixpanel.android.util.MPLog;

import org.json.JSONObject;

/**
 * Sends {@code Install Attributed} once per install with the Google Play install referrer.
 *
 * The Play library is a {@code compileOnly} dependency of the SDK: apps that ship
 * {@code com.android.installreferrer:installreferrer} get the event, others get a log line. The
 * Play classes are referenced only inside {@link PlayReferrer}, which is loaded after the
 * class-presence check, so the host app never sees a {@code NoClassDefFoundError}.
 */
/* package */ final class InstallReferrerTracker {

    static final String EVENT_INSTALL_ATTRIBUTED = "Install Attributed";

    private static final String LOGTAG = "MixpanelAPI.AutoTrack";
    private static final String KEY_DONE = "install_referrer_done";
    private static final String CLIENT_CLASS = "com.android.installreferrer.api.InstallReferrerClient";

    private InstallReferrerTracker() { }

    /**
     * @param freshInstall false when the package had already been updated before this SDK first
     *     ran: the referrer would describe an install from the past, so it is recorded as done
     *     without sending anything.
     */
    static void trackOnce(Context context, MixpanelAPI mixpanel, SharedPreferences prefs, boolean freshInstall) {
        if (prefs.getBoolean(KEY_DONE, false)) {
            return;
        }
        if (!freshInstall) {
            MPLog.v(LOGTAG, "not a fresh install, skipping " + EVENT_INSTALL_ATTRIBUTED);
            prefs.edit().putBoolean(KEY_DONE, true).apply();
            return;
        }
        try {
            Class.forName(CLIENT_CLASS);
        } catch (ClassNotFoundException e) {
            MPLog.v(LOGTAG, "installreferrer library not present, skipping " + EVENT_INSTALL_ATTRIBUTED);
            return;
        }
        try {
            PlayReferrer.fetch(context, mixpanel, prefs);
        } catch (Throwable t) {
            // Some devices throw SecurityException/DeadObjectException when binding to Play.
            MPLog.w(LOGTAG, "install referrer connection failed", t);
        }
    }

    private static final class PlayReferrer {
        static void fetch(Context context, final MixpanelAPI mixpanel, final SharedPreferences prefs) {
            final com.android.installreferrer.api.InstallReferrerClient client =
                    com.android.installreferrer.api.InstallReferrerClient.newBuilder(context).build();
            client.startConnection(new com.android.installreferrer.api.InstallReferrerStateListener() {
                @Override
                public void onInstallReferrerSetupFinished(int responseCode) {
                    try {
                        if (responseCode == com.android.installreferrer.api.InstallReferrerClient
                                .InstallReferrerResponse.OK) {
                            final com.android.installreferrer.api.ReferrerDetails details =
                                    client.getInstallReferrer();
                            final JSONObject props = new JSONObject();
                            props.put("referrer", details.getInstallReferrer());
                            props.put("referrer_click_ts", details.getReferrerClickTimestampSeconds());
                            props.put("install_begin_ts", details.getInstallBeginTimestampSeconds());
                            props.put("google_play_instant", details.getGooglePlayInstantParam());
                            props.put("install_version", details.getInstallVersion());
                            mixpanel.track(EVENT_INSTALL_ATTRIBUTED, props);
                            MPLog.v(LOGTAG, EVENT_INSTALL_ATTRIBUTED + " " + props);
                            prefs.edit().putBoolean(KEY_DONE, true).apply();
                        } else if (responseCode == com.android.installreferrer.api.InstallReferrerClient
                                .InstallReferrerResponse.FEATURE_NOT_SUPPORTED) {
                            MPLog.v(LOGTAG, "install referrer not supported on this device");
                            prefs.edit().putBoolean(KEY_DONE, true).apply();
                        } else {
                            // SERVICE_UNAVAILABLE etc.: no Play Store right now, retry next launch.
                            MPLog.v(LOGTAG, "install referrer unavailable, code " + responseCode);
                        }
                    } catch (Exception e) {
                        MPLog.w(LOGTAG, "install referrer failed", e);
                    } finally {
                        try {
                            client.endConnection();
                        } catch (Exception ignored) {
                            // nothing to release
                        }
                    }
                }

                @Override
                public void onInstallReferrerServiceDisconnected() { }
            });
        }
    }
}
