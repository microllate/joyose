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

/** Diagnostic tracing of PowerKeeper's universal foreground/perf pipeline. */
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
        hookQualcommPerf(cl);
        hookPerfFlinger(cl);
    }

    private static void hookGame(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PeGameController", cl);
            for (String n : new String[]{"a","B","C","S","X","V","p","q","R","H","D","T"}) {
                hookEveryMethodByName(c, n);
            }
            XposedBridge.log(TAG + " hooked PeGameController foreground/perf dispatch");
            dumpMethodList(c, "PeGameController");
        } catch (Throwable e) { XposedBridge.log(TAG + " game trace unavailable: " + e); }
    }

    private static void hookSchedHandler(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PeSchedHandler", cl);
            for (Method m : c.getDeclaredMethods()) {
                final Method target = m;
                final String sig = signature(target);
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " PeSchedHandler." + sig + " args=" + formatObjects(p.args));
                        logGpuCommandsFromArgs(p.args);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (target.getName().equals("handleMessage"))
                            XposedBridge.log(TAG + " PeSchedHandler.handleMessage after result=" + String.valueOf(p.getResult()));
                    }
                });
            }
            XposedBridge.log(TAG + " hooked ALL PeSchedHandler methods");
            dumpMethodList(c, "PeSchedHandler");
        } catch (Throwable e) { XposedBridge.log(TAG + " sched handler unavailable: " + e); }
    }

    private static void hookPerfUtils(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PerfUtils", cl);
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals("c")) {
                final Method target = m;
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " PerfUtils.c args=" + formatObjects(p.args));
                    }
                });
            }
            XposedBridge.log(TAG + " hooked PerfUtils.c");
        } catch (Throwable e) { XposedBridge.log(TAG + " PerfUtils trace unavailable: " + e); }
    }

    private static void hookQualcommPerf(ClassLoader cl) {
        String[] names = {"com.qualcomm.qti.Performance", "com.qualcomm.qti.Performance$PerfLock"};
        for (String cn : names) {
            try {
                Class<?> c = XposedHelpers.findClass(cn, cl);
                int count = 0;
                for (Method m : c.getDeclaredMethods()) {
                    String n = m.getName();
                    if (!n.toLowerCase(Locale.ROOT).contains("perf")) continue;
                    final Method target = m;
                    final String sig = signature(target);
                    XposedBridge.hookMethod(target, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            XposedBridge.log(TAG + " QualcommPerf." + sig + " args=" + formatObjects(p.args));
                            logGpuCommandsFromArgs(p.args);
                        }
                    });
                    count++;
                }
                XposedBridge.log(TAG + " hooked Qualcomm Performance class=" + cn + " methods=" + count);
            } catch (Throwable e) {
                XposedBridge.log(TAG + " Qualcomm class unavailable " + cn + ": " + e);
            }
        }
    }

    private static void hookPerfFlinger(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.xring.perf.PerfFlingerClient", cl);
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (!n.contains("perf") && !n.contains("lock")) continue;
                final Method target = m;
                final String sig = signature(target);
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log(TAG + " PerfFlinger." + sig + " args=" + formatObjects(p.args));
                        logGpuCommandsFromArgs(p.args);
                    }
                });
            }
            XposedBridge.log(TAG + " hooked PerfFlingerClient");
        } catch (Throwable e) { XposedBridge.log(TAG + " PerfFlinger unavailable: " + e); }
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
            XposedBridge.log(TAG + " hooked SchedConfig constructors");
        } catch (Throwable e) { XposedBridge.log(TAG + " SchedConfig trace unavailable: " + e); }
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
                    if (name.equals("a") || name.equals("B") || name.equals("C") || name.equals("S") || name.equals("X") || name.equals("V"))
                        dumpAllFields(p.thisObject, "PeGameController before " + name);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (name.equals("p") || name.equals("q") || name.equals("R") || name.equals("H") || name.equals("D"))
                        dumpAllFields(p.thisObject, "PeGameController after " + name);
                    if (name.equals("S") || name.equals("X"))
                        XposedBridge.log(TAG + " PeGameController." + name + " result=" + String.valueOf(p.getResult()));
                }
            });
            count++;
        }
        if (count == 0) XposedBridge.log(TAG + " PeGameController method absent: " + name);
        else XposedBridge.log(TAG + " hooked PeGameController." + name + " count=" + count);
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
        for (int i = 0; i < n; i++) if (list.get(i) != null) logGpuString(String.valueOf(list.get(i)));
        if (list.size() > n) XposedBridge.log(TAG + " GPU-CANDIDATE list truncated +" + (list.size() - n));
    }

    private static void logGpuString(String s) {
        if (s == null) return;
        String l = s.toLowerCase(Locale.ROOT);
        if (l.contains("gpu") || l.contains("kgsl") || l.contains("devfreq") || l.contains("freq") || l.contains("opp") || l.startsWith("0x"))
            XposedBridge.log(TAG + " GPU-CANDIDATE " + s);
    }

    private static void logIntArray(int[] a) {
        if (a != null) XposedBridge.log(TAG + " INT-ARRAY len=" + a.length + " values=" + Arrays.toString(a));
    }

    private static String signature(Method m) { return m.getName() + Arrays.toString(m.getParameterTypes()) + " -> " + m.getReturnType().getName(); }

    private static void dumpMethodList(Class<?> c, String label) {
        try { for (Method m : c.getDeclaredMethods()) XposedBridge.log(TAG + " METHOD " + label + "." + signature(m)); }
        catch (Throwable e) { XposedBridge.log(TAG + " method list failed " + label + ": " + e); }
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
