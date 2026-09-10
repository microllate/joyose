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
                        }
                    });
            XposedBridge.log(TAG + " hooked SocOptimizationHandlerVersion2.perfLockAcquire()");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " SocOptimizationV2 unavailable: " + e);
        }
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
