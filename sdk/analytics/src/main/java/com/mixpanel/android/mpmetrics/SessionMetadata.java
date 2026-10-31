package com.mixpanel.android.mpmetrics;

import android.content.SharedPreferences;

import com.mixpanel.android.util.MPLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;

import static com.mixpanel.android.mpmetrics.ConfigurationChecker.LOGTAG;

/**
 * Produces the {@code $mp_metadata} block attached to every event and people update.
 *
 * <p>A session used to be regenerated on every foreground and lived only in memory, so a one
 * second trip to another app, or the process being killed, started a new {@code $mp_session_id}.
 * The session is now kept while the gap since the last activity is shorter than the idle timeout
 * ({@code com.mixpanel.android.MPConfig.SessionIdleTimeout}, 30 minutes by default) and, when a
 * {@link SharedPreferences} store is supplied, survives process death. An idle timeout of 0
 * restores the old behaviour.
 */
/* package */ class SessionMetadata {
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_SESSION_START_SEC = "session_start_sec";
    private static final String KEY_LAST_ACTIVITY_MS = "session_last_activity_ms";
    private static final String KEY_EVENTS_COUNTER = "session_events_counter";
    private static final String KEY_PEOPLE_COUNTER = "session_people_counter";

    private final Object mLock = new Object();
    private final SecureRandom mRandom = new SecureRandom();
    private final SharedPreferences mStore; // null: in-memory only
    private final long mIdleTimeoutMs;

    private long mEventsCounter, mPeopleCounter, mSessionStartEpoch, mLastActivityMs;
    private long mLastPersistMs;
    private String mSessionID;

    /** Events refresh the persisted last-activity time at most this often. */
    private static final long EVENT_PERSIST_INTERVAL_MS = 60 * 1000L;

    /** In-memory session that restarts on every foreground (legacy behaviour). */
    /* package */ SessionMetadata() {
        this(null, 0L);
    }

    /* package */ SessionMetadata(SharedPreferences store, long idleTimeoutMs) {
        mStore = store;
        mIdleTimeoutMs = Math.max(0L, idleTimeoutMs);
        if (!restore(System.currentTimeMillis())) {
            initSession();
        }
    }

    protected void initSession() {
        synchronized (mLock) {
            mEventsCounter = 0L;
            mPeopleCounter = 0L;
            mSessionID = Long.toHexString(new SecureRandom().nextLong());
            mSessionStartEpoch = System.currentTimeMillis() / 1000;
            mLastActivityMs = System.currentTimeMillis();
            persist();
        }
    }

    /**
     * Called when the app comes to the foreground: continues the session if the user was away
     * for less than the idle timeout, starts a new one otherwise.
     */
    /* package */ void onForeground(long nowMs) {
        synchronized (mLock) {
            if (nowMs - mLastActivityMs > mIdleTimeoutMs) {
                MPLog.v(LOGTAG, "Session " + mSessionID + " idle for " + (nowMs - mLastActivityMs)
                        + " ms, starting a new one");
                initSession();
            } else {
                mLastActivityMs = nowMs;
                persist();
            }
        }
    }

    /** Called when the app goes to the background: remembers when the user left. */
    /* package */ void onBackground(long nowMs) {
        synchronized (mLock) {
            mLastActivityMs = nowMs;
            persist();
        }
    }

    /* package */ String getSessionId() {
        synchronized (mLock) {
            return mSessionID;
        }
    }

    public JSONObject getMetadataForEvent() {
        return getNewMetadata(true);
    }

    public JSONObject getMetadataForPeople() {
        return getNewMetadata(false);
    }

    private JSONObject getNewMetadata(boolean isEvent) {
        JSONObject metadataJson = new JSONObject();
        synchronized (mLock) {
            try {
                metadataJson.put("$mp_event_id", Long.toHexString(mRandom.nextLong()));
                metadataJson.put("$mp_session_id", mSessionID);
                metadataJson.put("$mp_session_seq_id", isEvent ? mEventsCounter : mPeopleCounter);
                metadataJson.put("$mp_session_start_sec", mSessionStartEpoch);
                if (isEvent) {
                    mEventsCounter++;
                } else {
                    mPeopleCounter++;
                }
                mLastActivityMs = System.currentTimeMillis();
                // A long foreground stint without a background would otherwise leave the
                // persisted timestamp stale and roll the session over after a kill.
                if (mLastActivityMs - mLastPersistMs > EVENT_PERSIST_INTERVAL_MS) {
                    persist();
                }
            } catch (JSONException e) {
                MPLog.e(LOGTAG, "Cannot create session metadata JSON object", e);
            }
        }
        return metadataJson;
    }

    private boolean restore(long nowMs) {
        if (mStore == null) {
            return false;
        }
        try {
            final String id = mStore.getString(KEY_SESSION_ID, null);
            final long lastActivity = mStore.getLong(KEY_LAST_ACTIVITY_MS, 0L);
            if (id == null || nowMs - lastActivity > mIdleTimeoutMs) {
                return false;
            }
            synchronized (mLock) {
                mSessionID = id;
                mSessionStartEpoch = mStore.getLong(KEY_SESSION_START_SEC, nowMs / 1000);
                // Counters are persisted on background; a kill in the foreground may lose a few
                // increments, so leave a gap to keep sequence ids unique within the session.
                mEventsCounter = mStore.getLong(KEY_EVENTS_COUNTER, 0L) + 1000L;
                mPeopleCounter = mStore.getLong(KEY_PEOPLE_COUNTER, 0L) + 1000L;
                mLastActivityMs = nowMs;
                persist();
            }
            MPLog.v(LOGTAG, "Restored session " + id + " after " + (nowMs - lastActivity) + " ms");
            return true;
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot restore session metadata", e);
            return false;
        }
    }

    // caller holds mLock
    private void persist() {
        if (mStore == null) {
            return;
        }
        try {
            mLastPersistMs = System.currentTimeMillis();
            mStore.edit()
                    .putString(KEY_SESSION_ID, mSessionID)
                    .putLong(KEY_SESSION_START_SEC, mSessionStartEpoch)
                    .putLong(KEY_LAST_ACTIVITY_MS, mLastActivityMs)
                    .putLong(KEY_EVENTS_COUNTER, mEventsCounter)
                    .putLong(KEY_PEOPLE_COUNTER, mPeopleCounter)
                    .apply();
        } catch (Exception e) {
            MPLog.e(LOGTAG, "Cannot persist session metadata", e);
        }
    }
}
