package com.mixpanel.example.hello;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Streams this process's logcat output and forwards lines that match a filter.
 *
 * Since Android 4.1 an app can read its own log lines without READ_LOGS, which is all we need to
 * mirror the Mixpanel SDK's debug logging ("MixpanelAPI.*" tags) inside the UI.
 */
final class LogcatReader {

    interface Listener {
        void onLine(String line);
    }

    private static final String LOGTAG = "LogcatReader";

    private final Thread mThread;
    private volatile boolean mRunning = true;
    private volatile Process mProcess;

    LogcatReader(final String filter, final Listener listener) {
        mThread = new Thread(() -> {
            try {
                // Drop whatever is already buffered so the panel only shows this launch.
                Runtime.getRuntime().exec(new String[] {"logcat", "-c"}).waitFor();
                mProcess = Runtime.getRuntime().exec(new String[] {"logcat", "-v", "time"});
                final BufferedReader reader =
                        new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
                String line;
                while (mRunning && (line = reader.readLine()) != null) {
                    if (line.contains(filter)) {
                        listener.onLine(line);
                    }
                }
            } catch (IOException | InterruptedException e) {
                if (mRunning) {
                    Log.w(LOGTAG, "logcat stream ended", e);
                }
            }
        }, "logcat-reader");
        mThread.setDaemon(true);
    }

    void start() {
        mThread.start();
    }

    void stop() {
        mRunning = false;
        final Process process = mProcess;
        if (process != null) {
            process.destroy();
        }
    }
}
