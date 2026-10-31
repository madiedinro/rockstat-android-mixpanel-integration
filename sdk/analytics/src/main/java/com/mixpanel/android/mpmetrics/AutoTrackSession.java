package com.mixpanel.android.mpmetrics;

import java.util.UUID;

/**
 * Session state machine behind {@code Session Started} / {@code Session Ended} and the
 * {@code $session_id} super property. Pure Java on purpose: the clock and the store are injected,
 * so the timeout, resume and back-fill rules are unit-testable without Android.
 *
 * <p>Rules:
 * <ul>
 *   <li>A session continues while the gap since the last activity is at most the idle timeout,
 *       across backgrounds and across process restarts.</li>
 *   <li>When the gap is longer, the old session ends at its last activity (back-dated) and a new
 *       one starts now.</li>
 *   <li>If the previous process died while in the foreground, the listener is told so it can
 *       back-fill the background event the SDK never got to send.</li>
 * </ul>
 */
/* package */ final class AutoTrackSession {

    interface Store {
        String getString(String key, String defaultValue);
        long getLong(String key, long defaultValue);
        boolean getBoolean(String key, boolean defaultValue);
        void put(String key, String value);
        void put(String key, long value);
        void put(String key, boolean value);
        /** Optional: group the following puts into one write. */
        default void begin() { }
        default void commit() { }
    }

    interface Listener {
        void onSessionStarted(String sessionId, long startMs, String reason);

        /** {@code endMs} may be in the past (timeout or process death noticed on a later launch). */
        void onSessionEnded(String sessionId, long startMs, long endMs, String reason);

        /** The previous process was killed while foregrounded; {@code lastActivityMs} is when the user was last seen. */
        void onUnfinishedForeground(String sessionId, long startMs, long lastActivityMs);
    }

    static final String KEY_SESSION_ID = "session_id";
    static final String KEY_SESSION_START = "session_start_ms";
    static final String KEY_LAST_ACTIVITY = "last_activity_ms";
    static final String KEY_IN_FOREGROUND = "in_foreground";

    static final String REASON_FIRST_LAUNCH = "first_launch";
    static final String REASON_TIMEOUT = "timeout";
    static final String REASON_RESET = "reset";

    private final Object mLock = new Object();
    private final Store mStore;
    private final long mIdleTimeoutMs;
    private final Listener mListener;

    private String mSessionId;
    private long mStartMs;
    private long mLastActivityMs;

    AutoTrackSession(Store store, long idleTimeoutMs, Listener listener) {
        mStore = store;
        mIdleTimeoutMs = Math.max(0L, idleTimeoutMs);
        mListener = listener;
    }

    /** Loads the persisted session once at start-up and reconciles it with {@code nowMs}. */
    void restore(long nowMs) {
        synchronized (mLock) {
            mSessionId = mStore.getString(KEY_SESSION_ID, null);
            mStartMs = mStore.getLong(KEY_SESSION_START, 0L);
            mLastActivityMs = mStore.getLong(KEY_LAST_ACTIVITY, 0L);

            if (mSessionId == null) {
                start(nowMs, REASON_FIRST_LAUNCH);
                return;
            }
            if (mStore.getBoolean(KEY_IN_FOREGROUND, false)) {
                // Clear first: a crash inside the listener must not replay this forever.
                mStore.put(KEY_IN_FOREGROUND, false);
                mListener.onUnfinishedForeground(mSessionId, mStartMs, mLastActivityMs);
            }
            rollOverIfIdle(nowMs);
        }
    }

    void onForeground(long nowMs) {
        synchronized (mLock) {
            rollOverIfIdle(nowMs);
            mStore.begin();
            mLastActivityMs = nowMs;
            persist();
            mStore.put(KEY_IN_FOREGROUND, true);
            mStore.commit();
        }
    }

    void onBackground(long nowMs) {
        synchronized (mLock) {
            mStore.begin();
            mLastActivityMs = nowMs;
            persist();
            mStore.put(KEY_IN_FOREGROUND, false);
            mStore.commit();
        }
    }

    /** Any user activity: keeps the session alive and the persisted timestamp fresh. */
    void touch(long nowMs) {
        synchronized (mLock) {
            mStore.begin();
            mLastActivityMs = nowMs;
            persist();
            mStore.commit();
        }
    }

    /**
     * A logout / {@code reset()} is a hard session boundary. {@link #endForReset(long)} and
     * {@link #startAfterReset(long)} split it so the caller can end the old session under the old
     * identity and start the new one under the new identity.
     */
    void reset(long nowMs) {
        synchronized (mLock) {
            end(nowMs, REASON_RESET);
            start(nowMs, REASON_RESET);
        }
    }

    /** First half of {@link #reset(long)}: ends the current session, keeps its id until {@link #startAfterReset(long)}. */
    void endForReset(long nowMs) {
        synchronized (mLock) {
            end(nowMs, REASON_RESET);
        }
    }

    /** Second half of {@link #reset(long)}: starts a fresh session. */
    void startAfterReset(long nowMs) {
        synchronized (mLock) {
            start(nowMs, REASON_RESET);
        }
    }

    String getSessionId() {
        synchronized (mLock) {
            return mSessionId;
        }
    }

    long getStartMs() {
        synchronized (mLock) {
            return mStartMs;
        }
    }

    long getLastActivityMs() {
        synchronized (mLock) {
            return mLastActivityMs;
        }
    }

    // caller holds mLock
    private void rollOverIfIdle(long nowMs) {
        final long idleMs = nowMs - mLastActivityMs;
        if (idleMs > mIdleTimeoutMs) {
            end(mLastActivityMs, REASON_TIMEOUT);
            start(nowMs, REASON_TIMEOUT);
        } else {
            mLastActivityMs = nowMs;
            persist();
        }
    }

    // caller holds mLock
    private void start(long nowMs, String reason) {
        mSessionId = UUID.randomUUID().toString();
        mStartMs = nowMs;
        mLastActivityMs = nowMs;
        persist();
        mListener.onSessionStarted(mSessionId, mStartMs, reason);
    }

    // caller holds mLock
    private void end(long atMs, String reason) {
        if (mSessionId == null) {
            return;
        }
        mListener.onSessionEnded(mSessionId, mStartMs, Math.max(atMs, mStartMs), reason);
    }

    // caller holds mLock; writes are batched when the caller opened a batch, else applied one by one
    private void persist() {
        mStore.begin();
        mStore.put(KEY_SESSION_ID, mSessionId);
        mStore.put(KEY_SESSION_START, mStartMs);
        mStore.put(KEY_LAST_ACTIVITY, mLastActivityMs);
        mStore.commit();
    }
}
