package com.mixpanel.android.mpmetrics;

import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.mixpanel.android.util.MPLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Reports main-thread freezes as {@code ANR Detected}. {@code $ae_crashed} only covers uncaught
 * exceptions; an ANR never throws. The watchdog posts a tick to the main looper from its own
 * HandlerThread and checks after {@code timeoutMs} whether the tick ran. The event carries the top
 * of the main thread's stack. Detection is skipped while a debugger is attached.
 */
/* package */ final class AnrWatchdog {

    static final String EVENT_ANR = "ANR Detected";

    private static final String LOGTAG = "MixpanelAPI.AutoTrack";
    private static final int STACK_FRAMES = 12;
    private static final long RECOVERY_POLL_MS = 200L;

    private final MixpanelAPI mMixpanel;
    private final long mTimeoutMs;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Handler mWorker;
    private final AtomicLong mTick = new AtomicLong();
    private final Runnable mTicker = mTick::incrementAndGet;

    static AnrWatchdog start(MixpanelAPI mixpanel, long timeoutMs) {
        final HandlerThread thread = new HandlerThread("MixpanelAPI.AnrWatchdog");
        thread.start();
        final AnrWatchdog watchdog = new AnrWatchdog(mixpanel, timeoutMs, thread.getLooper());
        watchdog.mWorker.post(watchdog::check);
        return watchdog;
    }

    private AnrWatchdog(MixpanelAPI mixpanel, long timeoutMs, Looper workerLooper) {
        mMixpanel = mixpanel;
        mTimeoutMs = timeoutMs;
        mWorker = new Handler(workerLooper);
    }

    // runs on the worker thread
    private void check() {
        final long before = mTick.get();
        final long postedAt = System.currentTimeMillis();
        mMainHandler.post(mTicker);
        mWorker.postDelayed(() -> verify(before, postedAt), mTimeoutMs);
    }

    // runs on the worker thread
    private void verify(long before, long postedAt) {
        try {
            if (mTick.get() != before) {
                check(); // responsive
                return;
            }
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                check(); // paused in the debugger, not a real ANR
                return;
            }
            report(System.currentTimeMillis() - postedAt);
            waitForRecovery(before, postedAt);
        } catch (Exception e) {
            MPLog.e(LOGTAG, "ANR check failed", e);
            check();
        }
    }

    // runs on the worker thread: one freeze yields one event
    private void waitForRecovery(long before, long postedAt) {
        if (mTick.get() == before) {
            mWorker.postDelayed(() -> waitForRecovery(before, postedAt), RECOVERY_POLL_MS);
            return;
        }
        MPLog.v(LOGTAG, "main thread recovered after " + (System.currentTimeMillis() - postedAt) + " ms");
        check();
    }

    private void report(long blockedMs) {
        final JSONObject props = new JSONObject();
        final JSONArray stack = new JSONArray();
        final StackTraceElement[] frames = Looper.getMainLooper().getThread().getStackTrace();
        for (int i = 0; i < frames.length && i < STACK_FRAMES; i++) {
            stack.put(frames[i].toString());
        }
        try {
            props.put("blocked_ms", blockedMs);
            props.put("main_thread_stack", stack);
        } catch (JSONException e) {
            MPLog.e(LOGTAG, "Cannot build ANR properties", e);
        }
        mMixpanel.track(EVENT_ANR, props);
        MPLog.v(LOGTAG, EVENT_ANR + " " + props);
    }
}
