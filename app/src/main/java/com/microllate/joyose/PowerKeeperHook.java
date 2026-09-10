package com.microllate.joyose;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** PowerKeeper-side FPS policy bypass and Qualcomm performance diagnostics. */
public class PowerKeeperHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-PowerKeeper]";
    private static final String POWERKEEPER = "com.miui.powerkeeper";
    private static final int UNLOCK_FPS = 120;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!POWERKEEPER.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + " loaded: " + lpparam.processName);
        hookDisplayFrameSetting(lpparam.classLoader);
        hookQcomPerformance(lpparam.classLoader);
        hookBoostFramework();
        hookPeGameController(lpparam.classLoader);
        hookSystemTuning(lpparam.classLoader);
        hookSocOptimization(lpparam.classLoader);
        hookGpuSchedulePath(lpparam.classLoader);
        hookRemainingPerfPaths(lpparam.classLoader);
    }

    private static void hookDisplayFrameSetting(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.statemachine.DisplayFrameSetting", cl);
            hookFpsMethod(c, "setFpsAync", new Class<?>[]{String.class, int.class, int.class});
            hookFpsMethod(c, "setFpsAync", new Class<?>[]{String.class, int.class});
            XposedHelpers.findAndHookMethod(c, "setScreenEffect", String.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args.length < 3 || !(p.args[1] instanceof Integer)) return;
                            int fps = ((Integer) p.args[1]).intValue();
                            if (fps > 0 && fps <= 60) {
                                p.args[1] = UNLOCK_FPS;
                                XposedBridge.log(TAG + " setScreenEffect: " + p.args[0] + " " + fps + " -> " + UNLOCK_FPS + " cookie=" + p.args[2]);
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
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.g", cl);
            XposedHelpers.findAndHookMethod(c, "e", int.class, int[].class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " QcomBoost.e duration=" + p.args[0]
                            + " resources=" + formatIntArray((int[]) p.args[1]));
                    logCallerStack("QcomBoost.e caller");
                }
            });
            XposedHelpers.findAndHookMethod(c, "d", int.class, int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " QcomBoost.d hint=" + p.args[0]
                            + " duration=" + p.args[1] + " tpid=" + p.args[2]);
                }
            });
            XposedBridge.log(TAG + " hooked QcomBoost.e/d");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " QcomBoost unavailable: " + e);
        }
    }

    /** Hook the framework class with the bootstrap class loader, not PowerKeeper's app loader. */
    private static void hookBoostFramework() {
        try {
            Class<?> c = Class.forName("android.util.BoostFramework", false, null);
            int hooked = 0;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!"perfLockAcquire".equals(m.getName())) continue;
                Class<?>[] types = m.getParameterTypes();
                if (types.length == 2 && types[0] == int.class && types[1] == int[].class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            XposedBridge.log(TAG + " BoostFramework.perfLockAcquire duration=" + p.args[0]
                                    + " resources=" + formatIntArray((int[]) p.args[1]));
                            logCallerStack("BoostFramework.perfLockAcquire caller");
                        }
                    });
                    hooked++;
                }
            }
            XposedBridge.log(TAG + " hooked BoostFramework.perfLockAcquire overloads=" + hooked);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " BoostFramework bootstrap hook unavailable: " + e);
        }
    }

    private static void hookPeGameController(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PeGameController", cl);
            XposedHelpers.findAndHookMethod(c, "p", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " PeGameController.p() f514j=" + safeField(p.thisObject, "f514j")
                            + " f515k=" + safeField(p.thisObject, "f515k"));
                    logCallerStack("PeGameController.p() caller");
                }
            });
            XposedHelpers.findAndHookMethod(c, "q", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " PeGameController.q(String) raw=" + p.args[0]);
                    logCallerStack("PeGameController.q(String) caller");
                }
            });
            XposedBridge.log(TAG + " hooked PeGameController.p/q");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " PeGameController unavailable: " + e);
        }
    }

    private static void hookSystemTuning(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.statemachine.DynamicTurboPowerHandler", cl);
            XposedHelpers.findAndHookMethod(c, "systemTuning", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " systemTuning mSystemTuning=" + safeField(p.thisObject, "mSystemTuning")
                            + " mNeedRelease=" + safeField(p.thisObject, "mNeedRelease")
                            + " mArgs=" + safeField(p.thisObject, "mArgs"));
                }
            });
            XposedBridge.log(TAG + " hooked DynamicTurboPowerHandler.systemTuning()");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " DynamicTurboPowerHandler unavailable: " + e);
        }
    }

    private static void hookSocOptimization(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.statemachine.SocOptimizationHandlerVersion2", cl);
            XposedHelpers.findAndHookMethod(c, "perfLockAcquire", int.class, int.class, int[].class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            XposedBridge.log(TAG + " SocOptimizationV2.perfLockAcquire handle=" + p.args[0]
                                    + " arg=" + p.args[1] + " resources=" + formatIntArray((int[]) p.args[2])
                                    + " workType=" + p.args[3]);
                            logCallerStack("SocOptimizationV2 caller");
                        }
                    });
            XposedBridge.log(TAG + " hooked SocOptimizationHandlerVersion2.perfLockAcquire()");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " SocOptimizationV2 unavailable: " + e);
        }
    }

    /**
     * Trace the schedule/config path which contains gpu_level_high/medium/low in the
     * decompiled PowerKeeper. This is diagnostic only: no values are modified.
     */
    private static void hookGpuSchedulePath(ClassLoader cl) {
        hookClassMethods(cl, "com.miui.powerkeeper.perfengine.SchedConfig", "SchedConfig", false);
        hookClassMethods(cl, "com.miui.powerkeeper.perfengine.PeSchedController", "PeSchedController", true);
    }

    /** Close the remaining Java-side Qualcomm/Xring perf-lock routes without changing behavior. */
    private static void hookRemainingPerfPaths(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.statemachine.CpuDdrHandler", cl);
            java.lang.reflect.Method m = c.getDeclaredMethod("systemNocDDRLLCTuning", Class.forName("com.miui.powerkeeper.statemachine.ForegroundInfo", false, cl));
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " CpuDdrHandler.systemNocDDRLLCTuning() called");
                    logCallerStack("CpuDdrHandler caller");
                }
            });
            XposedBridge.log(TAG + " hooked CpuDdrHandler.systemNocDDRLLCTuning()");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " CpuDdrHandler path unavailable: " + e);
        }

        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.i", cl);
            int hooked = 0;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!"e".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " XRBoost.i.e args=" + formatObjects(p.args));
                        logCallerStack("XRBoost.i.e caller");
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " hooked XRBoost.i.e methods=" + hooked);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " XRBoost path unavailable: " + e);
        }
    }

    private static void hookClassMethods(ClassLoader cl, String className, String label, boolean logAll) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int hooked = 0;
            for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                final String sig = label + ".<init>" + java.util.Arrays.toString(ctor.getParameterTypes());
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " " + sig + " args=" + formatObjects(p.args));
                        if ("SchedConfig".equals(label)) logObjectFields(p.thisObject, label);
                    }
                });
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                boolean interesting = lower.contains("gpu") || lower.contains("sched") || lower.contains("level")
                        || lower.contains("config") || lower.contains("policy") || lower.contains("apply")
                        || lower.contains("load") || lower.contains("parse") || lower.contains("init");
                if (!logAll && !interesting) continue;
                final String sig = label + "." + name + java.util.Arrays.toString(m.getParameterTypes());
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " " + sig + " args=" + formatObjects(p.args));
                        if ("SchedConfig".equals(label)) logObjectFields(p.thisObject, label);
                        if ("PeSchedController".equals(label)) logCallerStack(label + "." + name + " caller");
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " hooked " + label + " methods=" + hooked);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " " + label + " trace unavailable: " + e);
        }
    }

    private static void logObjectFields(Object obj, String label) {
        try {
            StringBuilder sb = new StringBuilder(TAG).append(' ').append(label).append(" fields:");
            for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
                String n = f.getName();
                String lower = n.toLowerCase(java.util.Locale.ROOT);
                if (!lower.contains("gpu") && !lower.contains("level") && !lower.contains("sched") && !lower.contains("config") && !lower.contains("map")) continue;
                try {
                    f.setAccessible(true);
                    sb.append(" ").append(n).append('=').append(String.valueOf(f.get(obj)));
                } catch (Throwable ignored) {
                    sb.append(" ").append(n).append("=<blocked>");
                }
            }
            XposedBridge.log(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String formatObjects(Object[] values) {
        if (values == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            Object v = values[i];
            if (v instanceof int[]) sb.append(formatIntArray((int[]) v));
            else if (v instanceof long[]) sb.append(java.util.Arrays.toString((long[]) v));
            else if (v instanceof byte[]) sb.append(java.util.Arrays.toString((byte[]) v));
            else sb.append(String.valueOf(v));
        }
        return sb.append(']').toString();
    }

    private static String safeField(Object obj, String name) {
        try {
            Object value = XposedHelpers.getObjectField(obj, name);
            if (value instanceof int[]) return formatIntArray((int[]) value);
            return String.valueOf(value);
        } catch (Throwable e) {
            return "<unavailable:" + e.getClass().getSimpleName() + ">";
        }
    }

    private static void logCallerStack(String title) {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder(TAG).append(' ').append(title);
            int count = 0;
            for (StackTraceElement e : stack) {
                String cls = e.getClassName();
                if (cls.equals(Thread.class.getName()) || cls.startsWith("de.robv.android.xposed.")) continue;
                if (count++ >= 14) break;
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
                    int fps = ((Integer) p.args[1]).intValue();
                    if (fps > 0 && fps <= 60) {
                        p.args[1] = UNLOCK_FPS;
                        XposedBridge.log(TAG + " " + name + ": " + p.args[0] + " " + fps + " -> " + UNLOCK_FPS);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(c, name, args);
            XposedBridge.log(TAG + " hooked " + name);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " " + name + " unavailable: " + e);
        }
    }
}
