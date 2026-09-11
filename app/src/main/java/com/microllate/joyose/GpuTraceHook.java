package com.microllate.joyose;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Second-stage diagnostic: trace the actual game scheduler command pipeline. */
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
            hook(c, "H", new Class<?>[0], "after");
            hook(c, "D", new Class<?>[0], "after");
            hook(c, "R", new Class<?>[]{org.json.JSONObject.class}, "after");
            hook(c, "C", new Class<?>[]{String.class}, "before");
            XposedBridge.log(TAG + " hooked PeGameController H/D/R/C");
        } catch (Throwable e) { XposedBridge.log(TAG + " game trace unavailable: " + e); }
    }

    private static void hookSchedHandler(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PeSchedHandler", cl);
            XposedHelpers.findAndHookMethod(c, "h", List.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " PeSchedHandler.h PERF cmds=" + formatList((List<?>) p.args[0]));
                }
            });
            XposedHelpers.findAndHookMethod(c, "i", ArrayList.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " PeSchedHandler.i RESTORE cmds=" + formatList((List<?>) p.args[0]));
                }
            });
            Method l = c.getDeclaredMethod("l", ArrayList.class);
            XposedBridge.hookMethod(l, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " PeSchedHandler.l WRITE cmds=" + formatList((List<?>) p.args[0]));
                    logGpuCommands((List<?>) p.args[0]);
                }
            });
            XposedHelpers.findAndHookMethod(c, "handleMessage", android.os.Message.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    android.os.Message m = (android.os.Message) p.args[0];
                    XposedBridge.log(TAG + " PeSchedHandler.handleMessage what=" + (m == null ? -1 : m.what));
                }
            });
            XposedBridge.log(TAG + " hooked PeSchedHandler h/i/l/handleMessage");
        } catch (Throwable e) { XposedBridge.log(TAG + " sched handler unavailable: " + e); }
    }

    private static void hookPerfUtils(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.PerfUtils", cl);
            XposedHelpers.findAndHookMethod(c, "c", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " PerfUtils.c -> mcd.extra.params: " + p.args[0]);
                }
            });
            XposedBridge.log(TAG + " hooked PerfUtils.c");
        } catch (Throwable e) { XposedBridge.log(TAG + " PerfUtils trace unavailable: " + e); }
    }

    private static void hookSchedConfig(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.miui.powerkeeper.perfengine.SchedConfig", cl);
            XposedHelpers.findAndHookMethod(c, "e", java.io.InputStream.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + " SchedConfig.e(InputStream) finished");
                    dumpFields(p.thisObject);
                }
            });
            XposedBridge.log(TAG + " hooked SchedConfig.e(InputStream)");
        } catch (Throwable e) { XposedBridge.log(TAG + " SchedConfig trace unavailable: " + e); }
    }

    private static void hook(final Class<?> c, String name, Class<?>[] args, String when) {
        XposedHelpers.findAndHookMethod(c, name, concat(args, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ("before".equals(when)) logGameState(p.thisObject, "before");
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if ("after".equals(when)) logGameState(p.thisObject, "after");
            }
        }));
    }

    private static Object[] concat(Class<?>[] a, Object hook) {
        Object[] out = new Object[a.length + 1];
        System.arraycopy(a, 0, out, 0, a.length);
        out[a.length] = hook;
        return out;
    }

    private static void logGameState(Object o, String phase) {
        try {
            StringBuilder s = new StringBuilder(TAG).append(" PeGameController state ").append(phase);
            for (String n : new String[]{"F","f508d","f511g","f514j","f515k","f524t","f525u","f526v","f527w","f528x","f529y","f507c","P"}) {
                Object v = XposedHelpers.getObjectField(o, n);
                s.append(' ').append(n).append('=').append(v);
            }
            XposedBridge.log(s.toString());
        } catch (Throwable e) { XposedBridge.log(TAG + " game state read failed: " + e); }
    }

    private static void dumpFields(Object o) {
        try {
            StringBuilder s = new StringBuilder(TAG).append(" SchedConfig values:");
            for (String n : new String[]{"f562a","f563b","f567f","f568g","f569h","f572k","f573l","f574m","f575n","f576o","f577p","f578q","f579r","f580s","f581t","f582u","f583v"}) {
                try { s.append(' ').append(n).append('=').append(XposedHelpers.getObjectField(o, n)); }
                catch (Throwable ignored) { }
            }
            XposedBridge.log(s.toString());
        } catch (Throwable ignored) { }
    }

    private static void logGpuCommands(List<?> list) {
        if (list == null) return;
        for (Object v : list) {
            if (v == null) continue;
            String s = String.valueOf(v);
            String l = s.toLowerCase(Locale.ROOT);
            if (l.contains("gpu") || l.contains("kgsl") || l.contains("devfreq") || l.contains("freq") || l.contains("opp") || l.startsWith("0x")) {
                XposedBridge.log(TAG + " GPU-CANDIDATE " + s);
            }
        }
    }

    private static String formatList(List<?> list) {
        if (list == null) return "null";
        StringBuilder s = new StringBuilder("[");
        int n = Math.min(list.size(), 80);
        for (int i = 0; i < n; i++) {
            if (i > 0) s.append(" | ");
            s.append(String.valueOf(list.get(i)));
        }
        if (list.size() > n) s.append(" ... +").append(list.size() - n);
        return s.append(']').toString();
    }
}
