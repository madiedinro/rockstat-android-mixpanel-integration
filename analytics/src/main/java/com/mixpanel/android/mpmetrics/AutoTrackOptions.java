package com.mixpanel.android.mpmetrics;

import androidx.annotation.NonNull;

/**
 * Opt-in automatic events beyond the {@code $ae_*} set, modelled on what PostHog, Amplitude,
 * Firebase and Segment collect out of the box. Enable by passing an instance to
 * {@link MixpanelOptions.Builder#autoTrackOptions(AutoTrackOptions)}:
 *
 * <pre>{@code
 * MixpanelOptions options = new MixpanelOptions.Builder()
 *         .autoTrackOptions(new AutoTrackOptions.Builder().build()) // everything on
 *         .build();
 * }</pre>
 *
 * <table>
 *   <tr><th>Group</th><th>Events</th></tr>
 *   <tr><td>lifecycle</td><td>{@code Application Installed}, {@code Application Updated},
 *       {@code Application Opened} ({@code from_background}), {@code Application Backgrounded}</td></tr>
 *   <tr><td>screens</td><td>{@code $screen} ({@code $screen_name}, {@code $screen_class}),
 *       {@code Screen Left} ({@code duration_s})</td></tr>
 *   <tr><td>sessions</td><td>{@code Session Started}, {@code Session Ended}; every event gets the
 *       {@code $session_id} / {@code $session_start} super properties. The session continues
 *       across backgrounds and process restarts shorter than the idle timeout and is closed
 *       retroactively (back-dated {@code time}) when the process died or timed out.</td></tr>
 *   <tr><td>deep links</td><td>{@code Deep Link Opened} ({@code url}, {@code referrer})</td></tr>
 *   <tr><td>system</td><td>{@code OS Updated}, {@code Memory Warning}, {@code Configuration Changed},
 *       {@code Network Status Changed}, {@code Time Zone Changed}, {@code Battery Low} / {@code Battery Okay}</td></tr>
 *   <tr><td>ANR</td><td>{@code ANR Detected} ({@code blocked_ms}, {@code main_thread_stack})</td></tr>
 *   <tr><td>install referrer</td><td>{@code Install Attributed} (needs
 *       {@code com.android.installreferrer:installreferrer} on the app's classpath)</td></tr>
 * </table>
 *
 * <p>These events are plain {@code track()} calls: they are not gated by the
 * {@code trackAutomaticEvents} flag and are sent to custom endpoints like any other event.
 */
public class AutoTrackOptions {

    /** Default inactivity gap after which a new session starts: 30 minutes. */
    public static final long DEFAULT_SESSION_IDLE_TIMEOUT_MS = 30L * 60L * 1000L;

    /** Default main-thread freeze reported as an ANR: 5 seconds, Android's own input limit. */
    public static final long DEFAULT_ANR_TIMEOUT_MS = 5000L;

    private final boolean mLifecycleEvents;
    private final boolean mScreenEvents;
    private final boolean mSessionEvents;
    private final boolean mDeepLinkEvents;
    private final boolean mSystemEvents;
    private final boolean mInstallReferrerEvent;
    private final long mAnrTimeoutMs;
    private final long mSessionIdleTimeoutMs;

    private AutoTrackOptions(Builder builder) {
        mLifecycleEvents = builder.mLifecycleEvents;
        mScreenEvents = builder.mScreenEvents;
        mSessionEvents = builder.mSessionEvents;
        mDeepLinkEvents = builder.mDeepLinkEvents;
        mSystemEvents = builder.mSystemEvents;
        mInstallReferrerEvent = builder.mInstallReferrerEvent;
        mAnrTimeoutMs = builder.mAnrTimeoutMs;
        mSessionIdleTimeoutMs = builder.mSessionIdleTimeoutMs;
    }

    public boolean isLifecycleEventsEnabled() {
        return mLifecycleEvents;
    }

    public boolean isScreenEventsEnabled() {
        return mScreenEvents;
    }

    public boolean isSessionEventsEnabled() {
        return mSessionEvents;
    }

    public boolean isDeepLinkEventsEnabled() {
        return mDeepLinkEvents;
    }

    public boolean isSystemEventsEnabled() {
        return mSystemEvents;
    }

    public boolean isInstallReferrerEventEnabled() {
        return mInstallReferrerEvent;
    }

    /** 0 disables ANR detection. */
    public long getAnrTimeoutMs() {
        return mAnrTimeoutMs;
    }

    public long getSessionIdleTimeoutMs() {
        return mSessionIdleTimeoutMs;
    }

    /** All groups enabled by default; turn off what you do not want. */
    public static class Builder {
        private boolean mLifecycleEvents = true;
        private boolean mScreenEvents = true;
        private boolean mSessionEvents = true;
        private boolean mDeepLinkEvents = true;
        private boolean mSystemEvents = true;
        private boolean mInstallReferrerEvent = true;
        private long mAnrTimeoutMs = DEFAULT_ANR_TIMEOUT_MS;
        private long mSessionIdleTimeoutMs = DEFAULT_SESSION_IDLE_TIMEOUT_MS;

        public Builder() {
        }

        public Builder(@NonNull AutoTrackOptions source) {
            mLifecycleEvents = source.mLifecycleEvents;
            mScreenEvents = source.mScreenEvents;
            mSessionEvents = source.mSessionEvents;
            mDeepLinkEvents = source.mDeepLinkEvents;
            mSystemEvents = source.mSystemEvents;
            mInstallReferrerEvent = source.mInstallReferrerEvent;
            mAnrTimeoutMs = source.mAnrTimeoutMs;
            mSessionIdleTimeoutMs = source.mSessionIdleTimeoutMs;
        }

        public Builder lifecycleEvents(boolean enabled) {
            mLifecycleEvents = enabled;
            return this;
        }

        public Builder screenEvents(boolean enabled) {
            mScreenEvents = enabled;
            return this;
        }

        public Builder sessionEvents(boolean enabled) {
            mSessionEvents = enabled;
            return this;
        }

        public Builder deepLinkEvents(boolean enabled) {
            mDeepLinkEvents = enabled;
            return this;
        }

        public Builder systemEvents(boolean enabled) {
            mSystemEvents = enabled;
            return this;
        }

        public Builder installReferrerEvent(boolean enabled) {
            mInstallReferrerEvent = enabled;
            return this;
        }

        /** Main-thread freeze (ms) reported as {@code ANR Detected}; 0 disables the watchdog. */
        public Builder anrTimeoutMs(long anrTimeoutMs) {
            mAnrTimeoutMs = Math.max(0L, anrTimeoutMs);
            return this;
        }

        /** Background gap (ms) after which the next foreground starts a new session. */
        public Builder sessionIdleTimeoutMs(long sessionIdleTimeoutMs) {
            mSessionIdleTimeoutMs = Math.max(0L, sessionIdleTimeoutMs);
            return this;
        }

        public AutoTrackOptions build() {
            return new AutoTrackOptions(this);
        }
    }
}
