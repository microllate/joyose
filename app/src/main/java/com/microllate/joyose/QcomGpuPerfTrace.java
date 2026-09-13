package com.microllate.joyose;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Traces the real Qualcomm perfLock resource array used by PowerKeeper. */
public class QcomGpuPerfTrace implements IXposedHookLoadPackage {
    private static final String TAG = "[Joyose-QcomTrace]";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.miui.powerkeeper".equals(lpparam.packageName)) return;
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.miui.powerkeeper.perfengine.g", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(c, "e", int.class, int[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!(p.args[1] instanceof int[])) return;
                            int duration = ((Integer) p.args[0]).intValue();
                            int[] a = (int[]) p.args[1];
                            StringBuilder sb = new StringBuilder(TAG)
                                    .append(" perfLock duration=").append(duration)
                                    .append(" count=").append(a.length)
                                    .append(" resources=");
                            boolean gpu = false;
                            for (int i = 0; i < a.length; i++) {
                                if (i > 0) sb.append(',');
                                long u = a[i] & 0xffffffffL;
                                sb.append(String.format("0x%08X", u));
                                if (u == 0x42804000L) gpu = true;
                            }
                            sb.append(" gpu42804000=").append(gpu);
                            XposedBridge.log(sb.toString());
                        }
                    });
            XposedBridge.log(TAG + " hooked com.miui.powerkeeper.perfengine.g.e");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook failed: " + t);
        }
    }
}
