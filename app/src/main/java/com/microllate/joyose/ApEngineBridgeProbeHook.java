package com.microllate.joyose;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Observation-only AP Engine bridge probe.
 *
 * This does not change any AP Engine action. It watches classes as they are
 * loaded by PowerKeeper and records AP/engine/GPU classes plus native Java
 * bridge methods. If a Java wrapper around libapengine.so exists, selected
 * methods are hooked so their arguments/return values can be correlated with
 * the already-confirmed GPU resource 0x42804000.
 */
public final class ApEngineBridgeProbeHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-APBRIDGE]";
    private static final String PK = "com.miui.powerkeeper";
    private static final String[] METHOD_NAMES = {
            "applyAction", "updateApplyActionQueue", "selectGpuBoostVal",
            "getGpuUsage", "getGpuHeadRoom", "collectGPUStats", "getAvlGpuFreqs"
    };
    private static final String[] CLASS_HINTS = {
            "apengine", "ap_engine", "adaptiveengine", "adaptive_engine",
            "predictiveengine", "predictive_engine", "apaction", "apdata",
            "gpumeter", "apmetameter"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PK.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + " loaded process=" + lpparam.processName);
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Object result = p.getResult();
                            if (!(result instanceof Class)) return;
                            inspect((Class<?>) result);
                        }
                    });
            XposedBridge.log(TAG + " hooked ClassLoader.loadClass");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ClassLoader hook failed=" + t);
        }
    }

    private static void inspect(Class<?> c) {
        String name = c.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean hinted = false;
        for (String h : CLASS_HINTS) {
            if (lower.contains(h)) { hinted = true; break; }
        }
        if (!hinted) return;

        XposedBridge.log(TAG + " CLASS " + name);
        Method[] methods;
        try {
            methods = c.getDeclaredMethods();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " methods failed class=" + name + " err=" + t);
            return;
        }
        for (Method m : methods) {
            String mn = m.getName();
            boolean interesting = false;
            for (String target : METHOD_NAMES) {
                if (target.equals(mn)) { interesting = true; break; }
            }
            if (!interesting) continue;
            XposedBridge.log(TAG + " METHOD " + m + " native=" + Modifier.isNative(m.getModifiers()));
            hookMethod(m);
        }
    }

    private static void hookMethod(Method m) {
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " CALL " + m.getDeclaringClass().getName() + "." + m.getName()
                            + " args=" + args(p.args));
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object result = null;
                    try { result = p.getResult(); } catch (Throwable ignored) { }
                    XposedBridge.log(TAG + " RET " + m.getName() + "=" + String.valueOf(result));
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook failed method=" + m + " err=" + t);
        }
    }

    private static String args(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object a = args[i];
            if (a instanceof int[]) {
                int[] x = (int[]) a;
                sb.append("int[");
                for (int j = 0; j < x.length && j < 32; j++) {
                    if (j > 0) sb.append(',');
                    sb.append(x[j]);
                }
                if (x.length > 32) sb.append("...");
                sb.append(']');
            } else {
                sb.append(String.valueOf(a));
            }
        }
        return sb.append(']').toString();
    }
}
