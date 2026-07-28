package com.example.appalive;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AppAlive";
    // AOSP source code https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=602?q=ActivityManagerService&sq=
    // lineage 18.1 source code https://github.com/LineageOS/android_frameworks_base/blob/lineage-18.1/services/core/java/com/android/server/am/ActivityManagerService.java
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded into system_server");

        String[] TARGET_CLASSES = {
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.AppProfiler",
                "com.android.server.am.ProcessList",
        };

        for (String className: TARGET_CLASSES) {

            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) continue;

            Class<?> amsClass = XposedHelpers.findClass(className, lpparam.classLoader);

            for (java.lang.reflect.Method method: amsClass.getDeclaredMethods()) {

                if (method.getName().equals("checkExcessivePowerUsageLPr")){
                    // ─── Hook 1: checkExcessivePowerUsageLPr → always return false ───
                    try {
                        XposedBridge.hookMethod(
                                method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        param.setResult(false);
                                    }
                                }
                        );
                        XposedBridge.log(TAG + ": Hooked checkExcessivePowerUsageLPr ✓");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": checkExcessivePowerUsageLPr FAILED: " + t.getMessage());
                    }
                };

                if (method.getName().equals("updateAppProcessCpuTimeLPr")){
                    // ─── Hook 2: updateAppProcessCpuTimeLPr → set doCpuKills = false ───
                    try {
                        XposedBridge.hookMethod(
                                method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        param.args[1] = false;
                                    }
                                }
                        );
                        XposedBridge.log(TAG + ": Hooked updateAppProcessCpuTimeLPr ✓");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": updateAppProcessCpuTimeLPr FAILED: " + t.getMessage());
                    }
                };

                if (method.getName().equals("updatePhantomProcessCpuTimeLPr")){
                    // ─── Hook 3:  updatePhantomProcessCpuTimeLPr → set doCpuKills = false ───
                    try {
                        XposedBridge.hookMethod(
                                method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        param.args[1] = false;
                                    }
                                }
                        );
                        XposedBridge.log(TAG + ": Hooked updatePhantomProcessCpuTimeLPr ✓");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": updatePhantomProcessCpuTimeLPr FAILED: " + t.getMessage());
                    }
                };

                if (method.getName().equals("sendKillExcessiveCpuProfilingTrigger")){
                    // ─── Hook 4: sendKillExcessiveCpuProfilingTrigger → no-op ───
                    // this may in com.android.server.am.AppProfiler in android 11-15, different OEM may have different parameters
                    try {
                        XposedBridge.hookMethod(
                                method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        param.setResult(null);
                                    }
                                }
                        );
                        XposedBridge.log(TAG + ": Hooked sendKillExcessiveCpuProfilingTrigger ✓");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": sendKillExcessiveCpuProfilingTrigger FAILED: " + t.getMessage());
                    }
                };

                if (method.getName().equals("checkExcessivePowerUsageLocked")){
                    // ─── Hook 5: checkExcessivePowerUsageLocked → no-op ───
                    // this is in lineageos 18.1
                    try {
                        XposedBridge.hookMethod(
                                method,
                                XC_MethodReplacement.DO_NOTHING
                        );
                        XposedBridge.log(TAG + ": Hooked checkExcessivePowerUsageLocked ✓");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": checkExcessivePowerUsageLocked FAILED: " + t.getMessage());
                    }
                };
            }
        };
    }
}