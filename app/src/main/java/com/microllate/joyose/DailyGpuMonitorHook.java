package com.microllate.joyose;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.Choreographer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Observation-only workload/frame monitor inspired by fas-rs.
 * It deliberately does not touch GPU/CPU governors or performance controls.
 */
public class DailyGpuMonitorHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-FASRS]";
    private static final String LOG_TAG = "Joyose-FASRS";
    private static final String TARGET = "com.ss.android.ugc.aweme";
    private static final long SAMPLE_MS = 300;
    private static final long THREAD_REFRESH_MS = 1000;
    private static volatile boolean started;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!TARGET.equals(p.packageName)) return;
        log("LOAD package=" + p.packageName + " process=" + p.processName);
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    startOnce();
                }
            });
        } catch (Throwable e) {
            log("Application.onCreate hook failed: " + e);
            new Handler(Looper.getMainLooper()).post(DailyGpuMonitorHook.this::startOnce);
        }
    }

    private void startOnce() {
        if (started) return;
        started = true;
        try {
            final int pid = android.os.Process.myPid();
            final Handler handler = new Handler(Looper.getMainLooper());
            final ThreadUsageMonitor cpu = new ThreadUsageMonitor(pid);

            final long[] frames = {0};
            final long[] windowStart = {0};
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                @Override public void doFrame(long frameTimeNanos) {
                    long now = System.nanoTime();
                    frames[0]++;
                    if (windowStart[0] == 0) windowStart[0] = now;
                    if (now - windowStart[0] >= 1_000_000_000L) {
                        double fps = frames[0] * 1_000_000_000.0 / (now - windowStart[0]);
                        log(String.format(java.util.Locale.US, "frame_fps=%.1f", fps));
                        frames[0] = 0;
                        windowStart[0] = now;
                    }
                    Choreographer.getInstance().postFrameCallback(this);
                }
            });

            handler.post(new Runnable() {
                @Override public void run() {
                    double cpuUsage = cpu.update();
                    if (cpuUsage >= 0) {
                        log(String.format(java.util.Locale.US,
                                "pid=%d top_thread_cpu=%.1f%% top_threads=%d",
                                pid, cpuUsage * 100.0, cpu.topThreadCount()));
                    }
                    handler.postDelayed(this, SAMPLE_MS);
                }
            });
            log("MONITOR_STARTED mode=fas-rs-inspired sample_ms=" + SAMPLE_MS
                    + " thread_refresh_ms=" + THREAD_REFRESH_MS);
        } catch (Throwable e) {
            log("start failed: " + e);
        }
    }

    private static final class ThreadUsageMonitor {
        private final int pid;
        private final Map<Integer, Sample> trackers = new HashMap<>();
        private long lastUpdateNs;
        private long lastThreadRefreshNs;
        private int topCount;

        ThreadUsageMonitor(int pid) {
            this.pid = pid;
            this.lastUpdateNs = System.nanoTime();
        }

        double update() {
            long now = System.nanoTime();
            if (now - lastUpdateNs < SAMPLE_MS * 1_000_000L) return -1;
            double elapsedSec = (now - lastUpdateNs) / 1_000_000_000.0;
            lastUpdateNs = now;

            if (now - lastThreadRefreshNs >= THREAD_REFRESH_MS * 1_000_000L) {
                refreshThreads(now);
            }

            long ticksPerSec = getClockTicks();
            if (ticksPerSec <= 0) return -1;
            double max = 0;
            for (Sample s : trackers.values()) {
                long ticks = readThreadCpuTicks(pid, s.tid);
                if (ticks < 0) continue;
                long delta = ticks - s.lastTicks;
                if (delta < 0) continue;
                s.lastTicks = ticks;
                double usage = delta / (elapsedSec * ticksPerSec);
                if (usage > max) max = usage;
            }
            return max;
        }

        int topThreadCount() { return topCount; }

        private void refreshThreads(long now) {
            File task = new File("/proc/" + pid + "/task");
            File[] files = task.listFiles();
            if (files == null) return;

            Map<Integer, Long> current = new HashMap<>();
            for (File f : files) {
                try {
                    int tid = Integer.parseInt(f.getName());
                    long ticks = readThreadCpuTicks(pid, tid);
                    if (ticks >= 0) current.put(tid, ticks);
                } catch (Throwable ignored) {
                }
            }

            List<Map.Entry<Integer, Long>> ranked = new ArrayList<>(current.entrySet());
            ranked.sort(Map.Entry.<Integer, Long>comparingByValue().reversed());
            if (ranked.size() > 8) ranked = ranked.subList(0, 8);

            Map<Integer, Sample> next = new HashMap<>();
            for (Map.Entry<Integer, Long> entry : ranked) {
                Sample old = trackers.get(entry.getKey());
                long baseline = old == null ? entry.getValue() : old.lastTicks;
                next.put(entry.getKey(), new Sample(entry.getKey(), baseline));
            }
            trackers.clear();
            trackers.putAll(next);
            topCount = trackers.size();
            lastThreadRefreshNs = now;
        }

        private static long getClockTicks() {
            try {
                return Os.sysconf(OsConstants._SC_CLK_TCK);
            } catch (Throwable ignored) {
                return 100;
            }
        }

        private static long readThreadCpuTicks(int pid, int tid) {
            String line = readFirst("/proc/" + pid + "/task/" + tid + "/stat");
            if (line == null) return -1;
            try {
                int close = line.lastIndexOf(')');
                if (close < 0 || close + 2 >= line.length()) return -1;
                String[] parts = line.substring(close + 2).trim().split("\\s+");
                // After the comm field, index 11 = utime and 12 = stime.
                if (parts.length <= 12) return -1;
                long utime = Long.parseLong(parts[11]);
                long stime = Long.parseLong(parts[12]);
                return utime + stime;
            } catch (Throwable ignored) {
                return -1;
            }
        }
    }

    private static final class Sample {
        final int tid;
        long lastTicks;
        Sample(int tid, long lastTicks) {
            this.tid = tid;
            this.lastTicks = lastTicks;
        }
    }

    private static String readFirst(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return null;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                return r.readLine();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void log(String message) {
        String line = TAG + " " + message;
        XposedBridge.log(line);
        Log.i(LOG_TAG, message);
    }
}
