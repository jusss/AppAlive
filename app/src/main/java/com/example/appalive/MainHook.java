package com.example.appalive;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import java.util.Collections;
import java.util.List;

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

    private static Context sSystemContext = null;
    private static final Object lock = new Object();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
//        if (!"android".equals(lpparam.packageName)) return;
        if (!"android".equals(lpparam.packageName) && !"system".equals(lpparam.packageName)) { return; }

        if (sSystemContext == null) {
            synchronized (lock) {
                if (sSystemContext == null) {
                    sSystemContext = getSystemContext(lpparam.classLoader);
                    if (sSystemContext == null) {
                        XposedBridge.log("Failed to get system context");
                        return;
                    }
                }
            }
        }

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
                                                // ── Option A: protection = "app has a Recents card right now" ──
                                                // protected ⇔ package has a task in system RecentTasks.mTasks.
                                                // open app → card exists → skip kill;
                                                // swipe / Clear-all / recents-trim → card gone → system may kill.
                                                if (isProtectedByRecents(param.thisObject, packageName)) {
                                                    XposedBridge.log(TAG + ": checkExcessivePowerUsageLPr skip: " + packageName);
                                                    XposedBridge.log(TAG + ": recents packages: " + dumpRecentsPackages());
                                                    param.setResult(false);
                                                }
                                            }
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

        // hook NMS, and call PMS will get deadlock, this is the wrong way
        // so I wonder what's the notification chain, new notification -> wake up -> make sound, I could call PMS in the end of the chain
        // then I search how to trigger notification tone, then I find DozeTriggers.onNotification,
        // there're two way about light up screen, one is pulse, light for notification, another is wake up
        // the reason that notification will not wake screen, may be ROM disabled pulseOnNotificationEnabled
        // -> Settings.Secure.getIntForUser,


        // soft restart to apply changes, adb shell; su; stop; start;   will restart system_server

        hookPulseOnNotification(lpparam);
//        hookBootComplete(lpparam.classLoader);
    }

    private void hookPulseOnNotification(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            /*
            Settings.Secure 和 AmbientDisplayConfiguration 都在 framework（android）和 SystemUI（com.android.systemui）中都有使用
            建议同时勾选 System Framework 和 SystemUI，确保两个进程中的读取都被拦截
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings$Secure",
                cl,
                "getIntForUser",
                ContentResolver.class,
                String.class,
                int.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[1];
                        if ("doze_enabled".equals(key)) {
                            param.setResult(1); // 强制返回“已启用”
                            XposedBridge.log("AppAlive: forced DOZE_ENABLED=1");
                        }
                    }
                }
            );
            XposedHelpers.findAndHookMethod(
                    "android.hardware.display.AmbientDisplayConfiguration", // Android 14
                                cl,
                "pulseOnNotificationEnabled",
                int.class,  // user
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        // 原逻辑：boolSetting(DOZE_ENABLED, user) && pulseOnNotificationAvailable()
                        // 强制返回 true，跳过所有检查
                        return true;
                    }
                }
            );


            XposedHelpers.findAndHookMethod(
        "com.android.systemui.doze.DozeHost",
                cl,
    "isAlwaysOnSuppressed",
                new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return false; // 强制认为 AOD 没有被压制
                }
                }
            );

            */

            final String DOZE_COMPONENT =
                    "com.android.systemui/com.android.systemui.doze.DozeService";

            boolean isSystemServer = "android".equals(lpp.packageName);
            boolean isSystemUI     = "com.android.systemui".equals(lpp.packageName);

            XposedBridge.log(TAG + ": isSystemUI is " + isSystemUI);


            if (!isSystemServer && !isSystemUI) return;

            // ① THE fix: give it a doze component (also makes ambientDisplayAvailable() true)
            XposedHelpers.findAndHookMethod(
                    "android.hardware.display.AmbientDisplayConfiguration", lpp.classLoader,
                    "ambientDisplayComponent",
                    XC_MethodReplacement.returnConstant(DOZE_COMPONENT));

            // ② Kill switch used by SystemUI's shouldHeadsUpWhenDozing AND by
            //    DreamManagerService.getDozeComponent() via enabled()
            XposedHelpers.findAndHookMethod(
                    "android.hardware.display.AmbientDisplayConfiguration", lpp.classLoader,
                    "pulseOnNotificationEnabled", int.class,
                    XC_MethodReplacement.returnConstant(true));

            // Deliberately NOT touching alwaysOnEnabled() → no full AOD, pulses only.

            if (isSystemUI) {
                // ③ Now-reachable DozeTriggers: neutralize sWakeDisplaySensorState + isAlwaysOnSuppressed
                Class<?> dt = XposedHelpers.findClass(
                        "com.android.systemui.doze.DozeTriggers", lpp.classLoader);
                XposedHelpers.findAndHookMethod(dt, "onNotification", Runnable.class,
                        new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) {
                                XposedHelpers.setStaticBooleanField(dt, "sWakeDisplaySensorState", true);
                                XposedHelpers.callMethod(param.thisObject, "requestPulse",
                                        1 /* PULSE_REASON_NOTIFICATION */, false, param.args[0]);
                                return null;
                            }
                        });
                XposedHelpers.findAndHookMethod(
                        "com.android.systemui.statusbar.phone.DozeServiceHost", lpp.classLoader,
                        "isAlwaysOnSuppressed", XC_MethodReplacement.returnConstant(false));

                XposedBridge.log(TAG + ": hook isSystemUI ok");
            }

            XposedBridge.log(TAG + ": Hooked PulseOnNotification ✓");
        } catch (Throwable t) {
                XposedBridge.log(TAG + ": hookPulseOnNotification FAILED: " + t.getMessage());
            }
    }

    // cached reflection handles — system_server is a long-lived process, the
    // RecentTasks instance is created once at boot and never replaced
    private static volatile boolean sRecentsResolved = false;
    private static Object sRecentTasks = null;          // RecentTasks instance
    private static Field sTasksField = null;            // RecentTasks.mTasks
    private static volatile boolean sRecentsFailed = false; // log-once guard

    // per-pass snapshot: one check pass bursts ~50+ hook calls in <0.1s, all
    // asking the same question about the same list. Scan ONCE per burst into
    // a LinkedHashSet (keeps recents order for the dump), serve O(1) contains.
    // TTL 2s: a burst lasts <0.1s and the next pass is 5min away → always cold
    // at burst start, so every app in one pass is judged against the SAME
    // recents moment and staleness can never span two decisions.
    private static volatile java.util.Set<String> sRecentsSnapshot =
            java.util.Collections.emptySet();
    private static volatile long sRecentsSnapshotAt = 0L;
    private static final long RECENTS_SNAPSHOT_TTL_MS = 2000L;

    /**
     * Option A check: does {@code packageName} currently have a task in recents?
     * Called from the checkExcessivePowerUsageLPr hook ( AMS check cadence:
     * every 5 min, under mProcLock ). Pure in-memory scan, no blocking calls.
     */
    private static boolean isProtectedByRecents(Object ams, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;

        if (!sRecentsResolved) {
            if (!resolveRecents(ams)) {
                if (!sRecentsFailed) {
                    sRecentsFailed = true;
                    XposedBridge.log(TAG + ": isProtectedByRecents: resolve FAILED, "
                            + "fail-open (nothing protected). Will not retry-log.");
                }
                return false;
            }
            sRecentsResolved = true;
            XposedBridge.log(TAG + ": isProtectedByRecents: resolved RecentTasks ✓ ("
                    + sRecentTasks.getClass().getName() + ")");
        }

        try {
            long now = android.os.SystemClock.elapsedRealtime();
            java.util.Set<String> snapshot = sRecentsSnapshot;
            if (now - sRecentsSnapshotAt >= RECENTS_SNAPSHOT_TTL_MS) {
                snapshot = buildRecentsSnapshot();
                sRecentsSnapshot = snapshot;
                sRecentsSnapshotAt = now;
            }
            return snapshot.contains(packageName);
        } catch (Throwable t) {
            if (!sRecentsFailed) {
                sRecentsFailed = true;
                XposedBridge.log(TAG + ": isProtectedByRecents scan FAILED: " + t);
            }
            return false;
        }
    }

    /**
     * Scan mTasks once into an immutable snapshot set (recents order kept for
     * the dump). Called at most once per TTL window; each hook call in a burst
     * then does an O(1) contains().
     */
    private static java.util.Set<String> buildRecentsSnapshot() {
        java.util.Set<String> pkgs = new java.util.LinkedHashSet<>();
        try {
            @SuppressWarnings("unchecked")
            java.util.ArrayList<Object> tasks =
                    (java.util.ArrayList<Object>) sTasksField.get(sRecentTasks);
            if (tasks == null) return java.util.Collections.emptySet();
            for (Object task: tasks){
                if (task == null) continue;
                try {
                    Intent baseIntent = (Intent) XposedHelpers.callMethod(task, "getBaseIntent");
                    if (baseIntent == null || baseIntent.getComponent() == null) continue;
                    String pkgName = baseIntent.getComponent().getPackageName();
                    pkgs.add(pkgName);
                    XposedBridge.log(TAG + ": buildRecentsSnapshot : " + pkgName);
                } catch (Throwable perTask) {
                    // one weird task must not break the whole snapshot
                }
            }
        } catch (Throwable t) {
            if (!sRecentsFailed) {
                sRecentsFailed = true;
                XposedBridge.log(TAG + ": buildRecentsSnapshot FAILED: " + t);
            }
            return java.util.Collections.emptySet(); // fail-open, safe direction
        }
        return pkgs;
    }

    /**
     * Debug dump: all packages that currently have a Recents card, in recents
     * order (most recent first). Logged right after every "skip" line so you
     * can see exactly which cards earned the skip.
     * Preconditions when called: recents already resolved successfully (the
     * caller only runs this on the protected path, after isProtectedByRecents
     * returned true). Reuses the cached fields; no resolution, no locks.
     */
    private static String dumpRecentsPackages() {
        try {
            return sRecentsSnapshot.toString(); // same snapshot the check used
        } catch (Throwable t) {
            return "[DUMP_FAILED: " + t.getMessage() + ']';
        }
    }

    /** One-time reflective resolve: AMS.mActivityTaskManager.mRecentTasks + its mTasks field. */
    private static boolean resolveRecents(Object ams) {
        try {
            Object atms = XposedHelpers.getObjectField(ams, "mActivityTaskManager");
            if (atms == null) return false;
            Object recentTasks = XposedHelpers.getObjectField(atms, "mRecentTasks");
            if (recentTasks == null) return false;
            sTasksField = recentTasks.getClass().getDeclaredField("mTasks");
            sTasksField.setAccessible(true);
            sRecentTasks = recentTasks;
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": resolveRecents failed: " + t);
            return false;
        }
    }

    private Context getSystemContext(ClassLoader cl) {
        try {
            // 直接通过 ActivityThread 获取（Android 8+ 通用）
            Object currentActivityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", cl), "currentActivityThread"
            );
            if (currentActivityThread != null) {
                Context ctx = (Context) XposedHelpers.callMethod(
                        currentActivityThread, "getSystemContext");
                if (ctx != null) { return ctx; }
                // 降级：尝试 getApplication
                ctx = (Context) XposedHelpers.callMethod(currentActivityThread, "getApplication");
                return ctx;
            }
        } catch (Throwable t) {
            XposedBridge.log("getSystemContext failed: " + t);
        }
        return null;
    }

    // ── Boot-complete test trigger ──────────────────────────────
    // finishBooting() 被调用时系统已完全启动，而 NotificationManagerService
    // 在 SystemServer.startOtherServices() 阶段就已启动（早于 finishBooting），
    // 所以此时调用 callNMS_Reflection 一定能拿到 "notification" binder。
    // 测试通知（tag="fcm_intercept"）会被 Hook B 的防重入守卫跳过，不会递归触发。
    private static boolean bootTestDone = false;

    private void hookBootComplete(ClassLoader cl) {
        try {
            Class<?> ams = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", cl);
            XposedBridge.hookAllMethods(ams, "finishBooting",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (bootTestDone) return;
                                bootTestDone = true;
                                XposedBridge.log(TAG + ": Boot complete, NMS test in 5s");
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Thread.sleep(5000);
                                        } catch (InterruptedException ignored) {
                                        }
                                    }
                                }, "AppAliveBootTest").start();
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": Hooked finishBooting failed: " + t);
                        }
                        }
                    });
            XposedBridge.log(TAG + ": Hooked finishBooting ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hooked finishBooting failed: " + t);
        }
    }
}