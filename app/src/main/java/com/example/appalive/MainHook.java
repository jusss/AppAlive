package com.example.appalive;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AppAlive";
    private static final String AMS = "com.android.server.am";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Only hook system_server (where AMS runs)
        if (!"android".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": Loaded into system_server");

        // ─── Hook 1: checkExcessivePowerUsageLPr → always return false ───
        try {
            XposedHelpers.findAndHookMethod(
                    AMS + ".OomAdjuster",
                    lpparam.classLoader,
                    "checkExcessivePowerUsageLPr",
                    long.class,      // uptimeSince
                    boolean.class,   // doCpuKills
                    long.class,      // cpuTimeUsed
                    String.class,    // processName
                    String.class,    // shortString
                    int.class,       // cpuLimit
                    AMS + ".ProcessRecord",  // app
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Block the kill by returning false immediately
                            param.setResult(false);
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hooked checkExcessivePowerUsageLPr ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook checkExcessivePowerUsageLPr: " + t.getMessage());
        }

        // ─── Hook 2: updateAppProcessCpuTimeLPr → skip the entire method ───
        // This is a backup — if Hook 1 fails, this stops it even earlier
        try {
            XposedHelpers.findAndHookMethod(
                    AMS + ".OomAdjuster",
                    lpparam.classLoader,
                    "updateAppProcessCpuTimeLPr",
                    long.class,      // uptimeSince
                    boolean.class,   // doCpuKills
                    long.class,      // checkDur
                    int.class,       // cpuLimit
                    AMS + ".ProcessRecord",  // app
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Set doCpuKills = false (2nd parameter)
                            param.args[1] = false;
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hooked updateAppProcessCpuTimeLPr ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook updateAppProcessCpuTimeLPr: " + t.getMessage());
        }

        // ─── Hook 3: sendKillExcessiveCpuProfilingTrigger → no-op ───
        // Prevents profiling triggers that happen even if we block the kill
        try {
            XposedHelpers.findAndHookMethod(
                    AMS + ".ActivityManagerService",
                    lpparam.classLoader,
                    "sendKillExcessiveCpuProfilingTrigger",
                    int.class,       // uid
                    String.class,    // packageName
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hooked sendKillExcessiveCpuProfilingTrigger ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook profiling trigger: " + t.getMessage());
        }
    }
}