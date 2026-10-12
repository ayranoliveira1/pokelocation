package com.pokelocation.app;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Executor de I/O único do processo (1 thread "pl-io", lazy, morre após 30 s ocioso) + Handler da main. */
public final class Io {
    private static final String TAG = "Io";

    public interface Callback<T> {
        void onResult(T value);
        default void onError(Throwable t) {}
    }

    public static final class Task {
        private volatile boolean cancelled;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    private static ThreadPoolExecutor sExec;
    private static Handler sMain;

    private Io() {}

    public static synchronized ExecutorService executor() {
        if (sExec == null) {
            sExec = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "pl-io"));
            sExec.allowCoreThreadTimeOut(true);
        }
        return sExec;
    }

    public static synchronized Handler main() {
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
        return sMain;
    }

    /** Roda work no Io e entrega o resultado na main; não entrega se cancelado. */
    public static <T> Task run(Callable<T> work, Callback<T> cb) {
        Task t = new Task();
        executor().execute(() -> {
            if (t.cancelled) return;
            try {
                T v = work.call();
                main().post(() -> { if (!t.cancelled && cb != null) cb.onResult(v); });
            } catch (Throwable e) {
                Log.w(TAG, "task failed", e);
                main().post(() -> { if (!t.cancelled && cb != null) cb.onError(e); });
            }
        });
        return t;
    }

    public static Task run(Runnable work) {
        Task t = new Task();
        executor().execute(() -> {
            if (t.cancelled) return;
            try {
                work.run();
            } catch (Throwable e) {
                Log.w(TAG, "task failed", e);
            }
        });
        return t;
    }
}
