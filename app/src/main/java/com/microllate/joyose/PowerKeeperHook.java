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
                            int fps = ((Integer) p.args[1]).intValue();
                            if (fps > 0 && fps <= 60) {
                                p.args[1] = UNLOCK_FPS;
                                XposedBridge.log(TAG + " setScreenEffect: "
                                        + p.args[0] + " " + fps + " -> " + UNLOCK_FPS
                                        + " cookie=" + p.args[2]);
                            }
                        }
                    });

            XposedBridge.log(TAG + " hooked DisplayFrameSetting FPS policy");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hook unavailable: " + e);
        }
    }

    /**
     * On this Qualcomm device PeGameController eventually calls QcomBoost.e(),
     * which passes the actual resource array to android.util.BoostFramework.
     * We record that array before it reaches the vendor performance layer.
     * Nothing is modified in this diagnostic build.
     */
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
                            XposedBridge.log(TAG + " QcomBoost.e duration="
                                    + duration + " resources=" + formatIntArray(resources));
                        }
                    });
            XposedBridge.log(TAG + " hooked QcomBoost.e");

            XposedHelpers.findAndHookMethod(c, "d", int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args.length < 3) return;
                            XposedBridge.log(TAG + " QcomBoost.d hint="
                                    + p.args[0] + " duration=" + p.args[1]
                                    + " tpid=" + p.args[2]);
                        }
                    });
            XposedBridge.log(TAG + " hooked QcomBoost.d");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " QcomBoost diagnostics unavailable: " + e);
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
                        XposedBridge.log(TAG + " " + name + ": "
                                + p.args[0] + " " + fps + " -> " + UNLOCK_FPS);
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
