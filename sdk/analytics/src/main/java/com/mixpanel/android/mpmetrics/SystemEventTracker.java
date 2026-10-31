package com.mixpanel.android.mpmetrics;

import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mixpanel.android.util.MPLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.TimeZone;

/**
 * Device / OS level automatic events, the kind Firebase and Amplitude collect for free:
 *
 * <ul>
 *   <li>{@code OS Updated} - first launch after the Android version or build changed.</li>
 *   <li>{@code Memory Warning} - {@link #onTrimMemory(int)} / {@link #onLowMemory()} pressure levels.</li>
 *   <li>{@code Configuration Changed} - orientation, dark mode, locale, font scale, screen size.</li>
 *   <li>{@code Network Status Changed} - online/offline and transport (wifi, cellular, ...).</li>
 *   <li>{@code Time Zone Changed} - the device time zone moved.</li>
 *   <li>{@code Battery Low} / {@code Battery Okay} - system battery broadcasts.</li>
 * </ul>
 */
/* package */ final class SystemEventTracker implements ComponentCallbacks2 {

    static final String EVENT_OS_UPDATED = "OS Updated";
    static final String EVENT_MEMORY_WARNING = "Memory Warning";
    static final String EVENT_CONFIGURATION_CHANGED = "Configuration Changed";
    static final String EVENT_NETWORK_CHANGED = "Network Status Changed";
    static final String EVENT_TIMEZONE_CHANGED = "Time Zone Changed";
    static final String EVENT_BATTERY_LOW = "Battery Low";
    static final String EVENT_BATTERY_OKAY = "Battery Okay";

    private static final String LOGTAG = "MixpanelAPI.AutoTrack";
    private static final String KEY_OS_RELEASE = "os_release";
    private static final String KEY_OS_SDK_INT = "os_sdk_int";
    private static final String KEY_OS_BUILD = "os_build";

    private final Application mApp;
    private final MixpanelAPI mMixpanel;
    private final SharedPreferences mPrefs; // the instance's AutoTrack prefs: OS state is per instance
    private final Object mLock = new Object();

    private Configuration mLastConfig;
    private String mLastTimeZone = TimeZone.getDefault().getID();
    private Boolean mLastOnline;
    private String mLastTransport;
    private Network mCurrentNetwork; // API 24+: the default network we last reported

    // registered listeners, kept so stop() can remove them
    private Object mNetworkCallback; // ConnectivityManager.NetworkCallback on API 24+
    private BroadcastReceiver mConnectivityReceiver; // below API 24
    private BroadcastReceiver mSystemReceiver;
    private boolean mRunning;

    static SystemEventTracker install(Application app, MixpanelAPI mixpanel, SharedPreferences prefs) {
        final SystemEventTracker tracker = new SystemEventTracker(app, mixpanel, prefs);
        tracker.start();
        tracker.trackOsUpdate();
        return tracker;
    }

    private SystemEventTracker(Application app, MixpanelAPI mixpanel, SharedPreferences prefs) {
        mApp = app;
        mMixpanel = mixpanel;
        mPrefs = prefs;
        mLastConfig = new Configuration(app.getResources().getConfiguration());
    }

    /** Registers the callbacks and receivers. Idempotent. */
    void start() {
        synchronized (mLock) {
            if (mRunning) {
                return;
            }
            mRunning = true;
        }
        try {
            mApp.registerComponentCallbacks(this);
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot register component callbacks", e);
        }
        registerNetworkCallback();
        registerSystemBroadcasts();
    }

    /** Unregisters everything: used while the user has opted out, nothing is observed then. */
    void stop() {
        synchronized (mLock) {
            if (!mRunning) {
                return;
            }
            mRunning = false;
        }
        try {
            mApp.unregisterComponentCallbacks(this);
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot unregister component callbacks", e);
        }
        unregisterNetworkCallback();
        unregisterSystemBroadcasts();
    }

    // ---------------------------------------------------------------------------------------------
    // OS update

    private void trackOsUpdate() {
        try {
            doTrackOsUpdate();
        } catch (Exception e) {
            MPLog.e(LOGTAG, "OS update check failed", e);
        }
    }

    private void doTrackOsUpdate() {
        final String release = String.valueOf(Build.VERSION.RELEASE);
        final String build = String.valueOf(Build.VERSION.INCREMENTAL);
        final String previousRelease = mPrefs.getString(KEY_OS_RELEASE, null);
        final int previousSdk = mPrefs.getInt(KEY_OS_SDK_INT, -1);
        final String previousBuild = mPrefs.getString(KEY_OS_BUILD, null);

        if (previousRelease != null
                && (!previousRelease.equals(release) || previousSdk != Build.VERSION.SDK_INT
                || !build.equals(previousBuild))) {
            final JSONObject props = new JSONObject();
            put(props, "previous_os_version", previousRelease);
            put(props, "previous_sdk_int", previousSdk);
            put(props, "previous_os_build", previousBuild);
            put(props, "os_version", release);
            put(props, "sdk_int", Build.VERSION.SDK_INT);
            put(props, "os_build", build);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                put(props, "security_patch", Build.VERSION.SECURITY_PATCH);
            }
            track(EVENT_OS_UPDATED, props);
        }

        if (previousRelease == null || !previousRelease.equals(release)
                || previousSdk != Build.VERSION.SDK_INT || !build.equals(previousBuild)) {
            mPrefs.edit()
                    .putString(KEY_OS_RELEASE, release)
                    .putInt(KEY_OS_SDK_INT, Build.VERSION.SDK_INT)
                    .putString(KEY_OS_BUILD, build)
                    .apply();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Memory pressure (ComponentCallbacks2)

    @Override
    public void onTrimMemory(int level) {
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            return; // fires on every background, not a memory signal
        }
        try {
            trackMemory(level, trimLevelName(level));
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onTrimMemory failed", e);
        }
    }

    @Override
    public void onLowMemory() {
        try {
            trackMemory(-1, "LOW_MEMORY");
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onLowMemory failed", e);
        }
    }

    private void trackMemory(int level, String levelName) {
        final JSONObject props = new JSONObject();
        put(props, "level", level);
        put(props, "level_name", levelName);
        final ActivityManager am = (ActivityManager) mApp.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            put(props, "available_mb", info.availMem / (1024 * 1024));
            put(props, "total_mb", info.totalMem / (1024 * 1024));
            put(props, "system_low_memory", info.lowMemory);
        }
        final Runtime rt = Runtime.getRuntime();
        put(props, "heap_used_mb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        put(props, "heap_max_mb", rt.maxMemory() / (1024 * 1024));
        track(EVENT_MEMORY_WARNING, props);
    }

    private static String trimLevelName(int level) {
        switch (level) {
            case TRIM_MEMORY_RUNNING_MODERATE: return "RUNNING_MODERATE";
            case TRIM_MEMORY_RUNNING_LOW: return "RUNNING_LOW";
            case TRIM_MEMORY_RUNNING_CRITICAL: return "RUNNING_CRITICAL";
            case TRIM_MEMORY_BACKGROUND: return "BACKGROUND";
            case TRIM_MEMORY_MODERATE: return "MODERATE";
            case TRIM_MEMORY_COMPLETE: return "COMPLETE";
            default: return "LEVEL_" + level;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Configuration (ComponentCallbacks)

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        try {
            trackConfigurationChange(newConfig);
        } catch (Exception e) {
            MPLog.e(LOGTAG, "onConfigurationChanged failed", e);
        }
    }

    private void trackConfigurationChange(Configuration newConfig) {
        final JSONArray changed = new JSONArray();
        final JSONObject props = new JSONObject();

        final String orientation = orientationName(newConfig.orientation);
        if (newConfig.orientation != mLastConfig.orientation) {
            changed.put("orientation");
            put(props, "previous_orientation", orientationName(mLastConfig.orientation));
        }
        put(props, "orientation", orientation);

        final boolean dark = isDarkMode(newConfig);
        if (dark != isDarkMode(mLastConfig)) {
            changed.put("dark_mode");
        }
        put(props, "dark_mode", dark);

        final String locale = localeOf(newConfig);
        if (!locale.equals(localeOf(mLastConfig))) {
            changed.put("locale");
            put(props, "previous_locale", localeOf(mLastConfig));
        }
        put(props, "locale", locale);

        if (newConfig.fontScale != mLastConfig.fontScale) {
            changed.put("font_scale");
            put(props, "previous_font_scale", mLastConfig.fontScale);
        }
        put(props, "font_scale", newConfig.fontScale);

        if (newConfig.screenWidthDp != mLastConfig.screenWidthDp
                || newConfig.screenHeightDp != mLastConfig.screenHeightDp) {
            changed.put("screen_size");
        }
        put(props, "screen_width_dp", newConfig.screenWidthDp);
        put(props, "screen_height_dp", newConfig.screenHeightDp);

        mLastConfig = new Configuration(newConfig);
        if (changed.length() == 0) {
            return; // something we do not report (keyboard, density, ...)
        }
        put(props, "changed", changed);
        track(EVENT_CONFIGURATION_CHANGED, props);
    }

    private static String orientationName(int orientation) {
        return orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait";
    }

    private static boolean isDarkMode(Configuration config) {
        return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private static String localeOf(Configuration config) {
        final Locale locale = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? config.getLocales().get(0) : config.locale;
        return locale == null ? "" : locale.toLanguageTag();
    }

    // ---------------------------------------------------------------------------------------------
    // Network

    private void registerNetworkCallback() {
        try {
            doRegisterNetworkCallback();
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot register network callback", e);
        }
    }

    private void doRegisterNetworkCallback() {
        final ConnectivityManager cm =
                (ConnectivityManager) mApp.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        // The SDK manifest does not declare ACCESS_NETWORK_STATE (it is optional for the host).
        // Both the default-network callback and getActiveNetworkInfo() need it; without it they
        // throw SecurityException, so skip network events rather than risk the host app.
        if (mApp.checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            log("ACCESS_NETWORK_STATE not granted, skipping " + EVENT_NETWORK_CHANGED);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                  @NonNull NetworkCapabilities caps) {
                    try {
                        synchronized (mLock) {
                            mCurrentNetwork = network;
                        }
                        onNetwork(true, transportOf(caps),
                                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                    } catch (Exception e) {
                        MPLog.e(LOGTAG, "onCapabilitiesChanged failed", e);
                    }
                }

                @Override
                public void onLost(@NonNull Network network) {
                    try {
                        // During a Wi-Fi -> cellular handover the old network is reported lost
                        // well after the new default is up: only the current default counts.
                        synchronized (mLock) {
                            if (mCurrentNetwork != null && !mCurrentNetwork.equals(network)) {
                                return;
                            }
                            mCurrentNetwork = null;
                        }
                        onNetwork(false, "none", false, false);
                    } catch (Exception e) {
                        MPLog.e(LOGTAG, "onLost failed", e);
                    }
                }
            };
            cm.registerDefaultNetworkCallback(callback);
            mNetworkCallback = callback;
        } else {
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                @SuppressWarnings("deprecation")
                public void onReceive(Context context, Intent intent) {
                    try {
                        final NetworkInfo info = cm.getActiveNetworkInfo();
                        final boolean online = info != null && info.isConnected();
                        onNetwork(online, online ? legacyTransport(info.getType()) : "none",
                                cm.isActiveNetworkMetered(), online);
                    } catch (Exception e) {
                        MPLog.e(LOGTAG, "connectivity receiver failed", e);
                    }
                }
            };
            registerReceiver(receiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            mConnectivityReceiver = receiver;
        }
    }

    private void unregisterNetworkCallback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mNetworkCallback != null) {
                final ConnectivityManager cm =
                        (ConnectivityManager) mApp.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback((ConnectivityManager.NetworkCallback) mNetworkCallback);
                }
                mNetworkCallback = null;
            }
            if (mConnectivityReceiver != null) {
                mApp.unregisterReceiver(mConnectivityReceiver);
                mConnectivityReceiver = null;
            }
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot unregister network callback", e);
        }
    }

    private void onNetwork(boolean online, String transport, boolean metered, boolean validated) {
        final String previousTransport;
        synchronized (mLock) {
            final boolean initial = mLastOnline == null;
            if (!initial && mLastOnline == online && transport.equals(mLastTransport)) {
                return; // capability churn (signal strength etc.), not a status change
            }
            previousTransport = mLastTransport;
            mLastOnline = online;
            mLastTransport = transport;
            if (initial) {
                log("network: initial state online=" + online + " transport=" + transport);
                return;
            }
        }
        final JSONObject props = new JSONObject();
        put(props, "online", online);
        put(props, "transport", transport);
        put(props, "previous_transport", previousTransport);
        put(props, "metered", metered);
        put(props, "validated", validated);
        track(EVENT_NETWORK_CHANGED, props);
    }

    private static String transportOf(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth";
        return "other";
    }

    @SuppressWarnings("deprecation")
    private static String legacyTransport(int type) {
        switch (type) {
            case ConnectivityManager.TYPE_WIFI: return "wifi";
            case ConnectivityManager.TYPE_MOBILE: return "cellular";
            case ConnectivityManager.TYPE_ETHERNET: return "ethernet";
            case ConnectivityManager.TYPE_VPN: return "vpn";
            case ConnectivityManager.TYPE_BLUETOOTH: return "bluetooth";
            default: return "other";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Time zone and battery broadcasts

    private void registerSystemBroadcasts() {
        try {
            doRegisterSystemBroadcasts();
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot register system broadcasts", e);
        }
    }

    private void doRegisterSystemBroadcasts() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    final String action = intent == null ? null : intent.getAction();
                    if (Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                        final String timeZone = TimeZone.getDefault().getID();
                        final JSONObject props = new JSONObject();
                        synchronized (mLock) {
                            put(props, "previous_timezone", mLastTimeZone);
                            mLastTimeZone = timeZone;
                        }
                        put(props, "timezone", timeZone);
                        put(props, "offset_minutes", TimeZone.getDefault().getRawOffset() / 60000);
                        track(EVENT_TIMEZONE_CHANGED, props);
                    } else if (Intent.ACTION_BATTERY_LOW.equals(action)) {
                        track(EVENT_BATTERY_LOW, batteryProperties());
                    } else if (Intent.ACTION_BATTERY_OKAY.equals(action)) {
                        track(EVENT_BATTERY_OKAY, batteryProperties());
                    }
                } catch (Exception e) {
                    MPLog.e(LOGTAG, "system broadcast receiver failed", e);
                }
            }
        };
        registerReceiver(receiver, filter);
        mSystemReceiver = receiver;
    }

    private void unregisterSystemBroadcasts() {
        try {
            if (mSystemReceiver != null) {
                mApp.unregisterReceiver(mSystemReceiver);
                mSystemReceiver = null;
            }
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot unregister system broadcasts", e);
        }
    }

    private JSONObject batteryProperties() {
        final JSONObject props = new JSONObject();
        // ACTION_BATTERY_CHANGED is sticky: a null receiver just reads the last value.
        final Intent status = mApp.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (status != null) {
            final int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                put(props, "battery_percent", Math.round(level * 100f / scale));
            }
            final int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            put(props, "charging", plugged != 0);
        }
        return props;
    }

    private void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts are still delivered to a non-exported receiver.
            mApp.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            mApp.registerReceiver(receiver, filter);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private void track(String eventName, @Nullable JSONObject props) {
        try {
            mMixpanel.track(eventName, props);
            log(eventName + " " + props);
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot track " + eventName, e);
        }
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException e) {
            MPLog.e(LOGTAG, "Cannot put " + key, e);
        }
    }

    private static void log(String message) {
        MPLog.v(LOGTAG, message);
    }
}
