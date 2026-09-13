package com.microllate.joyose;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Observation-only GPU/frame monitor for the Douyin Android app. */
public class DailyGpuMonitorHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-DailyGPU]";
    private static final String LOG_TAG = "Joyose-DailyGPU";
    private static final String TARGET = "com.ss.android.ugc.aweme";
    private static final String GPU = "/sys/class/kgsl/kgsl-3d0";
    private static final long SAMPLE_MS = 500;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!TARGET.equals(p.packageName)) return;

        log("LOAD package=" + p.packageName + " process=" + p.processName
                + " uid=" + android.os.Process.myUid());

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    log("Application.onCreate process=" + p.processName);
                    startOnce();
                }
            });
        } catch (Throwable e) {
            log("Application.onCreate hook failed: " + e);
            new Handler(Looper.getMainLooper()).post(DailyGpuMonitorHook.this::startOnce);
        }
    }

    private void startOnce() {
        try {
            final Choreographer choreographer = Choreographer.getInstance();
            final long[] frames = {0};
            final long[] windowStart = {0};
            choreographer.postFrameCallback(new Choreographer.FrameCallback() {
                @Override public void doFrame(long frameTimeNanos) {
                    long now = System.nanoTime();
                    frames[0]++;
                    if (windowStart[0] == 0) windowStart[0] = now;
                    if (now - windowStart[0] >= 1_000_000_000L) {
                        double fps = frames[0] * 1_000_000_000.0 / (now - windowStart[0]);
                        log(String.format(Locale.US, "frame_fps=%.1f", fps));
                        frames[0] = 0;
                        windowStart[0] = now;
                    }
                    choreographer.postFrameCallback(this);
                }
            });

            Handler h = new Handler(Looper.getMainLooper());
            h.post(new Runnable() {
                @Override public void run() {
                    sampleGpu();
                    h.postDelayed(this, SAMPLE_MS);
                }
            });
            log("MONITOR_STARTED sample_ms=" + SAMPLE_MS);
        } catch (Throwable e) {
            log("start failed: " + e);
        }
    }

    private void sampleGpu() {
        String freq = readFirst(GPU + "/devfreq/cur_freq");
        String util = readFirst(GPU + "/gpu_busy_percentage");
        if (util == null) util = readFirst(GPU + "/gpu_busy_percent");
        log("gpu_freq=" + (freq == null ? "?" : freq)
                + "Hz gpu_util=" + (util == null ? "?" : util));
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
