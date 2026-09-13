package com.microllate.joyose;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Observation-only GPU monitor running inside PowerKeeper (UID 1000). */
public class PowerKeeperGpuMonitorHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-PK-GPU]";
    private static final String TARGET = "com.miui.powerkeeper";
    private static final String GPU = "/sys/class/kgsl/kgsl-3d0";
    private static final long SAMPLE_MS = 500;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!TARGET.equals(p.packageName)) return;
        XposedBridge.log(TAG + " loaded uid=" + android.os.Process.myUid()
                + " process=" + p.processName);
        new Handler(Looper.getMainLooper()).post(this::start);
    }

    private void start() {
        try {
            Handler h = new Handler(Looper.getMainLooper());
            h.post(new Runnable() {
                @Override public void run() {
                    sample();
                    h.postDelayed(this, SAMPLE_MS);
                }
            });
            XposedBridge.log(TAG + " monitor started sample_ms=" + SAMPLE_MS);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " start failed: " + e);
        }
    }

    private static void sample() {
        String freq = readFirst(GPU + "/devfreq/cur_freq");
        String util = readFirst(GPU + "/gpu_busy_percentage");
        if (util == null) util = readFirst(GPU + "/gpu_busy_percent");
        String governor = readFirst(GPU + "/devfreq/governor");
        String min = readFirst(GPU + "/devfreq/min_freq");
        String max = readFirst(GPU + "/devfreq/max_freq");
        XposedBridge.log(TAG + " freq=" + value(freq)
                + "Hz util=" + value(util)
                + " governor=" + value(governor)
                + " min=" + value(min)
                + " max=" + value(max));
    }

    private static String value(String s) {
        return s == null ? "?" : s.trim();
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
}
