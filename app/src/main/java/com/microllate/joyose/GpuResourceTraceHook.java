package com.microllate.joyose;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Diagnostic-only trace for Qualcomm GPU perf resource 0x42804000.
 * No performance values are modified.
 */
public class GpuResourceTraceHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-GPU-RES]";
    private static final String PK = "com.miui.powerkeeper";
    private static final int GPU_PWR_LVL = 0x42804000;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PK.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + " loaded: " + lpparam.processName);
        hookBoostFramework();
        hookQcomBoost(lpparam.classLoader);
        hookSocOptimization(lpparam.classLoader);
    }

    private static void hookBoostFramework() {
        try {
            Class<?> c = Class.forName("android.util.BoostFramework", false, null);
            int count = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!"perfLockAcquire".equals(m.getName())) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length != 2 || t[0] != int.class || t[1] != int[].class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        int[] resources = (int[]) p.args[1];
                        trace("BoostFramework.perfLockAcquire", (Integer) p.args[0], resources);
                    }
                });
                count++;
            }
            XposedBridge.log(TAG + " hooked BoostFramework.perfLockAcquire=" + count);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " BoostFramework hook failed: " + e);
        }
    }

    private static void hookQcomBoost(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.g", cl);
            XposedHelpers.findAndHookMethod(c, "e", int.class, int[].class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    trace("QcomBoost.e", (Integer) p.args[0], (int[]) p.args[1]);
                }
            });
            XposedBridge.log(TAG + " hooked QcomBoost.e");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " QcomBoost hook failed: " + e);
        }
    }

    private static void hookSocOptimization(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.miui.powerkeeper.statemachine.SocOptimizationHandlerVersion2", cl);
            XposedHelpers.findAndHookMethod(c, "perfLockAcquire",
                    int.class, int.class, int[].class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            trace("SocOptimizationV2.perfLockAcquire",
                                    (Integer) p.args[1], (int[]) p.args[2]);
                        }
                    });
            XposedBridge.log(TAG + " hooked SocOptimizationV2.perfLockAcquire");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " SocOptimization hook failed: " + e);
        }
    }

    private static void trace(String source, int durationOrArg, int[] resources) {
        if (resources == null) return;
        for (int i = 0; i + 1 < resources.length; i += 2) {
            if (resources[i] != GPU_PWR_LVL) continue;
            int value = resources[i + 1];
            XposedBridge.log(TAG + " GPU_PWR_LVL source=" + source
                    + " resource=0x" + Integer.toHexString(GPU_PWR_LVL)
                    + " value=" + value
                    + " durationOrArg=" + durationOrArg
                    + " pairIndex=" + i);
            logStack();
        }
    }

    private static void logStack() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder(TAG).append(" caller:");
            int n = 0;
            for (StackTraceElement e : stack) {
                String cls = e.getClassName();
                if (cls.equals(Thread.class.getName()) || cls.startsWith("de.robv.android.xposed.")) continue;
                if (n++ >= 12) break;
                sb.append("\n  at ").append(e.toString());
            }
            XposedBridge.log(sb.toString());
        } catch (Throwable ignored) {
        }
    }
}
