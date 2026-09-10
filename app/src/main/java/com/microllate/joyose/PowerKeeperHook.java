package com.microllate.joyose;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** PowerKeeper-side FPS policy bypass and performance diagnostics. */
public class PowerKeeperHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-PowerKeeper]";
    private static final String POWERKEEPER = "com.miui.powerkeeper";
    private static final int UNLOCK_FPS = 120;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!POWERKEEPER.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + " loaded: " + lpparam.processName);
        hookDisplayFrameSetting(lpparam.classLoader);
        hookQcomPerformanceCommands(lpparam.classLoader);
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
     * Qualcomm devices use PeGameController.q(String) for perflock commands.
     * We only record the raw command here; no performance command is changed yet.
     * This lets us identify the exact CPU/GPU/DDR resources before selectively
     * bypassing any GPU policy that conflicts with a user-owned KonaBass table.
     */
    private static void hookQcomPerformanceCommands(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.miui.powerkeeper.perfengine.PeGameController", cl);

            hookPerfCommand(c, "q");
            hookPerfCommand(c, "p");

            XposedBridge.log(TAG + " hooked Qcom performance command diagnostics");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Qcom diagnostics unavailable: " + e);
        }
    }

    private static void hookPerfCommand(Class<?> c, String name) {
        try {
            XposedHelpers.findAndHookMethod(c, name, String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 1 || !(p.args[0] instanceof String)) return;
                    String cmd = (String) p.args[0];
                    if (cmd.startsWith("0x")) {
                        XposedBridge.log(TAG + " " + name + " perflock: " + cmd);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked PeGameController." + name);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " PeGameController." + name + " unavailable: " + e);
        }
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
