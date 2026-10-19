package com.example.appalive;

import android.content.Intent;
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
    // keytool -genkeypair -v -keystore AppAlive/app/release.jks -alias appalive -keyalg RSA -keysize 2048 -validity 10000 -storepass 123 -keypass 123

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
//        if (!"android".equals(lpparam.packageName)) return;
        if (!"android".equals(lpparam.packageName) && !"system".equals(lpparam.packageName)) { return; }

        XposedBridge.log(TAG + ": Loaded into system_server");

        String[] TARGET_CLASSES = {
                "com.android.server.am.ActivityManagerService",
//                "com.android.server.am.AppProfiler",
//                "com.android.server.am.ProcessList",
        };

        for (String className: TARGET_CLASSES) {

            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) continue;

            Class<?> amsClass = XposedHelpers.findClass(className, lpparam.classLoader);

            for (java.lang.reflect.Method method: amsClass.getDeclaredMethods()) {

                if (method.getName().equals("checkExcessivePowerUsageLPr")){
                    // ─── Hook 1: checkExcessivePowerUsageLPr → always return false ───
                    try {
                        XposedBridge.hookMethod(method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        try{
                                            // this can use the last param app.info.packageName to get package name, control which package would keep alive, and other just return
                                            // param.setResult would skip original function, return would run the original function
                                            // updateAppProcessCpuTimeLPr and updatePhantomProcessCpuTimeLPr both use checkExcessivePowerUsageLPr return value to kill apps, no need to hook them

                                            Object app = param.args[param.args.length - 1];
                                            if (app == null) {
                                                return;
                                            }
                                            if (app.getClass().getName().equals("com.android.server.am.ProcessRecord")){
                                                Object info = XposedHelpers.getObjectField(app, "info");
                                                String packageName = (String) XposedHelpers.getObjectField(info, "packageName");
                                                // dynamic list: added on app resume, removed when task swiped away in recents
                                                // (seeded at boot from KeepAliveConfig). see ProtectedApps.
                                                XposedBridge.log(TAG + ": checkExcessivePowerUsageLPr check: " + packageName);
                                                if (ProtectedApps.contains(packageName)) {
                                                    XposedBridge.log(TAG + ": checkExcessivePowerUsageLPr skip: " + packageName);
                                                    param.setResult(false);
                                                }
                                            }
                                            // debug
//                                            ProtectedApps.dump();
                                        } catch (Throwable t) {
                                            XposedBridge.log(TAG + ": checkExcessivePowerUsageLPr FAILED: " + t.getMessage());
                                        }
                                    }
                                }
                        );
                        XposedBridge.log(TAG + ": Hooked checkExcessivePowerUsageLPr ✓");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": checkExcessivePowerUsageLPr FAILED: " + t.getMessage());
                    }
                };

                if (method.getName().equals("checkExcessivePowerUsageLocked")){
                    // ─── Hook 5: checkExcessivePowerUsageLocked → no-op ───
                    // this is in lineageos 18.1
                    try {
                        XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING);
                        XposedBridge.log(TAG + ": Hooked checkExcessivePowerUsageLocked ✓");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": checkExcessivePowerUsageLocked FAILED: " + t.getMessage());
                    }
                };
            }
        };

        // dynamic keep-alive: seed once from file/defaults, then maintained by
        // app-resume / recents-swipe hooks below
        ProtectedApps.seedIfNeeded();

        hookAppResumed(lpparam.classLoader);
        hookTaskRemovedFromRecents(lpparam.classLoader);
    }

    // ── Hook C: app came to foreground (started / resumed) → protect it ──
    // ActivityRecord.completeResumeLocked() is called (under the WM lock) exactly when an
    // activity actually becomes resumed, i.e. the app was opened or brought back to front.
    // Android 12+: com.android.server.wm.ActivityRecord
    // Android 11 : com.android.server.am.ActivityRecord  (same method name)
    // Package comes from record.intent.getComponent(). Skip the launcher (ACTIVITY_TYPE_HOME)
    // so the home app never enters the set.
    // Only tiny in-memory set ops run in the hook -> safe under the WM lock.
    private void hookAppResumed(ClassLoader cl) {
        String[] arClasses = {
                "com.android.server.wm.ActivityRecord",  // Android 12+
                "com.android.server.am.ActivityRecord",  // Android 11 (lineage 18.1)
        };
        boolean hooked = false;
        for (String name : arClasses) {
            Class<?> ar = XposedHelpers.findClassIfExists(name, cl);
            if (ar == null) continue;
            try {
                XposedBridge.hookAllMethods(ar, "completeResumeLocked", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object record = param.thisObject;
                            Intent intent = (Intent) XposedHelpers.getObjectField(record, "intent");
                            if (intent == null || intent.getComponent() == null) return;
                            String pkg = intent.getComponent().getPackageName();
                            if (pkg == null || pkg.isEmpty()) return;
                            // don't protect the launcher / home task
                            if (isHomeTask(record)) return;
                            ProtectedApps.add(pkg);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": hookAppResumed FAILED: " + t.getMessage());
                        }
                    }
                });
                hooked = true;
                XposedBridge.log(TAG + ": Hooked " + name + ".completeResumeLocked ✓");
                break; // only one of the two exists in a given ROM
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + name + ".completeResumeLocked FAILED: " + t.getMessage());
            }
        }
        if (!hooked) XposedBridge.log(TAG + ": hookAppResumed: no ActivityRecord class found");
    }

    // ── Hook D: task removed from Recents (user swiped it away / Clear all) → unprotect ──
    // ActivityTaskSupervisor.removeTask()/cleanUpRemovedTaskLocked() are ONLY reached from
    // user-driven recents removal (swipe one card, Clear all) — LMK and background service
    // kills never go through here, so background kills don't remove protection.
    // Path (android-13 source, verified): IActivityTaskManager.removeTask
    //   → ActivityTaskSupervisor.removeTask(Task, killProcess, removeFromRecents, reason)
    //   → cleanUpRemovedTaskLocked(Task, ...) → killProcessesForRemovedTask
    // Android 12+: com.android.server.wm.ActivityTaskSupervisor
    // Android 11 : com.android.server.am.ActivityStackSupervisor (same method names)
    // Task/TaskRecord package: task.getBaseIntent().getComponent().getPackageName()
    // Both methods are hooked on purpose: some paths go through removeTask, some directly
    // into cleanUpRemovedTaskLocked; Set.remove is idempotent so double-fire is harmless.
    private void hookTaskRemovedFromRecents(ClassLoader cl) {
        String[] supClasses = {
                "com.android.server.wm.ActivityTaskSupervisor",   // Android 12+
                "com.android.server.am.ActivityStackSupervisor",  // Android 11 (lineage 18.1)
        };
        boolean hooked = false;
        for (String name : supClasses) {
            Class<?> sup = XposedHelpers.findClassIfExists(name, cl);
            if (sup == null) continue;
            try {
                // hookAllMethods: signatures differ slightly across versions
                // (removeTask(Task,boolean,boolean,String) / cleanUpRemovedTaskLocked(Task,boolean,boolean)),
                // first arg is always the Task/TaskRecord in every version.
                XposedBridge.hookAllMethods(sup, "removeTask", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        unprotectTaskArg(param.args);
                    }
                });
                XposedBridge.hookAllMethods(sup, "cleanUpRemovedTaskLocked", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        unprotectTaskArg(param.args);
                    }
                });
                hooked = true;
                XposedBridge.log(TAG + ": Hooked " + name + ".removeTask/cleanUpRemovedTaskLocked ✓");
                break; // only one of the two exists in a given ROM
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + name + " task-removal FAILED: " + t.getMessage());
            }
        }
        if (!hooked) XposedBridge.log(TAG + ": hookTaskRemovedFromRecents: no supervisor class found");
    }

    /** Extract package from the Task/TaskRecord arg (first param of both methods) and unprotect it. */
    private void unprotectTaskArg(Object[] args) {
        try {
            if (args == null || args.length == 0 || args[0] == null) return;
            Object task = args[0];
            Intent baseIntent = (Intent) XposedHelpers.callMethod(task, "getBaseIntent");
            if (baseIntent == null || baseIntent.getComponent() == null) return;
            String pkg = baseIntent.getComponent().getPackageName();
            if (pkg == null || pkg.isEmpty()) return;
            ProtectedApps.remove(pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": unprotectTaskArg FAILED: " + t.getMessage());
        }
    }

    /** Best-effort: is this ActivityRecord the home/launcher task? */
    private boolean isHomeTask(Object activityRecord) {
        try {
            // ConfigurationContainer.getActivityType(): ACTIVITY_TYPE_HOME == 2,
            // available on both 11 (via WindowContainer) and 12+ (via Fragment)
            Object type = XposedHelpers.callMethod(activityRecord, "getActivityType");
            return type instanceof Integer && (Integer) type == 2;
        } catch (Throwable t) {
            return false; // can't tell → don't exclude
        }
    }
}
