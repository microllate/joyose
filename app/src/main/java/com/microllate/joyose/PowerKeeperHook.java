package com.microllate.joyose;

import android.os.SystemClock;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** PowerKeeper-side FPS policy bypass and Qualcomm performance diagnostics. */
public class PowerKeeperHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-PowerKeeper]";
    private static final String POWERKEEPER = "com.miui.powerkeeper";
    private static final String DOUYIN = "com.ss.android.ugc.aweme";
    private static final int UNLOCK_FPS = 120;
    private static final int TRACE_HINT = 4227;
    private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 5000L;

    // FPS overrides are logged once per distinct package/original-FPS combination
    // per PowerKeeper process, rather than once for every repeated framework call.
    private static final Set<String> LOGGED_FPS_OVERRIDES =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> LOGGED_STACK_TRACES =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ConcurrentHashMap<String, Long> LAST_DIAGNOSTIC_LOG =
            new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!POWERKEEPER.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + " loaded: " + lpparam.processName);
        hookDisplayFrameSetting(lpparam.classLoader);
        hookQcomPerformance(lpparam.classLoader);
        hookBoostFramework(lpparam.classLoader);
    }

    private static void hookDisplayFrameSetting(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.miui.powerkeeper.statemachine.DisplayFrameSetting", cl);

            hookFpsMethod(c, "setFpsAync", new Class<?>[]{String.class, int.class, int.class});
            hookFpsMethod(c, "setFpsAync", new Class<?>[]{String.class, int.class});

            XposedHelpers.findAndHookMethod(c, "setScreenEffect",
                    String.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args.length < 3 || !(p.args[1] instanceof Integer)) return;
                            String packageName = String.valueOf(p.args[0]);
                            int fps = ((Integer) p.args[1]).intValue();
                            if (DOUYIN.equals(packageName)) return;
                            if (fps > 0 && fps <= 60) {
                                p.args[1] = UNLOCK_FPS;
                                logFpsOverrideOnce("setScreenEffect:" + packageName + ":" + fps,
                                        TAG + " setScreenEffect: " + packageName + " "
                                                + fps + " -> " + UNLOCK_FPS
                                                + " cookie=" + p.args[2]);
                            }
                        }
                    });

            XposedBridge.log(TAG + " hooked DisplayFrameSetting FPS policy");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hook unavailable: " + e);
        }
    }

    private static void hookQcomPerformance(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.miui.powerkeeper.perfengine.g", cl);

            XposedHelpers.findAndHookMethod(c, "e", int.class, int[].class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args.length < 2 || !(p.args[1] instanceof int[])) return;
                            int duration = ((Integer) p.args[0]).intValue();
                            int[] resources = (int[]) p.args[1];
                            logDiagnosticRateLimited("QcomBoost.e:" + duration + ":"
                                            + formatIntArray(resources),
                                    TAG + " QcomBoost.e duration=" + duration
                                            + " resources=" + formatIntArray(resources));
                        }
                    });
            XposedBridge.log(TAG + " hooked QcomBoost.e");

            XposedHelpers.findAndHookMethod(c, "d", int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args.length < 3) return;
                            int hint = ((Integer) p.args[0]).intValue();
                            int duration = ((Integer) p.args[1]).intValue();
                            int tpid = ((Integer) p.args[2]).intValue();
                            String key = "QcomBoost.d:" + hint + ":" + duration + ":" + tpid;
                            logDiagnosticRateLimited(key, TAG + " QcomBoost.d hint="
                                    + hint + " duration=" + duration + " tpid=" + tpid);
                            if (hint == TRACE_HINT && LOGGED_STACK_TRACES.add("QcomBoost.d:" + hint)) {
                                logCallerStack("QcomBoost.d hint=4227 caller");
                            }
                        }
                    });
            XposedBridge.log(TAG + " hooked QcomBoost.d");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " QcomBoost diagnostics unavailable: " + e);
        }
    }

    /**
     * PowerKeeper's actual Qualcomm requests are ultimately passed to the
     * framework BoostFramework. Hook the final API as a second observation
     * point because an obfuscated vendor wrapper may bypass our g.e() hook.
     * Diagnostic only: no performance value is modified.
     */
    private static void hookBoostFramework(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("android.util.BoostFramework", cl);

            XposedHelpers.findAndHookMethod(c, "perfLockAcquire", int.class, int[].class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args.length < 2 || !(p.args[1] instanceof int[])) return;
                            int duration = ((Integer) p.args[0]).intValue();
                            int[] resources = (int[]) p.args[1];
                            logDiagnosticRateLimited("BoostFramework.perfLockAcquire:"
                                            + duration + ":" + formatIntArray(resources),
                                    TAG + " BoostFramework.perfLockAcquire duration="
                                            + duration + " resources=" + formatIntArray(resources));
                        }
                    });
            XposedBridge.log(TAG + " hooked BoostFramework.perfLockAcquire");

            XposedHelpers.findAndHookMethod(c, "perfHint",
                    int.class, String.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            int hint = ((Integer) p.args[0]).intValue();
                            String userData = String.valueOf(p.args[1]);
                            int duration = ((Integer) p.args[2]).intValue();
                            int tpid = ((Integer) p.args[3]).intValue();
                            String key = "BoostFramework.perfHint:" + hint + ":" + userData
                                    + ":" + duration + ":" + tpid;
                            logDiagnosticRateLimited(key, TAG + " BoostFramework.perfHint hint="
                                    + hint + " userData=" + userData
                                    + " duration=" + duration + " tpid=" + tpid);
                            if (hint == TRACE_HINT
                                    && LOGGED_STACK_TRACES.add("BoostFramework.perfHint:" + hint)) {
                                logCallerStack("BoostFramework.perfHint hint=4227 caller");
                            }
                        }
                    });
            XposedBridge.log(TAG + " hooked BoostFramework.perfHint");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " BoostFramework diagnostics unavailable: " + e);
        }
    }

    private static void logCallerStack(String title) {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder(TAG).append(' ').append(title);
            int count = 0;
            for (StackTraceElement e : stack) {
                String cls = e.getClassName();
                if (cls.equals(Thread.class.getName()) || cls.startsWith("de.robv.android.xposed.")) {
                    continue;
                }
                if (count++ >= 18) break;
                sb.append("\n  at ").append(e);
            }
            XposedBridge.log(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String formatIntArray(int[] values) {
        if (values == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    private static void hookFpsMethod(Class<?> c, String name, Class<?>[] parameterTypes) {
        try {
            Object[] args = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
            args[parameterTypes.length] = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 2 || !(p.args[1] instanceof Integer)) return;
                    String packageName = String.valueOf(p.args[0]);
                    if (DOUYIN.equals(packageName)) return;
                    int fps = ((Integer) p.args[1]).intValue();
                    if (fps > 0 && fps <= 60) {
                        p.args[1] = UNLOCK_FPS;
                        logFpsOverrideOnce(name + ":" + packageName + ":" + fps,
                                TAG + " " + name + ": " + packageName
                                        + " " + fps + " -> " + UNLOCK_FPS);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(c, name, args);
            XposedBridge.log(TAG + " hooked " + name);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " " + name + " unavailable: " + e);
        }
    }

    private static void logFpsOverrideOnce(String key, String message) {
        if (LOGGED_FPS_OVERRIDES.add(key)) {
            XposedBridge.log(message);
        }
    }

    /** Log an identical diagnostic at most once per interval. */
    private static void logDiagnosticRateLimited(String key, String message) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LAST_DIAGNOSTIC_LOG) {
            Long last = LAST_DIAGNOSTIC_LOG.get(key);
            if (last == null || now - last.longValue() >= DIAGNOSTIC_LOG_INTERVAL_MS) {
                LAST_DIAGNOSTIC_LOG.put(key, Long.valueOf(now));
                XposedBridge.log(message);
            }
        }
    }
}
