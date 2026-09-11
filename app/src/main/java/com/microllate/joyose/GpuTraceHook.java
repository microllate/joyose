package com.microllate.joyose;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Diagnostic tracing of PowerKeeper's scheduler/perf command pipeline. */
public class GpuTraceHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-GPUTrace]";
    private static final String PK = "com.miui.powerkeeper";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if (!PK.equals(p.packageName)) return;
        ClassLoader cl = p.classLoader;
        XposedBridge.log(TAG + " loaded: " + p.processName);
        hookGame(cl);
        hookSchedHandler(cl);
        hookPerfUtils(cl);
        hookSchedConfig(cl);
    }

    private static void hookGame(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PeGameController", cl);
            hookIfPresent(c, "H", new Class<?>[0], "PeGameController.H", true);
            hookIfPresent(c, "D", new Class<?>[0], "PeGameController.D", true);
            hookIfPresent(c, "R", new Class<?>[]{org.json.JSONObject.class}, "PeGameController.R", true);
            hookIfPresent(c, "C", new Class<?>[]{String.class}, "PeGameController.C", false);
            XposedBridge.log(TAG + " hooked PeGameController H/D/R/C");
        } catch (Throwable e) { XposedBridge.log(TAG + " game trace unavailable: " + e); }
    }

    /**
     * Do not assume obfuscated parameter types. The previous build stopped after
     * PeSchedHandler.i(ArrayList) was absent, so h()/l() were never hooked.
     * This version hooks every declared method in the class and logs its signature,
     * while only dumping arguments for scheduler/write-looking methods.
     */
    private static void hookSchedHandler(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PeSchedHandler", cl);
            int hooked = 0;
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean interesting = lower.contains("sched") || lower.contains("write")
                        || lower.contains("restore") || lower.contains("perf")
                        || lower.equals("h") || lower.equals("i") || lower.equals("l")
                        || lower.equals("j") || lower.equals("k");
                if (!interesting) continue;
                final Method target = m;
                final String sig = signature(target);
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " PeSchedHandler." + sig + " args=" + formatObjects(p.args));
                        if (hasListArg(p.args)) logGpuCommandsFromArgs(p.args);
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " hooked PeSchedHandler methods=" + hooked);
        } catch (Throwable e) { XposedBridge.log(TAG + " sched handler unavailable: " + e); }
    }

    private static void hookPerfUtils(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PerfUtils", cl);
            hookIfPresent(c, "c", new Class<?>[]{String.class}, "PerfUtils.c", false);
            XposedBridge.log(TAG + " hooked PerfUtils.c");
        } catch (Throwable e) { XposedBridge.log(TAG + " PerfUtils trace unavailable: " + e); }
    }

    private static void hookSchedConfig(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.SchedConfig", cl);
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                final String sig = "SchedConfig.<init>" + Arrays.toString(ctor.getParameterTypes());
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " " + sig + " args=" + formatObjects(p.args));
                        dumpAllFields(p.thisObject, "SchedConfig");
                    }
                });
            }
            int hooked = 0;
            for (Method m : c.getDeclaredMethods()) {
                String lower = m.getName().toLowerCase(Locale.ROOT);
                if (!lower.contains("gpu") && !lower.contains("sched") && !lower.contains("level")
                        && !lower.contains("config") && !lower.contains("parse") && !lower.contains("load")) continue;
                final String sig = signature(m);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " " + sig + " args=" + formatObjects(p.args));
                        dumpAllFields(p.thisObject, "SchedConfig");
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " hooked SchedConfig methods=" + hooked);
        } catch (Throwable e) { XposedBridge.log(TAG + " SchedConfig trace unavailable: " + e); }
    }

    private static void hookIfPresent(final Class<?> c, String name, Class<?>[] args, final String label, final boolean after) {
        try {
            Object[] hookArgs = new Object[args.length + 1];
            System.arraycopy(args, 0, hookArgs, 0, args.length);
            hookArgs[args.length] = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!after) logGameState(p.thisObject, label + " before");
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (after) logGameState(p.thisObject, label + " after");
                }
            };
            XposedHelpers.findAndHookMethod(c, name, hookArgs);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " " + label + " unavailable: " + e);
        }
    }

    private static String signature(Method m) {
        return m.getName() + Arrays.toString(m.getParameterTypes()) + " -> " + m.getReturnType().getName();
    }

    private static boolean hasListArg(Object[] args) {
        if (args == null) return false;
        for (Object a : args) if (a instanceof List) return true;
        return false;
    }

    private static void logGpuCommandsFromArgs(Object[] args) {
        if (args == null) return;
        for (Object a : args) {
            if (a instanceof List) logGpuCommands((List<?>) a);
            else if (a instanceof String) logGpuString((String) a);
        }
    }

    private static void logGpuCommands(List<?> list) {
        if (list == null) return;
        int n = Math.min(list.size(), 120);
        for (int i = 0; i < n; i++) {
            Object v = list.get(i);
            if (v == null) continue;
            logGpuString(String.valueOf(v));
        }
        if (list.size() > n) XposedBridge.log(TAG + " GPU-CANDIDATE list truncated +" + (list.size() - n));
    }

    private static void logGpuString(String s) {
        if (s == null) return;
        String l = s.toLowerCase(Locale.ROOT);
        if (l.contains("gpu") || l.contains("kgsl") || l.contains("devfreq")
                || l.contains("freq") || l.contains("opp") || l.startsWith("0x")) {
            XposedBridge.log(TAG + " GPU-CANDIDATE " + s);
        }
    }

    private static void logGameState(Object o, String phase) {
        try {
            StringBuilder s = new StringBuilder(TAG).append(' ').append(phase).append(" fields:");
            for (Field f : allFields(o.getClass())) {
                String n = f.getName();
                String lower = n.toLowerCase(Locale.ROOT);
                if (!lower.contains("game") && !lower.contains("sched") && !lower.contains("perf")
                        && !lower.contains("level") && !lower.contains("config") && !lower.matches("f\\d+")) continue;
                try {
                    f.setAccessible(true);
                    s.append(' ').append(n).append('=').append(String.valueOf(f.get(o)));
                } catch (Throwable ignored) { }
            }
            XposedBridge.log(s.toString());
        } catch (Throwable e) { XposedBridge.log(TAG + " game state read failed: " + e); }
    }

    private static void dumpAllFields(Object o, String label) {
        try {
            StringBuilder s = new StringBuilder(TAG).append(' ').append(label).append(" fields:");
            for (Field f : allFields(o.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    String text = String.valueOf(v);
                    if (text.length() > 400) text = text.substring(0, 400) + "...";
                    s.append(' ').append(f.getName()).append('=').append(text);
                } catch (Throwable ignored) { }
            }
            XposedBridge.log(s.toString());
        } catch (Throwable ignored) { }
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> x = c; x != null && x != Object.class; x = x.getSuperclass()) {
            try { out.addAll(Arrays.asList(x.getDeclaredFields())); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static String formatObjects(Object[] values) {
        if (values == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            Object v = values[i];
            if (v instanceof int[]) sb.append(Arrays.toString((int[]) v));
            else if (v instanceof long[]) sb.append(Arrays.toString((long[]) v));
            else if (v instanceof byte[]) sb.append(Arrays.toString((byte[]) v));
            else {
                String text = String.valueOf(v);
                if (text.length() > 500) text = text.substring(0, 500) + "...";
                sb.append(text);
            }
        }
        return sb.append(']').toString();
    }
}
