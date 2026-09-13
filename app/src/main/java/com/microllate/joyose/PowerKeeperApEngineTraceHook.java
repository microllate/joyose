package com.microllate.joyose;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Observation-only probe for Java bridges around Xiaomi/Qualcomm AP Engine GPU
 * statistics. It never changes a result or writes a performance node.
 */
public class PowerKeeperApEngineTraceHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-APGPU]";
    private static final String PK = "com.miui.powerkeeper";
    private static final Set<String> HOOKED = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if (!PK.equals(p.packageName)) return;
        XposedBridge.log(TAG + " LOAD process=" + p.processName);
        scan(p.classLoader);
    }

    private static void scan(ClassLoader cl) {
        String[] apkCandidates = {
                "/system_ext/priv-app/PowerKeeper/PowerKeeper.apk",
                "/system/priv-app/PowerKeeper/PowerKeeper.apk",
                "/product/priv-app/PowerKeeper/PowerKeeper.apk"
        };
        for (String apk : apkCandidates) {
            try {
                DexFile dex = new DexFile(apk);
                int candidates = 0;
                java.util.Enumeration<String> e = dex.entries();
                while (e.hasMoreElements()) {
                    String name = e.nextElement();
                    if (!isCandidate(name)) continue;
                    candidates++;
                    try {
                        Class<?> c = Class.forName(name, false, cl);
                        hookMethods(c);
                    } catch (Throwable ignored) {
                    }
                }
                dex.close();
                XposedBridge.log(TAG + " SCAN apk=" + apk
                        + " candidates=" + candidates + " hooked=" + HOOKED.size());
                if (candidates > 0) return;
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + " SCAN no matching AP Engine Java bridge classes");
    }

    private static boolean isCandidate(String n) {
        return n.contains("GpuMeter") || n.contains("APDataManager")
                || n.contains("APMetaMeter") || n.contains("AdaptiveEngine")
                || n.contains("PredictiveEngine");
    }

    private static void hookMethods(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            String n = m.getName();
            if (!(n.equals("getGpuUsage") || n.equals("getGpuHeadRoom")
                    || n.equals("getAvlGpuFreqs") || n.equals("collectGPUStats"))) continue;
            String key = c.getName() + "#" + m.toGenericString();
            if (!HOOKED.add(key)) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object result = p.getResult();
                            XposedBridge.log(TAG + " " + c.getName() + "." + n
                                    + " result=" + format(result));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                XposedBridge.log(TAG + " HOOK " + key);
            } catch (Throwable t) {
                HOOKED.remove(key);
            }
        }
    }

    private static String format(Object value) {
        if (value == null) return "null";
        if (value instanceof int[]) return java.util.Arrays.toString((int[]) value);
        if (value instanceof long[]) return java.util.Arrays.toString((long[]) value);
        if (value instanceof float[]) return java.util.Arrays.toString((float[]) value);
        if (value instanceof double[]) return java.util.Arrays.toString((double[]) value);
        if (value.getClass().isArray()) return value.getClass().getName();
        return String.valueOf(value);
    }
}
