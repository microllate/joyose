package com.microllate.joyose;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PowerKeeper-side FPS bypass.
 *
 * DisplayFrameSetting is the PowerKeeper component that applies the FPS policy.
 * Its public setFpsAync() entry and its private setScreenEffect() sink both
 * receive the package name and target FPS. We raise a 60 FPS request to 120 FPS
 * before PowerKeeper can apply it.
 */
public class PowerKeeperHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-PowerKeeper]";
    private static final String POWERKEEPER = "com.miui.powerkeeper";
    private static final int UNLOCK_FPS = 120;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!POWERKEEPER.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + " loaded: " + lpparam.processName);
        hookDisplayFrameSetting(lpparam.classLoader);
    }

    private static void hookDisplayFrameSetting(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.miui.powerkeeper.statemachine.DisplayFrameSetting", cl);

            // Public API used by PowerKeeper/Joyose callers.
            hookFpsMethod(c, "setFpsAync", String.class, int.class, int.class);
            hookFpsMethod(c, "setFpsAync", String.class, int.class);

            // Final internal sink. This catches direct internal policy changes too.
            XposedHelpers.findAndHookMethod(c, "setScreenEffect",
                    String.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
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

    private static void hookFpsMethod(Class<?> c, String name, Class<?>... parameterTypes) {
        try {
            XposedHelpers.findAndHookMethod(c, name, parameterTypes, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 2 || !(p.args[1] instanceof Integer)) return;
                    int fps = ((Integer) p.args[1]).intValue();
                    if (fps > 0 && fps <= 60) {
                        p.args[1] = UNLOCK_FPS;
                        XposedBridge.log(TAG + " " + name + ": "
                                + p.args[0] + " " + fps + " -> " + UNLOCK_FPS);
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log(TAG + " " + name + " unavailable: " + e);
        }
    }
}
