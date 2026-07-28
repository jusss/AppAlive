package com.example.appalive;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AppAlive";
    private static final String AMS = "com.android.server.am.ActivityManagerService";

    // AOSP source code https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=602?q=ActivityManagerService&sq=

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded into system_server");

        // ─── Hook 1: checkExcessivePowerUsageLPr → always return false ───
        try {
            XposedHelpers.findAndHookMethod(
                    AMS,
                    lpparam.classLoader,
                    "checkExcessivePowerUsageLPr",
                    long.class,
                    boolean.class,
                    long.class,
                    String.class,
                    String.class,
                    int.class,
                    "com.android.server.am.ProcessRecord",
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

        // ─── Hook 2: updateAppProcessCpuTimeLPr → set doCpuKills = false ───
        try {
            XposedHelpers.findAndHookMethod(
                    AMS,
                    lpparam.classLoader,
                    "updateAppProcessCpuTimeLPr",
                    long.class,
                    boolean.class,
                    long.class,
                    int.class,
                    "com.android.server.am.ProcessRecord",
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

        // ─── Hook 3:  updatePhantomProcessCpuTimeLPr → set doCpuKills = false ───
        try {
            XposedHelpers.findAndHookMethod(
                    AMS,
                    lpparam.classLoader,
                    "updatePhantomProcessCpuTimeLPr",
                    long.class,
                    boolean.class,
                    long.class,
                    int.class,
                    "com.android.server.am.ProcessRecord",
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


        // ─── Hook 4: sendKillExcessiveCpuProfilingTrigger → no-op ───
        // this may in com.android.server.am.AppProfiler in android 11-15, different OEM may have different parameters
        Class<?> amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService",lpparam.classLoader);
        for (java.lang.reflect.Method method: amsClass.getDeclaredMethods()) {
            if (method.getName().equals("sendKillExcessiveCpuProfilingTrigger")){

                try {
                    XposedHelpers.findAndHookMethod(
                            amsClass,
                            method.getName(),
                            method.getParameterTypes(),
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

            }
        }

//        try {
//            XposedHelpers.findAndHookMethod(
//                    AMS,
//                    lpparam.classLoader,
//                    "sendKillExcessiveCpuProfilingTrigger",
//                    int.class,
//                    String.class,
//                    new XC_MethodHook() {
//                        @Override
//                        protected void beforeHookedMethod(MethodHookParam param) {
//                            param.setResult(null);
//                        }
//                    }
//            );
//            XposedBridge.log(TAG + ": Hooked sendKillExcessiveCpuProfilingTrigger ✓");
//        } catch (Throwable t) {
//            XposedBridge.log(TAG + ": sendKillExcessiveCpuProfilingTrigger FAILED: " + t.getMessage());
//        }
    }
}