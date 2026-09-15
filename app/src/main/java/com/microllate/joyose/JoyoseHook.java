package com.microllate.joyose;

import android.app.Application;
import android.content.ContentResolver;
import com.google.gson.stream.JsonReader;
import org.json.JSONObject;
import java.io.StringReader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class JoyoseHook implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose]";
    private static final String JOYOSE = "com.xiaomi.joyose";

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!JOYOSE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + " loaded: " + lpparam.processName);
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                hookCloudParser(lpparam.classLoader);
                hookMiuiCloudData(lpparam.classLoader);
                hookCpuCloudDispatcher(lpparam.classLoader);
            }
        });
    }

    private static void hookCloudParser(final ClassLoader cl) {
        try {
            final Class<?> reader = XposedHelpers.findClass("com.google.gson.stream.JsonReader", cl);
            Class<?> parser = XposedHelpers.findClass("com.google.gson.JsonParser", cl);
            XposedHelpers.findAndHookMethod(parser, "parse", reader, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    Object result = p.getResult();
                    if (result == null) return;
                    JSONObject root;
                    try { root = new JSONObject(result.toString()); } catch (Throwable ignored) { return; }
                    JSONObject profile = extractProfile(root);
                    if (profile == null) return;
                    JSONObject hacked = blockPerformanceProfile(profile);
                    replaceProfile(root, hacked);
                    Object r = XposedHelpers.newInstance(reader, new StringReader(root.toString()));
                    p.setResult(XposedBridge.invokeOriginalMethod(p.method, p.thisObject, new Object[]{r}));
                    XposedBridge.log(TAG + " cloud performance profile blocked");
                }
            });
        } catch (Throwable e) { XposedBridge.log(TAG + " Gson hook unavailable: " + e); }
    }

    private static JSONObject extractProfile(JSONObject root) {
        JSONObject common = root.optJSONObject("common_config");
        JSONObject booster = root.optJSONObject("booster_config");
        if (common == null) {
            JSONObject data = root.optJSONObject("data");
            if (data != null) common = data.optJSONObject("common_config");
        }
        if (common == null && booster == null) return null;
        JSONObject out = new JSONObject();
        try { if (common != null) out.put("common_config", common); if (booster != null) out.put("booster_config", booster); } catch (Throwable ignored) {}
        return out;
    }

    private static JSONObject blockPerformanceProfile(JSONObject profile) {
        JSONObject out = new JSONObject();
        try {
            for (String name : new String[]{"common_config", "booster_config"}) {
                JSONObject old = profile.optJSONObject(name);
                if (old == null) continue;
                JSONObject n = new JSONObject();
                n.put("config_name", old.opt("config_name"));
                n.put("version", old.optInt("version"));
                n.put("group_name", old.opt("group_name"));
                n.put("enable", false);
                n.put("with_model", false);
                n.put("params", new JSONObject());
                out.put(name, n);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void replaceProfile(JSONObject root, JSONObject hacked) {
        try {
            JSONObject common = hacked.optJSONObject("common_config");
            JSONObject booster = hacked.optJSONObject("booster_config");
            if (root.has("common_config") && common != null) root.put("common_config", common);
            JSONObject data = root.optJSONObject("data");
            if (data != null && data.has("common_config") && common != null) data.put("common_config", common);
            if (root.has("booster_config") && booster != null) root.put("booster_config", booster);
        } catch (Throwable ignored) {}
    }

    /**
     * Joyose turns cloud actions into /data/system/whetstone/perf_data and
     * asks mcd_init to dispatch them. Block only CPU tuning commands here so
     * the kernel remains responsible for normal CPU frequency/core scheduling.
     * GPU, DDR, display and unrelated performance actions are left intact.
     */
    private static void hookCpuCloudDispatcher(final ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("z.l", cl);
            XposedHelpers.findAndHookMethod(c, "b", String[].class, String.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!(p.args[0] instanceof String[])) return;
                            String[] commands = (String[]) p.args[0];
                            int removed = 0;
                            for (int i = 0; i < commands.length; i++) {
                                String command = commands[i];
                                String filtered = filterCpuCloudCommand(command);
                                if (filtered == null) {
                                    commands[i] = "";
                                    removed++;
                                    XposedBridge.log(TAG + " BLOCK CPU cloud command: " + command);
                                } else if (!filtered.equals(command)) {
                                    commands[i] = filtered;
                                    XposedBridge.log(TAG + " FILTER CPU resources: " + command + " -> " + filtered);
                                }
                            }
                            if (removed > 0) {
                                p.args[0] = commands;
                                XposedBridge.log(TAG + " blocked " + removed + " CPU cloud command(s)");
                            }
                        }
                    });
            XposedBridge.log(TAG + " CPU cloud dispatcher hooked");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " CPU cloud dispatcher unavailable: " + e);
        }
    }

    private static String filterCpuCloudCommand(String command) {
        if (command == null || command.isEmpty()) return command;
        String normalized = command.startsWith("/") ? command : "/" + command;
        if (normalized.startsWith("/sys/devices/system/cpu/")) return null;
        if (normalized.startsWith("/proc/sys/walt/")) return null;
        if (normalized.startsWith("/sys/module/migt/parameters/")) return null;
        if (!command.startsWith("perflock#")) return command;
        return filterPerflockCpuResources(command);
    }

    private static String filterPerflockCpuResources(String command) {
        String[] parts = command.split("#", -1);
        if (parts.length != 3) return command;
        String[] tokens = parts[1].split("_", -1);
        if ((tokens.length & 1) != 0) return command;
        StringBuilder resources = new StringBuilder();
        boolean changed = false;
        for (int i = 0; i < tokens.length; i += 2) {
            if (isCpuMinResource(tokens[i])) {
                changed = true;
                XposedBridge.log(TAG + " BLOCK perflock CPU resource=" + tokens[i]
                        + " value=" + tokens[i + 1]);
                continue;
            }
            if (resources.length() > 0) resources.append('_');
            resources.append(tokens[i]).append('_').append(tokens[i + 1]);
        }
        if (!changed) return command;
        if (resources.length() == 0) return null;
        return "perflock#" + resources + "#" + parts[2];
    }

    private static boolean isCpuMinResource(String token) {
        try {
            long value = Long.parseLong(token.startsWith("0x") || token.startsWith("0X")
                    ? token.substring(2) : token, 16);
            return value == 0x40800000L || value == 0x40800100L || value == 0x40800200L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void hookMiuiCloudData(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("android.provider.MiuiSettings$SettingsCloudData", cl);
            XposedHelpers.findAndHookMethod(c, "getCloudDataList", ContentResolver.class, String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(null); }
            });
            XposedBridge.log(TAG + " MiuiSettings cloud data blocked");
        } catch (Throwable e) { XposedBridge.log(TAG + " MiuiSettings hook unavailable: " + e); }
    }
}
