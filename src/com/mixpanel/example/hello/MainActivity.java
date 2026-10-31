package com.mixpanel.example.hello;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mixpanel.android.mpmetrics.MPConfig;
import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.mixpanel.android.mpmetrics.MixpanelFlagVariant;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * A small playground for debugging the Mixpanel Android SDK.
 *
 * Every button maps to one SDK call, the status panel shows the SDK's resolved configuration
 * (version, identity, endpoints) plus the {@link AutoTracker} session, and the bottom panel
 * mirrors the SDK's logcat output so you can watch requests go out without a connected debugger.
 *
 * The SDK itself is initialised in {@link DebugApplication}; automatic events (screens,
 * open/background, install/update, deep links) come from {@link AutoTracker}.
 *
 * SDK docs: https://docs.mixpanel.com/docs/tracking-methods/sdks/android
 * Source:   https://github.com/mixpanel/mixpanel-android
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_EVENT_NAME = "Test Event";
    private static final String TIMED_EVENT_NAME = "Timed Event";
    private static final int MAX_LOG_CHARS = 60_000;

    private MixpanelAPI mMixpanel;
    private MPConfig mConfig;
    private AutoTracker mAutoTracker;

    private TextView mStatusView;
    private TextView mLogView;
    private ScrollView mLogScroll;
    private EditText mEventNameEdit;
    private EditText mEventPropsEdit;
    private EditText mDistinctIdEdit;
    private EditText mFirstNameEdit;
    private EditText mLastNameEdit;
    private EditText mEmailEdit;
    private Button mTimedEventButton;
    private Button mOptOutButton;

    private boolean mTimedEventRunning;
    private int mEventCounter;
    private LogcatReader mLogcatReader;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();

        final DebugApplication app = DebugApplication.from(this);
        mMixpanel = app.mixpanel();
        mAutoTracker = app.autoTracker();
        // MPConfig exposes everything the SDK resolved from the manifest (endpoints, intervals...).
        mConfig = MPConfig.getInstance(this, null);

        startLogMirror();
        refreshStatus(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // "$screen" and "Application Opened" are sent by AutoTracker; nothing to do here.
        refreshStatus(null);
    }

    @Override
    protected void onDestroy() {
        if (mLogcatReader != null) {
            mLogcatReader.stop();
        }
        // Push any pending events before the process can be killed.
        mMixpanel.flush();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------------
    // Setup

    private void bindViews() {
        mStatusView = findViewById(R.id.text_status);
        mLogView = findViewById(R.id.text_log);
        mLogScroll = findViewById(R.id.scroll_log);
        mEventNameEdit = findViewById(R.id.edit_event_name);
        mEventPropsEdit = findViewById(R.id.edit_event_props);
        mDistinctIdEdit = findViewById(R.id.edit_distinct_id);
        mFirstNameEdit = findViewById(R.id.edit_first_name);
        mLastNameEdit = findViewById(R.id.edit_last_name);
        mEmailEdit = findViewById(R.id.edit_email_address);
        mTimedEventButton = findViewById(R.id.button_timed_event);
        mOptOutButton = findViewById(R.id.button_opt_out);
    }

    private void startLogMirror() {
        mLogcatReader = new LogcatReader("Mixpanel", line -> mMainHandler.post(() -> appendLog(line)));
        mLogcatReader.start();
    }

    // ---------------------------------------------------------------------------------------------
    // Status

    public void refreshStatus(View view) {
        final StringBuilder sb = new StringBuilder();
        sb.append("SDK version      ").append(MixpanelAPI.VERSION).append('\n');
        sb.append("token            ").append(DebugApplication.MIXPANEL_API_TOKEN).append('\n');
        sb.append("distinct_id      ").append(mMixpanel.getDistinctId()).append('\n');
        sb.append("$device_id       ").append(mMixpanel.getAnonymousId()).append('\n');
        sb.append("opted out        ").append(mMixpanel.hasOptedOutTracking()).append('\n');
        sb.append("automatic events ").append(DebugApplication.TRACK_AUTOMATIC_EVENTS).append('\n');
        sb.append("session id       ").append(mAutoTracker.sessionId()).append('\n');
        sb.append("session start    ").append(formatTime(mAutoTracker.sessionStartMs())).append('\n');
        sb.append("last activity    ").append(formatTime(mAutoTracker.lastActivityMs())).append('\n');
        sb.append("session timeout  ").append(mAutoTracker.sessionTimeoutMs() / 60000).append(" min\n");
        sb.append("flush interval   ").append(mConfig.getFlushInterval()).append(" ms\n");
        sb.append("bulk upload lim. ").append(mConfig.getBulkUploadLimit()).append('\n');
        sb.append("flush batch size ").append(mConfig.getFlushBatchSize()).append('\n');
        sb.append("flush on bg      ").append(mConfig.getFlushOnBackground()).append('\n');
        sb.append("events  -> ").append(mConfig.getEventsEndpoint()).append('\n');
        sb.append("people  -> ").append(mConfig.getPeopleEndpoint()).append('\n');
        sb.append("groups  -> ").append(mConfig.getGroupsEndpoint()).append('\n');
        sb.append("flags   -> ").append(mConfig.getFlagsEndpoint()).append('\n');
        sb.append("super properties ").append(mMixpanel.getSuperProperties());
        mStatusView.setText(sb);

        mOptOutButton.setText(mMixpanel.hasOptedOutTracking()
                ? R.string.button_opt_in : R.string.button_opt_out);
    }

    // ---------------------------------------------------------------------------------------------
    // Events

    public void trackEvent(View view) {
        final String name = textOf(mEventNameEdit, DEFAULT_EVENT_NAME);
        final JSONObject properties = parseProperties();
        if (properties == null) {
            return;
        }
        try {
            properties.put("counter", ++mEventCounter);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        mMixpanel.track(name, properties);
        appendLog("app: track(\"" + name + "\", " + properties + ")");
    }

    public void toggleTimedEvent(View view) {
        if (!mTimedEventRunning) {
            // Starts a timer; the next track() of the same name gets a $duration property.
            mMixpanel.timeEvent(TIMED_EVENT_NAME);
            mTimedEventRunning = true;
            mTimedEventButton.setText(R.string.button_timed_event_stop);
            appendLog("app: timeEvent(\"" + TIMED_EVENT_NAME + "\")");
        } else {
            mMixpanel.track(TIMED_EVENT_NAME);
            mTimedEventRunning = false;
            mTimedEventButton.setText(R.string.button_timed_event_start);
            appendLog("app: track(\"" + TIMED_EVENT_NAME + "\") with $duration");
        }
    }

    public void flushQueue(View view) {
        mMixpanel.flush();
        appendLog("app: flush()");
    }

    // ---------------------------------------------------------------------------------------------
    // Identity

    public void identifyUser(View view) {
        final String distinctId = textOf(mDistinctIdEdit, null);
        if (distinctId == null) {
            toast(R.string.toast_distinct_id_required);
            return;
        }
        // Since SDK 6 identify() covers both events and People, and sends $identify when the
        // anonymous and identified ids differ.
        mMixpanel.identify(distinctId);
        appendLog("app: identify(\"" + distinctId + "\")");
        refreshStatus(null);
    }

    public void resetIdentity(View view) {
        // Generates a fresh anonymous distinct_id and clears super properties, like a logout.
        mMixpanel.reset();
        // reset() wipes super properties, so AutoTracker re-registers $session_id with a new session.
        mAutoTracker.onIdentityReset();
        mTimedEventRunning = false;
        mTimedEventButton.setText(R.string.button_timed_event_start);
        appendLog("app: reset()");
        refreshStatus(null);
    }

    public void toggleOptOut(View view) {
        if (mMixpanel.hasOptedOutTracking()) {
            mMixpanel.optInTracking();
            appendLog("app: optInTracking()");
        } else {
            // Drops queued data and ignores every call until opt-in.
            mMixpanel.optOutTracking();
            appendLog("app: optOutTracking()");
        }
        refreshStatus(null);
    }

    // ---------------------------------------------------------------------------------------------
    // People

    public void updateProfile(View view) {
        final String firstName = textOf(mFirstNameEdit, "");
        final String lastName = textOf(mLastNameEdit, "");
        final String email = textOf(mEmailEdit, "");

        final MixpanelAPI.People people = mMixpanel.getPeople();
        // People profiles keep the latest value for each property.
        people.set("$first_name", firstName);
        people.set("$last_name", lastName);
        people.set("$email", email);
        people.increment("Update Count", 1L);

        // Super properties ride along with every subsequent event.
        try {
            final JSONObject superProperties = new JSONObject();
            superProperties.put("user domain", domainFromEmailAddress(email));
            mMixpanel.registerSuperProperties(superProperties);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }

        mMixpanel.track("update info button clicked");
        appendLog("app: people.set($first_name/$last_name/$email), increment(Update Count)");
        refreshStatus(null);
    }

    public void recordRevenue(View view) {
        // Appends a $transactions entry to the profile.
        mMixpanel.getPeople().trackCharge(1.50, null);
        appendLog("app: people.trackCharge(1.50)");
    }

    // ---------------------------------------------------------------------------------------------
    // Feature flags

    public void loadFlags(View view) {
        appendLog("app: flags.loadFlags()");
        mMixpanel.getFlags().loadFlags(success -> mMainHandler.post(() -> {
            if (!Boolean.TRUE.equals(success)) {
                appendLog("app: flags fetch FAILED (see MixpanelAPI log above)");
                return;
            }
            final Map<String, MixpanelFlagVariant> variants = mMixpanel.getFlags().getAllVariantsSync();
            if (variants.isEmpty()) {
                appendLog("app: flags fetched, no flags defined for this project");
                return;
            }
            for (Map.Entry<String, MixpanelFlagVariant> entry : variants.entrySet()) {
                final MixpanelFlagVariant variant = entry.getValue();
                appendLog("app: flag " + entry.getKey() + " = " + variant.key + " -> " + variant.value);
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Log panel

    public void clearLog(View view) {
        mLogView.setText("");
    }

    private void appendLog(String line) {
        final CharSequence current = mLogView.getText();
        final String next = current.length() == 0 ? line : current + "\n" + line;
        // Keep only the tail so the TextView stays responsive during long sessions.
        mLogView.setText(next.length() > MAX_LOG_CHARS
                ? next.substring(next.length() - MAX_LOG_CHARS) : next);
        mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private JSONObject parseProperties() {
        final String raw = mEventPropsEdit.getText().toString().trim();
        if (raw.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            toast(R.string.toast_invalid_json);
            return null;
        }
    }

    private static String textOf(EditText edit, String fallback) {
        final String value = edit.getText().toString().trim();
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static String domainFromEmailAddress(String email) {
        final int at = email.indexOf('@');
        return at > -1 ? email.substring(at + 1) : "";
    }

    private static String formatTime(long millis) {
        return millis == 0 ? "-" : new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
