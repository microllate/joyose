package com.microllate.joyose;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Framework-side escape hatch for MIUI/HyperOS refresh-rate votes.
 *
 * PowerKeeper can inject a 60 FPS render-rate vote into DisplayModeDirector while
 * the physical panel still supports 120 Hz.  Hooking the final desired display
 * specs is deliberately less dependent on PowerKeeper's obfuscated internals.
 */
public class FrameworkHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-Framework]";
    private static final float UNLOCK_MAX_FPS = 120.0f;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // system_server is exposed as "android" by modern LSPosed. Some older
        // configurations expose the process/package as "system_server".
        if (!"android".equals(lpparam.packageName)
                && !"system_server".equals(lpparam.packageName)) {
            return;
        }

        hookDisplayModeDirector(lpparam.classLoader,
                "com.android.server.display.mode.DisplayModeDirector");
        hookDisplayModeDirector(lpparam.classLoader,
                "com.android.server.display.DisplayModeDirector");
    }

    private static void hookDisplayModeDirector(ClassLoader cl, String className) {
        try {
            Class<?> director = XposedHelpers.findClass(className, cl);
            XposedHelpers.findAndHookMethod(director, "getDesiredDisplayModeSpecs", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object result = p.getResult();
                            if (result == null) return;

                            String before = String.valueOf(result);
                            int changed = unlockRefreshRateRanges(result);
                            if (changed > 0) {
                                XposedBridge.log(TAG + " unlocked refresh-rate specs: "
                                        + before + " -> " + result);
                            }
                        }
                    });
            XposedBridge.log(TAG + " hooked " + className + "#getDesiredDisplayModeSpecs");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " unavailable " + className + ": " + e);
        }
    }

    /**
     * Recursively updates refresh-rate range objects. HyperOS revisions have used
     * several nested representations (RefreshRateRange/RefreshRateRanges), so we
     * intentionally locate only fields named "max*" and never alter unrelated
     * numeric fields.
     */
    private static int unlockRefreshRateRanges(Object root) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        return walk(root, seen, 0);
    }

    private static int walk(Object value, Set<Object> seen, int depth) {
        if (value == null || depth > 6) return 0;
        Class<?> type = value.getClass();
        if (type.isPrimitive() || type == String.class || type.isEnum()) return 0;
        if (!seen.add(value)) return 0;

        int changed = 0;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }

            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String name = f.getName().toLowerCase();
                try {
                    f.setAccessible(true);
                    Class<?> ft = f.getType();
                    if (ft == float.class || ft == Float.class) {
                        float current = f.getFloat(value);
                        if (name.contains("max")
                                && !Float.isInfinite(current)
                                && !Float.isNaN(current)
                                && current > 0.0f
                                && current <= 60.5f) {
                            f.setFloat(value, UNLOCK_MAX_FPS);
                            changed++;
                        }
                    } else if (ft == double.class || ft == Double.class) {
                        double current = f.getDouble(value);
                        if (name.contains("max")
                                && !Double.isInfinite(current)
                                && !Double.isNaN(current)
                                && current > 0.0d
                                && current <= 60.5d) {
                            f.setDouble(value, UNLOCK_MAX_FPS);
                            changed++;
                        }
                    } else if (!ft.isPrimitive()
                            && !ft.getName().startsWith("java.lang.")
                            && !ft.getName().startsWith("java.util.")) {
                        Object child = f.get(value);
                        changed += walk(child, seen, depth + 1);
                    }
                } catch (Throwable ignored) {
                    // Vendor framework fields may be inaccessible/final on some builds.
                }
            }
        }
        return changed;
    }
}
