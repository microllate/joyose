package com.microllate.joyose;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

/**
 * Narrow diagnostic hook for PowerKeeper's non-game performance paths.
 * No values are changed and no performance request is blocked.
 */
public class PerfPathTraceHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-PerfPath]";
    private static final String PK = "com.miui.powerkeeper";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if (!PK.equals(p.packageName)) return;
        ClassLoader cl = p.classLoader;
        hookQcomBoost(cl);
        hookCpuDdr(cl);
        hookNoiseCpu(cl);
        hookForeground(cl);
        hookSchedWrite(cl);
    }

    private static void hookQcomBoost(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.g", cl);
            hookNamed(c, "e", "QcomBoost.e", true);
            hookNamed(c, "f", "QcomBoost.f", true);
            XposedBridge.log(TAG + " hooked perfengine.g");
        } catch (Throwable e) { XposedBridge.log(TAG + " QcomBoost unavailable: " + e); }
    }

    private static void hookCpuDdr(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.statemachine.CpuDdrHandler", cl);
            Class<?> fg = Class.forName("miui.process.ForegroundInfo", false, cl);
            Method m = c.getDeclaredMethod("systemNocDDRLLCTuning", fg);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    log(TAG + " CpuDdrHandler.systemNocDDRLLCTuning args=" + format(p.args));
                    stack("CpuDdrHandler");
                }
            });
            XposedBridge.log(TAG + " hooked CpuDdrHandler with miui.process.ForegroundInfo");
        } catch (Throwable e) { XposedBridge.log(TAG + " CpuDdr unavailable: " + e); }
    }

    private static void hookNoiseCpu(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.statemachine.NoiseCpuHandler", cl);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("perf") && !name.contains("boost")) continue;
                final Method target = m;
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        log(TAG + " NoiseCpu." + target.getName() + Arrays.toString(target.getParameterTypes()) + " args=" + format(p.args));
                        stack("NoiseCpu." + target.getName());
                    }
                });
                n++;
            }
            XposedBridge.log(TAG + " hooked NoiseCpu perf/boost methods=" + n);
        } catch (Throwable e) { XposedBridge.log(TAG + " NoiseCpu unavailable: " + e); }
    }

    private static void hookForeground(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PeGameController", cl);
            Class<?> fg = Class.forName("miui.process.ForegroundInfo", false, cl);
            Method m = c.getDeclaredMethod("a", fg);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    log(TAG + " PeGameController.a(ForegroundInfo)=" + format(p.args));
                    stack("PeGameController.a foreground");
                }
            });
            XposedBridge.log(TAG + " hooked universal foreground callback");
        } catch (Throwable e) { XposedBridge.log(TAG + " foreground path unavailable: " + e); }
    }

    private static void hookSchedWrite(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PeSchedHandler", cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("l")) continue;
                final Method target = m;
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        log(TAG + " PeSchedHandler.l WRITE-CANDIDATE args=" + format(p.args));
                        stack("PeSchedHandler.l");
                    }
                });
            }
            XposedBridge.log(TAG + " hooked PeSchedHandler.l");
        } catch (Throwable e) { XposedBridge.log(TAG + " sched write path unavailable: " + e); }
    }

    private static void hookNamed(Class<?> c, String name, String label, boolean stack) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            final Method target = m;
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    log(TAG + " " + label + Arrays.toString(target.getParameterTypes()) + " args=" + format(p.args));
                    if (stack) stack(label);
                }
            });
            n++;
        }
        XposedBridge.log(TAG + " hooked " + label + " methods=" + n);
    }

    private static String format(Object[] a) {
        if (a == null) return "null";
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) s.append(',');
            Object v = a[i];
            if (v instanceof int[]) s.append(Arrays.toString((int[]) v));
            else if (v instanceof long[]) s.append(Arrays.toString((long[]) v));
            else s.append(String.valueOf(v));
        }
        return s.append(']').toString();
    }

    private static void log(String s) { XposedBridge.log(s); }

    private static void stack(String title) {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder s = new StringBuilder(TAG + " " + title + " caller");
            int n = 0;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.equals(Thread.class.getName()) || c.startsWith("de.robv.android.xposed.")) continue;
                if (n++ >= 10) break;
                s.append("\n  at ").append(e);
            }
            XposedBridge.log(s.toString());
        } catch (Throwable ignored) { }
    }
}
