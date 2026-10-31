package com.mixpanel.android.mpmetrics;

import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.mixpanel.android.util.MPLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reports main-thread freezes as {@code ANR Detected}. {@code $ae_crashed} only covers uncaught
 * exceptions; an ANR never throws. The watchdog posts a tick to the main looper from its own
 * HandlerThread and checks after {@code timeoutMs} whether the tick ran. The event carries the top
 * of the main thread's stack. Detection is skipped while a debugger is attached.
 *
 * <p>Times come from {@link SystemClock#uptimeMillis()}, the clock the main looper runs on, so a
 * frozen process (Android 11+ cached-app freezer, deep doze) does not read as an ANR. A freeze is
 * reported only after a second check confirms the tick still has not run.
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
    private final AtomicBoolean mRunning = new AtomicBoolean(true);

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

    /** Stops checking (opt-out) and quits the worker thread. The watchdog cannot be restarted. */
    void stop() {
        if (mRunning.compareAndSet(true, false)) {
            mWorker.removeCallbacksAndMessages(null);
            mMainHandler.removeCallbacks(mTicker);
            mWorker.getLooper().quitSafely();
        }
    }

    /** Pauses checking without quitting the thread; {@link #resume()} restarts it. */
    void pause() {
        mRunning.set(false);
        mWorker.removeCallbacksAndMessages(null);
    }

    void resume() {
        if (mRunning.compareAndSet(false, true)) {
            mWorker.post(this::check);
        }
    }

    // runs on the worker thread
    private void check() {
        if (!mRunning.get()) {
            return;
        }
        final long before = mTick.get();
        final long postedAt = SystemClock.uptimeMillis();
        mMainHandler.post(mTicker);
        mWorker.postDelayed(() -> verify(before, postedAt, false), mTimeoutMs);
    }

    // runs on the worker thread
    private void verify(long before, long postedAt, boolean confirmed) {
        if (!mRunning.get()) {
            return;
        }
        try {
            if (mTick.get() != before) {
                check(); // responsive
                return;
            }
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                check(); // paused in the debugger, not a real ANR
                return;
            }
            if (!confirmed) {
                // Re-check shortly: if the whole process was frozen, the tick runs right after
                // thaw and this second look sees it. A real ANR is still stuck.
                mWorker.postDelayed(() -> verify(before, postedAt, true), RECOVERY_POLL_MS);
                return;
            }
            report(SystemClock.uptimeMillis() - postedAt);
            waitForRecovery(before, postedAt);
        } catch (Exception e) {
            MPLog.e(LOGTAG, "ANR check failed", e);
            check();
        }
    }

    // runs on the worker thread: one freeze yields one event
    private void waitForRecovery(long before, long postedAt) {
        if (!mRunning.get()) {
            return;
        }
        if (mTick.get() == before) {
            mWorker.postDelayed(() -> waitForRecovery(before, postedAt), RECOVERY_POLL_MS);
            return;
        }
        MPLog.v(LOGTAG, "main thread recovered after " + (SystemClock.uptimeMillis() - postedAt) + " ms");
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
