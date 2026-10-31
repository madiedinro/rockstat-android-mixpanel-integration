package com.mixpanel.android.mpmetrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoTrackSessionTest {

    private static final long IDLE = 30 * 60 * 1000L;
    private static final long T0 = 1_700_000_000_000L;

    private MapStore mStore;
    private RecordingListener mListener;

    @Before
    public void setUp() {
        mStore = new MapStore();
        mListener = new RecordingListener();
    }

    private AutoTrackSession newSession() {
        return new AutoTrackSession(mStore, IDLE, mListener);
    }

    @Test
    public void firstLaunchStartsSession() {
        AutoTrackSession session = newSession();
        session.restore(T0);

        assertEquals(1, mListener.started.size());
        assertEquals(AutoTrackSession.REASON_FIRST_LAUNCH, mListener.started.get(0).reason);
        assertNotNull(session.getSessionId());
        assertEquals(session.getSessionId(), mStore.getString(AutoTrackSession.KEY_SESSION_ID, null));
        assertTrue(mListener.ended.isEmpty());
        assertTrue(mListener.unfinished.isEmpty());
    }

    @Test
    public void shortBackgroundKeepsSession() {
        AutoTrackSession session = newSession();
        session.restore(T0);
        String id = session.getSessionId();

        session.onForeground(T0);
        session.onBackground(T0 + 60_000);
        session.onForeground(T0 + 60_000 + IDLE - 1);

        assertEquals(id, session.getSessionId());
        assertEquals(1, mListener.started.size());
        assertTrue(mListener.ended.isEmpty());
    }

    @Test
    public void longBackgroundEndsOldSessionAtLastActivityAndStartsNew() {
        AutoTrackSession session = newSession();
        session.restore(T0);
        String oldId = session.getSessionId();

        session.onForeground(T0);
        long left = T0 + 5 * 60_000;
        session.onBackground(left);
        long back = left + IDLE + 1;
        session.onForeground(back);

        assertNotEquals(oldId, session.getSessionId());
        assertEquals(1, mListener.ended.size());
        RecordingListener.Ended ended = mListener.ended.get(0);
        assertEquals(oldId, ended.sessionId);
        assertEquals(T0, ended.startMs);
        assertEquals(left, ended.endMs); // back-dated to when the user actually left
        assertEquals(AutoTrackSession.REASON_TIMEOUT, ended.reason);
        assertEquals(2, mListener.started.size());
        assertEquals(back, mListener.started.get(1).startMs);
    }

    @Test
    public void processRestartWithinTimeoutResumesPersistedSession() {
        AutoTrackSession first = newSession();
        first.restore(T0);
        first.onForeground(T0);
        first.onBackground(T0 + 10_000);
        String id = first.getSessionId();

        // "new process": same store, new instance
        RecordingListener second = new RecordingListener();
        AutoTrackSession restarted = new AutoTrackSession(mStore, IDLE, second);
        restarted.restore(T0 + 10_000 + 60_000);

        assertEquals(id, restarted.getSessionId());
        assertTrue(second.started.isEmpty());
        assertTrue(second.ended.isEmpty());
        assertTrue(second.unfinished.isEmpty());
    }

    @Test
    public void processKilledInForegroundIsReportedAndSessionResumes() {
        AutoTrackSession first = newSession();
        first.restore(T0);
        first.onForeground(T0);
        first.touch(T0 + 42_000); // last time the user was seen; process dies after this
        String id = first.getSessionId();

        RecordingListener second = new RecordingListener();
        AutoTrackSession restarted = new AutoTrackSession(mStore, IDLE, second);
        restarted.restore(T0 + 42_000 + 60_000);

        assertEquals(1, second.unfinished.size());
        assertEquals(id, second.unfinished.get(0).sessionId);
        assertEquals(T0 + 42_000, second.unfinished.get(0).lastActivityMs);
        assertEquals(id, restarted.getSessionId()); // still within the idle timeout
        assertFalse(mStore.getBoolean(AutoTrackSession.KEY_IN_FOREGROUND, true));
    }

    @Test
    public void processKilledInForegroundThenTimeoutEndsAtLastActivity() {
        AutoTrackSession first = newSession();
        first.restore(T0);
        first.onForeground(T0);
        first.touch(T0 + 42_000);
        String id = first.getSessionId();

        RecordingListener second = new RecordingListener();
        AutoTrackSession restarted = new AutoTrackSession(mStore, IDLE, second);
        long back = T0 + 42_000 + IDLE + 1;
        restarted.restore(back);

        assertEquals(1, second.unfinished.size());
        assertEquals(1, second.ended.size());
        assertEquals(id, second.ended.get(0).sessionId);
        assertEquals(T0 + 42_000, second.ended.get(0).endMs);
        assertEquals(1, second.started.size());
        assertNotEquals(id, restarted.getSessionId());
    }

    @Test
    public void resetEndsAndStartsImmediately() {
        AutoTrackSession session = newSession();
        session.restore(T0);
        String oldId = session.getSessionId();

        session.reset(T0 + 1000);

        assertEquals(1, mListener.ended.size());
        assertEquals(oldId, mListener.ended.get(0).sessionId);
        assertEquals(T0 + 1000, mListener.ended.get(0).endMs);
        assertEquals(AutoTrackSession.REASON_RESET, mListener.ended.get(0).reason);
        assertEquals(2, mListener.started.size());
        assertNotEquals(oldId, session.getSessionId());
    }

    @Test
    public void zeroTimeoutStartsNewSessionOnEveryForeground() {
        AutoTrackSession session = new AutoTrackSession(mStore, 0L, mListener);
        session.restore(T0);
        String id = session.getSessionId();
        session.onBackground(T0 + 1);
        session.onForeground(T0 + 2);
        assertNotEquals(id, session.getSessionId());
    }

    // ---------------------------------------------------------------------------------------------

    private static final class MapStore implements AutoTrackSession.Store {
        private final Map<String, Object> map = new HashMap<>();

        @Override public String getString(String key, String def) { Object v = map.get(key); return v == null ? def : (String) v; }
        @Override public long getLong(String key, long def) { Object v = map.get(key); return v == null ? def : (Long) v; }
        @Override public boolean getBoolean(String key, boolean def) { Object v = map.get(key); return v == null ? def : (Boolean) v; }
        @Override public void put(String key, String value) { map.put(key, value); }
        @Override public void put(String key, long value) { map.put(key, value); }
        @Override public void put(String key, boolean value) { map.put(key, value); }
    }

    private static final class RecordingListener implements AutoTrackSession.Listener {
        static final class Started { String sessionId; long startMs; String reason; }
        static final class Ended { String sessionId; long startMs; long endMs; String reason; }
        static final class Unfinished { String sessionId; long startMs; long lastActivityMs; }

        final List<Started> started = new ArrayList<>();
        final List<Ended> ended = new ArrayList<>();
        final List<Unfinished> unfinished = new ArrayList<>();

        @Override
        public void onSessionStarted(String sessionId, long startMs, String reason) {
            Started s = new Started(); s.sessionId = sessionId; s.startMs = startMs; s.reason = reason; started.add(s);
        }

        @Override
        public void onSessionEnded(String sessionId, long startMs, long endMs, String reason) {
            Ended e = new Ended(); e.sessionId = sessionId; e.startMs = startMs; e.endMs = endMs; e.reason = reason; ended.add(e);
        }

        @Override
        public void onUnfinishedForeground(String sessionId, long startMs, long lastActivityMs) {
            Unfinished u = new Unfinished(); u.sessionId = sessionId; u.startMs = startMs; u.lastActivityMs = lastActivityMs; unfinished.add(u);
        }
    }
}
