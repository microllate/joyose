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

/** Diagnostic tracing of PowerKeeper's actual game scheduler/perf command pipeline. */
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

            // These are the actual dispatch points seen in JADX:
            // C/B -> foreground handling, S -> perf scheduler command path,
            // X -> restore scheduler command path, V -> restore helper.
            hookEveryMethodByName(c, "B");
            hookEveryMethodByName(c, "C");
            hookEveryMethodByName(c, "S");
            hookEveryMethodByName(c, "X");
            hookEveryMethodByName(c, "V");
            hookEveryMethodByName(c, "p");
            hookEveryMethodByName(c, "q");
            hookEveryMethodByName(c, "R");
            hookEveryMethodByName(c, "H");
            hookEveryMethodByName(c, "D");

            XposedBridge.log(TAG + " hooked PeGameController dispatch methods B/C/S/X/V/p/q/R/H/D");
            dumpMethodList(c, "PeGameController");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " game trace unavailable: " + e);
        }
    }

    private static void hookSchedHandler(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PeSchedHandler", cl);
            int hooked = 0;
            for (Method m : c.getDeclaredMethods()) {
                final Method target = m;
                final String sig = signature(target);
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " PeSchedHandler." + sig + " args=" + formatObjects(p.args));
                        logGpuCommandsFromArgs(p.args);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (target.getName().equals("handleMessage")) {
                            XposedBridge.log(TAG + " PeSchedHandler.handleMessage after result=" + String.valueOf(p.getResult()));
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " hooked ALL PeSchedHandler methods=" + hooked);
            dumpMethodList(c, "PeSchedHandler");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " sched handler unavailable: " + e);
        }
    }

    private static void hookPerfUtils(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PerfUtils", cl);
            for (Method m : c.getDeclaredMethods()) {
                final Method target = m;
                if (!target.getName().equals("c")) continue;
                final String sig = signature(target);
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " PerfUtils.c args=" + formatObjects(p.args));
                    }
                });
            }
            XposedBridge.log(TAG + " hooked PerfUtils.c");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " PerfUtils trace unavailable: " + e);
        }
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
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " hooked SchedConfig methods=" + hooked);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " SchedConfig trace unavailable: " + e);
        }
    }

    private static void hookEveryMethodByName(final Class<?> c, final String name) {
        int count = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            final Method target = m;
            final String sig = signature(target);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " PeGameController." + sig + " args=" + formatObjects(p.args));
                    logGpuCommandsFromArgs(p.args);
                    if (name.equals("B") || name.equals("C") || name.equals("S") || name.equals("X") || name.equals("V")) {
                        dumpAllFields(p.thisObject, "PeGameController before " + name);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (name.equals("p") || name.equals("q") || name.equals("R")
                            || name.equals("H") || name.equals("D")) {
                        dumpAllFields(p.thisObject, "PeGameController after " + name);
                    }
                    if (name.equals("S") || name.equals("X")) {
                        XposedBridge.log(TAG + " PeGameController." + name + " result=" + String.valueOf(p.getResult()));
                    }
                }
            });
            count++;
        }
        if (count == 0) {
            XposedBridge.log(TAG + " PeGameController method absent: " + name);
        } else {
            XposedBridge.log(TAG + " hooked PeGameController." + name + " count=" + count);
        }
    }

    private static void logGpuCommandsFromArgs(Object[] args) {
        if (args == null) return;
        for (Object a : args) {
            if (a instanceof List) logGpuCommands((List<?>) a);
            else if (a instanceof String) logGpuString((String) a);
            else if (a instanceof int[]) logIntArray((int[]) a);
        }
    }

    private static void logGpuCommands(List<?> list) {
        if (list == null) return;
        int n = Math.min(list.size(), 160);
        for (int i = 0; i < n; i++) {
            Object v = list.get(i);
            if (v != null) logGpuString(String.valueOf(v));
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

    private static void logIntArray(int[] a) {
        if (a == null) return;
        XposedBridge.log(TAG + " INT-ARRAY len=" + a.length + " values=" + Arrays.toString(a));
    }

    private static String signature(Method m) {
        return m.getName() + Arrays.toString(m.getParameterTypes()) + " -> " + m.getReturnType().getName();
    }

    private static void dumpMethodList(Class<?> c, String label) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                XposedBridge.log(TAG + " METHOD " + label + "." + signature(m));
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " method list failed " + label + ": " + e);
        }
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
                    if (text.length() > 800) text = text.substring(0, 800) + "...";
                    s.append(' ').append(f.getName()).append("<").append(f.getType().getSimpleName()).append(">=").append(text);
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
                if (text.length() > 700) text = text.substring(0, 700) + "...";
                sb.append(text);
            }
        }
        return sb.append(']').toString();
    }
}
