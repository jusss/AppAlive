package com.example.appalive;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
    // NMS -> screen off -> wakeup screen, play sound, no toast
    //     -> screen on -> nothing

    // FCM -> screen off -> toast/notification, wakeup screen, play sound
    //     -> screen on -> toast/notification, nothing
    // keytool -genkeypair -v -keystore AppAlive/app/release.jks -alias appalive -keyalg RSA -keysize 2048 -validity 10000 -storepass 123 -keypass 123

    private static final String ACTION_FCM = "com.google.firebase.MESSAGING_EVENT";
    private static final String ACTION_GCM = "com.google.android.c2dm.intent.RECEIVE"; // legacy GCM
    private static long lastWake = 0;
    private static Context sSystemContext = null;
    private static final Object lock = new Object();
    private static int notificationId = 0;
    private static Class<?> nms = null;
    // 缓存：NotificationManagerService 与 system_server 同进程同生命周期，
    // binder 是进程级单例，system_server 不重启它就永远有效；失败时置 null 重取。
    private static volatile NotificationManager sNms = null;
    private static final long DEBOUNCE_WINDOW_MS = 3000;
    // key = pkg + "|" + opPkg  → 该组合最近一次放行时间
    // 用 ConcurrentHashMap：binder 多线程并发访问安全；条目数 ≈ 应用数量，不会无限增长
    private static final java.util.Map<String, Long> sLastWakeByApp =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Handler sWorkerHandler = null;
    private static final Object sWorkerHandlerLock = new Object();

    private static volatile PowerManager spm = null;


    private static Handler getWorker() {
        synchronized (sWorkerHandlerLock) {
            if (sWorkerHandler == null) {
                HandlerThread ht = new HandlerThread("AppAliveWorker");
                ht.start();
                sWorkerHandler = new Handler(ht.getLooper());
            }
            return sWorkerHandler;
        }
    }

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

        try{
            nms = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", lpparam.classLoader);
        } catch (Throwable t) { XposedBridge.log(TAG + ": get NMS FAILED: " + t.getMessage()); }

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
                                                // (spec: wechat can die if it has no recents card)
                                                if (isProtectedByRecents(param.thisObject, packageName)) {
                                                    XposedBridge.log(TAG + ": checkExcessivePowerUsageLPr skip: " + packageName);
                                                    XposedBridge.log(TAG + ": recents packages: " + dumpRecentsPackages());
                                                    param.setResult(false);
                                                }
                                                // ── old impl kept for rollback: file/DEFAULTS-seeded event set ──
                                                // if (ProtectedApps.contains(packageName)) {
                                                //     XposedBridge.log(TAG + ": checkExcessivePowerUsageLPr skip: " + packageName);
                                                //     param.setResult(false);
                                                // }
                                            }
                                            // debug: dump() belonged to the old ProtectedApps impl
                                            // ProtectedApps.dump();
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

                if (method.getName().equals("sendKillExcessiveCpuProfilingTrigger")){
                    // ─── Hook 4: sendKillExcessiveCpuProfilingTrigger → no-op ───
                    // this may in com.android.server.am.AppProfiler in android 11-15, different OEM may have different parameters
                    try {
                        XposedBridge.hookMethod(method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        try{
                                            param.setResult(null);
                                        } catch (Throwable t) {
                                            XposedBridge.log(TAG + ": sendKillExcessiveCpuProfilingTrigger FAILED: " + t.getMessage());
                                        }
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
                        XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING);
                        XposedBridge.log(TAG + ": Hooked checkExcessivePowerUsageLocked ✓");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": checkExcessivePowerUsageLocked FAILED: " + t.getMessage());
                    }
                };
            }
        };

        // ── old event-driven keep-alive (ProtectedApps) — disabled, kept for rollback ──
        // seeded once from KeepAliveConfig file/DEFAULTS, then maintained by the
        // app-resume / recents-swipe hooks below. Replaced by Option A
        // (isProtectedByRecents): the system's own RecentTasks list is the source of
        // truth — no custom list, no file, no config.
        // ProtectedApps.seedIfNeeded();
        // hookAppResumed(lpparam.classLoader);
        // hookTaskRemovedFromRecents(lpparam.classLoader);

        // 注意：不要在 handleLoadPackage 里调用 callNMS_Reflection 做启动测试。
        // 此时代码运行在系统启动早期，"notification" 服务尚未注册，
        // 拿到的 NotificationManager.mService 为 null，createNotificationChannel 会 NPE。

        hookNotificationManager(lpparam.classLoader);
        hookBootComplete(lpparam.classLoader);
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

    // ═══════════════════════════════════════════════════════════════════════
    // ── Option A: "is this package protected?" = "does it have a Recents card?" ──
    //
    // No custom list at all. At check time (system_server's 5-min excessive-CPU
    // audit, CHECK_EXCESSIVE_POWER_USE_MSG → checkExcessivePowerUsage → this hook),
    // walk the system's LIVE recents list and see if any task belongs to the package.
    //
    //   lifecycle (all owned by the system, nothing for us to maintain):
    //     app opened / resumed      → recents add(Task)   → card exists → protected
    //     user swipes card / Clear all → recents remove(Task) → card gone → can die
    //     recents cap trim (48, ActivityManager.getMaxRecentTasksStatic)
    //                               → oldest tasks silently dropped → can die
    //     reboot                    → recents persisted to disk & restored → protection survives
    //
    // verified field paths (android-14.0.0_r1):
    //   ActivityManagerService.mActivityTaskManager      AMS.java:1535  (public ATMS)
    //   ActivityTaskManagerService.mRecentTasks          ATMS.java:442  (private RecentTasks)
    //   RecentTasks.mTasks                               RT.java:181    (private final ArrayList<Task>)
    //   Task.getBaseIntent()                             Task.java:1324
    //   AOSP's own package extraction: task.getBaseIntent().getComponent().getPackageName()
    //     (same pattern at RecentTasks.java:1854-1856)
    //
    // cost: ≤48 elements, ~µs each, once per 5 minutes ≈ negligible (measured
    // against the surrounding audit which iterates every app process). No locks
    // taken, no binder, no I/O → cannot stall the system_server thread.
    //
    // failure mode: if any reflection step fails (OEM ROM drift), log ONCE and
    // return false → fail-open (nothing protected, system free to kill), the
    // safe direction for this module. A repeated failure every 5 min does not
    // spam the log thanks to the once-guard.
    // ═══════════════════════════════════════════════════════════════════════

    // cached reflection handles — system_server is a long-lived process, the
    // RecentTasks instance is created once at boot and never replaced
    private static volatile boolean sRecentsResolved = false;
    private static Object sRecentTasks = null;          // RecentTasks instance
    private static Field sTasksField = null;            // RecentTasks.mTasks
    private static volatile boolean sRecentsFailed = false; // log-once guard

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
            @SuppressWarnings("unchecked")
            java.util.ArrayList<Object> tasks =
                    (java.util.ArrayList<Object>) sTasksField.get(sRecentTasks);
            if (tasks == null) return false;
            for (int i = 0; i < tasks.size(); i++) {
                Object task = tasks.get(i);
                if (task == null) continue;
                Intent baseIntent = (Intent) XposedHelpers.callMethod(task, "getBaseIntent");
                if (baseIntent == null) continue;
                ComponentName cn = baseIntent.getComponent();
                if (cn == null) continue;
                if (packageName.equals(cn.getPackageName())) return true;
            }
        } catch (Throwable t) {
            if (!sRecentsFailed) {
                sRecentsFailed = true;
                XposedBridge.log(TAG + ": isProtectedByRecents scan FAILED: " + t);
            }
            return false;
        }
        return false;
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
        StringBuilder sb = new StringBuilder("[");
        try {
            @SuppressWarnings("unchecked")
            java.util.ArrayList<Object> tasks =
                    (java.util.ArrayList<Object>) sTasksField.get(sRecentTasks);
            if (tasks == null) return sb.append("null]").toString();
            for (int i = 0; i < tasks.size(); i++) {
                Object task = tasks.get(i);
                if (task == null) continue;
                try {
                    Intent baseIntent = (Intent) XposedHelpers.callMethod(task, "getBaseIntent");
                    if (baseIntent == null || baseIntent.getComponent() == null) continue;
                    if (sb.length() > 1) sb.append(", ");
                    sb.append(baseIntent.getComponent().getPackageName());
                } catch (Throwable perTask) {
                    // one weird task must not break the whole dump
                }
            }
        } catch (Throwable t) {
            return sb.append("DUMP_FAILED: ").append(t.getMessage()).append(']').toString();
        }
        return sb.append(']').toString();
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

    // ═══════════════════════════════════════════════════════════════════════
    // ── OLD event-driven impl (Option A predecessor) — kept for rollback ──
    // hookAppResumed / hookTaskRemovedFromRecents / unprotectTaskArg below are
    // NO LONGER CALLED (see handleLoadPackage). Their call sites and the
    // ProtectedApps check in checkExcessivePowerUsageLPr are commented out.
    // ProtectedApps.java / KeepAliveConfig.java are untouched dead code.
    // ═══════════════════════════════════════════════════════════════════════

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

    // ── Hook B: every message that becomes a notification ─────────────
    private void hookNotificationManager(ClassLoader cl) {
        try {
            // 注意：这里不能调用 callNMS_Reflection 做启动测试。
            // hookNotificationManager 在 handleLoadPackage("android") 里被调用，
            // 时机早于 NotificationManagerService 注册 "notification" binder，
            // 此时永远拿不到 binder（不是反射的问题，是服务还没启动）。

            // beforeHookedMethod on enqueueNotificationInternal, get into this method, will get NMS notification lock,
            //
            /*
            T0: 系统线程进入 enqueueNotificationInternal，获取 mNotificationLock
            T1: 系统线程执行到 beforeHookedMethod（hook 注入点）
            T2: hook 里 getWorker().post(...) 把任务丢给 worker
            T3: beforeHookedMethod 返回
            T4: 系统线程继续执行 enqueueNotificationInternal 方法体（仍然持有 mNotificationLock）
                   ↑ 这中间可能有耗时操作，锁一直没释放
            T5: worker 线程被唤醒，执行 wakeScreen → pm.isInteractive()
                   ↑ 此时 NMS 锁可能还持有！
            T6: worker 线程持有"逻辑上的 NMS 锁等待"关系 → 等待 PMS 锁


            注意：beforeHookedMethod 是 Xposed 在原方法真正开始执行之前调用的。但 Xposed 的 hook 注入点通常是在方法入口，此时如果原方法是 synchronized 的，锁已经获取了（因为 synchronized 是方法级别的，锁在方法入口就拿了）。beforeHookedMethod 返回后，原方法的方法体才开始执行，锁一直持有到方法体结束。
            所以：worker 线程完全可能在一个“NMS 锁仍被系统线程持有”的时间窗口内去调用 isInteractive()。
            post 到 worker 解决的是“在 hook 线程上阻塞”的问题，但它没有解决“在 NMS 锁被持有的期间，另一个线程发起 PMS Binder 调用”的问题。

锁的死锁条件不是“同一个线程同时持有两把锁”，而是：

线程 A（系统线程）持有 NMS 锁

线程 B（worker 线程）调用 PMS，PMS 内部需要 PMS 锁

某个线程 C 持有 PMS 锁，并且需要 NMS 锁

只要这三个条件在时间上重叠，就会死锁。worker 线程的 isInteractive() 调用和系统线程持有 NMS 锁的时间窗口重叠，就构成条件。

callNMS_Reflection 调用 nms_obj.notify(...)，这会重新进入 NMS。虽然 pkg=="android" 的守卫阻止了递归，但 notify 本身会尝试获取 NMS 锁。此时 worker 线程：

 持有 NMS 锁 -> 持有 PMS 锁（isInteractive 返回后可能还没释放？） → 尝试获取 NMS 锁
或者反过来。这直接构成 worker 线程和系统线程之间的锁循环。

真正安全的做法
要让 worker 线程的 PMS 调用永远不会和 NMS 锁重叠，只有两条路：

不在 beforeHookedMethod 里做任何事，只记录，让 worker 异步执行——但如上所述，worker 仍可能和系统线程重叠，只是概率低。这不是根本解法。

在 afterHookedMethod 里 post。此时 enqueueNotificationInternal 已经返回，NMS 锁已经释放。这是根本解法：

afterHookedMethod 在原方法返回之后调用，此时 synchronized 块已经退出，NMS 锁已释放。worker 线程执行 isInteractive() 时，系统里没有任何线程因为这次 NMS 调用而持有 NMS 锁，锁循环的其中一条边被切断。

             */


            XposedHelpers.findAndHookMethod(
        "com.android.server.notification.NotificationManagerService", // 类名
                cl,
        "enqueueNotificationInternal",
        // 按顺序声明 9 个参数的类型
                String.class,   // pkg
                String.class,   // opPkg
                int.class,      // callingUid
                int.class,      // callingPid
                String.class,   // tag
                int.class,      // id
                Notification.class, // notification
                int.class,      // incomingUserId
                boolean.class,  // postSilently (9 参数版特有)
                new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try{
                    /* android 14.0
                   8-params,
                   void enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid, final int callingPid, final String tag, final int id,
                                final Notification notification, int incomingUserId)
                   9-params,
                   void enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid, final int callingPid,
                        final String tag, final int id, final Notification notification, int incomingUserId, boolean postSilently)

                   10-params,
                   private boolean enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid, final int callingPid, final String tag,
                        final int id, final Notification notification, int incomingUserId, boolean postSilently, PostNotificationTracker tracker)
                    */
                    String pkg = (String) param.args[0];
                    String tag = (String) param.args[4];

                    if (pkg.equals("com.android.vending")) return;
                    if (pkg.equals("android")) return;
                    if (pkg.equals("com.brave.browser")) return;
                    if (pkg.equals("com.kimcy929.secretvideorecorder")) return;

                    // 防重入：跳过我们自己投递的拦截通知（tag 位于 args[4]，9/10 参数签名位置一致）
                    if ("fcm_intercept".equals(param.args[4])) return;

                    final String fPkg = pkg;
                    final String fTag = tag;

                    getWorker().post(new Runnable() {
                        @Override public void run() {
                            Notification n = (Notification) param.args[6];
                            if (!isMessageNotification(n)) return;

//                    XposedBridge.log(TAG + ": NMS " + pkg);

//                            callNMS_Reflection(fPkg, fTag, cl);
                            wakeScreen("pkg: " + fPkg + ", tag: " + fTag, cl);
                        }
                    });
                    } catch (Throwable t) {
                        XposedBridge.log("Hooked enqueueNotificationInternal failed: " + t);
                    }
                }
            }
            );
        } catch (Throwable t) {
            XposedBridge.log("Hooked enqueueNotificationInternal failed: " + t);
        }
    }

    private void wakeScreen(final String source, final ClassLoader cl) {
        // Fix 3: thread-guard — if we are not on the AppAliveWorker thread, re-post
        // ourselves there and return immediately. Belt-and-braces on top of the
        // capture-and-post design: no IPC/wakeUp can ever run on a system_server
        // hook thread (android.display / binder) again.
        if (!"AppAliveWorker".equals(Thread.currentThread().getName())) {
//            final ClassLoader fCl = cl;
//            getWorker().post(new Runnable() {
//                @Override public void run() { wakeScreen(source, fCl); }
//            });
            return;
        }
        if (sSystemContext == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastWake < 3000) return;                 // IMPORTANT: both hooks fire for one message
        lastWake = now;

        try {
            PowerManager pms_obj = spm;
            if (pms_obj == null) {
                pms_obj = getSystemPowerManager(cl);
                if (pms_obj == null) {
                    XposedBridge.log(TAG + ": PowerManager got failed, skip wake up screen");
                    return;
                }
                spm = pms_obj;
            }

            boolean isInteractive = (boolean) XposedHelpers.callMethod(pms_obj, "isInteractive");
            if (isInteractive) return; // 检查屏幕是否已亮

            PowerManager.WakeLock wakeLock = pms_obj.newWakeLock(
    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "AppAlive:WakeScreen"
            );

            XposedBridge.log(TAG + ": wake up screen by " + source);
            // Acquire with a timeout to be safe
            wakeLock.acquire(5000); // 5 seconds
        } catch (Throwable t) {
            XposedBridge.log("wakeScreen failed: " + t);
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
                                        callNMS_Reflection("Boot test", "NMS reflection OK after boot", cl);
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

    private void callNMS_Reflection(String title, String content, ClassLoader cl) {
        try {
            if (nms==null) return;

            // 关键修复：不要用 sSystemContext.getSystemService(NOTIFICATION_SERVICE)。
            // 当 "notification" 服务尚未注册时它返回的 NotificationManager.mService 为 null
            // （且坏实例可能被 SystemServiceRegistry 缓存），createNotificationChannel 会 NPE。
            // 改用 getSystemNotificationManager 现取 binder（结果缓存到 sNms，见字段注释）。
            NotificationManager nms_obj = sNms;
            if (nms_obj == null) {
                nms_obj = getSystemNotificationManager(cl);
                if (nms_obj == null) {
                    XposedBridge.log(TAG + ": NMS binder not ready, skip notification");
                    return;
                }
                sNms = nms_obj;
            }

            long ident = Binder.clearCallingIdentity();
            try {
                // register channel first (public, 2-param)
                NotificationChannel ch = new NotificationChannel("fcm_intercept_channel",
                        "FCM Intercept", NotificationManager.IMPORTANCE_HIGH);
                nms_obj.createNotificationChannel(ch);

                Notification notification = new Notification.Builder(sSystemContext, "fcm_intercept_channel")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_MESSAGE)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .build();

                // int notificationId = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                notificationId = (notificationId + 1) & 0x7FFFFFFF;   // static field, replaces previous card
                nms_obj.notify("fcm_intercept", notificationId, notification);

            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            // XposedBridge.log(TAG + ": NMS call succeeded ✅");
        } catch (Throwable t) {
            sNms = null; // 调用失败（如 binder 死亡），丢弃缓存，下次重新获取
            XposedBridge.log(TAG + ": Reflection call failed: " + t);
        }
    }

    private NotificationManager getSystemNotificationManager(ClassLoader cl) {
        // 兜底：boot 完成后 SystemServiceRegistry 里的 service 已经是可用的了
        try {
            if (sSystemContext == null) return null;
            Object nm = sSystemContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm instanceof NotificationManager) {
                XposedBridge.log(TAG + ": Got system NotificationManager via getSystemService ✅");
                return (NotificationManager) nm;
            }
        } catch (Throwable t2) {
            XposedBridge.log(TAG + ": getSystemNotificationManager failed: " + t2);
        }
        return null;
    }

    private PowerManager getSystemPowerManager(ClassLoader cl) {
        try {
            if (sSystemContext == null) return null;
            Object pm = sSystemContext.getSystemService(Context.POWER_SERVICE);
            if (pm instanceof PowerManager) {
                XposedBridge.log(TAG + ": Got PowerManager via getSystemService ✅");
                return (PowerManager) pm;
            }
        } catch (Throwable t2) {
            XposedBridge.log(TAG + ": get PowerManager failed: " + t2);
        }
        return null;
    }

    private boolean isMessageNotification(Notification n) {
        if (n == null) return false;
        if (Notification.CATEGORY_MESSAGE.equals(n.category)) return true;
        if (hasMessagingStyle(n)) {
            return true;
        }

        if (n.extras == null) return false;

        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text == null) {
            return false; // 没有文字内容，不太可能是消息
        }

        boolean isOngoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        boolean isGroupSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;

        if (isOngoing || isGroupSummary) {
            return false;
        }

        // 有标题 && 有内容文本 => 大概率是消息
        boolean hasTitle = n.extras.getCharSequence(Notification.EXTRA_TITLE) != null;
        return hasTitle;

//        return n.extras.getCharSequence(Notification.EXTRA_TEXT) != null
//                && (n.flags & Notification.FLAG_ONGOING_EVENT) == 0
//                && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0;
    }

    private boolean hasMessagingStyle(Notification n) {
        try {
            // 方法1：通过 Class.forName（不依赖 Xposed）
            Class<?> styleClass = Class.forName("android.app.Notification$MessagingStyle");
            Method extractMethod = styleClass.getMethod(
                    "extractMessagingStyleFromNotification",
                    Notification.class
            );
            Object result = extractMethod.invoke(null, n);
            return result != null;

        } catch (ClassNotFoundException e) {
            // Android 10 以下没有这个类
            return false;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
