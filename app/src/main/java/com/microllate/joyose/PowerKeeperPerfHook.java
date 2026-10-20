package com.microllate.joyose;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Guarded Qualcomm performance hook for com.miui.powerkeeper.
 * Blocks request-side perfHint/perfLockAcquire calls; release paths stay intact.
 */
public final class PowerKeeperPerfHook {
    private static final String TAG = "[Joyose-PowerKeeperPerf]";
    private static final String TARGET = "com.miui.powerkeeper.perfengine.g";

    private PowerKeeperPerfHook() {}

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) return;
        try {
            Class<?> cls = XposedHelpers.findClass(TARGET, classLoader);
            int installed = 0;
            for (Method method : cls.getDeclaredMethods()) {
                String name = method.getName();
                Class<?>[] p = method.getParameterTypes();
                if ("d".equals(name) && p.length == 3
                        && p[0] == int.class && p[1] == int.class && p[2] == int.class) {
                    hook(method);
                    installed++;
                } else if ("e".equals(name) && p.length == 2
                        && p[0] == int.class && p[1] == int[].class) {
                    hook(method);
                    installed++;
                }
            }
            XposedBridge.log(TAG + " installed=" + installed);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " install failed: " + t);
        }
    }

    private static void hook(Method method) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(zeroValue(method.getReturnType()));
            }
        });
    }

    private static Object zeroValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return (char) 0;
        return null;
    }
}
