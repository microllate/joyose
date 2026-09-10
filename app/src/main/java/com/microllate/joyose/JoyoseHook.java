package com.microllate.joyose;

import android.app.Application;
import android.content.ContentResolver;
import com.google.gson.stream.JsonReader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.Reader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
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
                hookHttpConnections();
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
                    Object r = XposedHelpers.newInstance(reader, new Class[]{Reader.class}, new Object[]{new StringReader(root.toString())});
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

    private static void hookMiuiCloudData(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("android.provider.MiuiSettings$SettingsCloudData", cl);
            XposedHelpers.findAndHookMethod(c, "getCloudDataList", ContentResolver.class, String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(null); }
            });
            XposedBridge.log(TAG + " MiuiSettings cloud data blocked");
        } catch (Throwable e) { XposedBridge.log(TAG + " MiuiSettings hook unavailable: " + e); }
    }

    private static void hookHttpConnections() {
        try {
            final XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    Object o = p.thisObject;
                    if (!(o instanceof HttpURLConnection)) return;
                    URL url = ((HttpURLConnection)o).getURL();
                    if (url == null) return;
                    String s = url.toString().toLowerCase();
                    if (s.contains("tracking.") || s.contains("ad.xiaomi.com") || s.contains("ad.miui.com")) {
                        p.setThrowable(new java.io.IOException("blocked by Joyose module"));
                    }
                }
            };
            // The concrete HttpURLConnection implementation is device/JDK dependent;
            // this constructor hook discovers it at runtime.
            final XC_MethodHook.Unhook[] holder = new XC_MethodHook.Unhook[1];
            holder[0] = XposedHelpers.findAndHookConstructor(HttpURLConnection.class, URL.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Class<?> impl = p.thisObject.getClass();
                        XposedHelpers.findAndHookMethod(impl, "connect", hook);
                        XposedHelpers.findAndHookMethod(impl, "getInputStream", hook);
                        XposedHelpers.findAndHookMethod(impl, "getOutputStream", hook);
                    } catch (Throwable ignored) {}
                    if (holder[0] != null) holder[0].unhook();
                }
            });
        } catch (Throwable e) { XposedBridge.log(TAG + " HTTP hook unavailable: " + e); }
    }
}
