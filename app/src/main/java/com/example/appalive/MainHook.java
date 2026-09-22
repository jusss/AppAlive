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

    private static final Object sLogWriterLock = new Object();

    private static final java.text.SimpleDateFormat sTsFormat =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US);


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


    private static void logToFile(final String msg) {
        try{
            getWorker().post(new Runnable() {
                @Override public void run() {
                    try{
                        synchronized (sLogWriterLock) {

//                            String ts = new java.text.SimpleDateFormat(
//                            "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
//                            .format(new java.util.Date());

                            String ts = sTsFormat.format(new java.util.Date());


                            // system_server can not visit /sdcard
                            java.io.File f = new java.io.File("/data/system", "xposed.txt");


                            java.io.BufferedWriter writer = new java.io.BufferedWriter(
                                        new java.io.FileWriter(f, true), 8192);


//                            FileWriter writer = new FileWriter(f, true);
                            writer.write(ts + " " + msg + "\n");
                            writer.flush();
//                            writer.close();
                        }
                } catch (Throwable t) { XposedBridge.log(TAG + ": write log failed: " + t.getMessage()); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": write log failed: " + t.getMessage()); }
    }

    private static void logWriteFile(final String msg) {
        try{
                        synchronized (sLogWriterLock) {

                            String ts = new java.text.SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                                    .format(new java.util.Date());


                            // system_server can not visit /sdcard
                            java.io.File f = new java.io.File("/data/system", "xposed.txt");


                            FileWriter writer = new FileWriter(f, true);
                            writer.write(ts + " " + msg + "\n");
                            writer.flush();
                            writer.close();
                        }
        } catch (Throwable t) { XposedBridge.log(TAG + ": write log failed: " + t.getMessage()); }
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
                                            logToFile("updateAppProcessCpuTimeLPr trigger");
                                        param.setResult(false);
                                        } catch (Throwable t) {XposedBridge.log(TAG + ": checkExcessivePowerUsageLPr FAILED: " + t.getMessage());}
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
                        XposedBridge.hookMethod(method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        try{

                                            logToFile("updateAppProcessCpuTimeLPr trigger");
                                        if (param.args.length > 1 && param.args[1] instanceof Boolean) {
                                            param.args[1] = false;
                                        }

                                        } catch (Throwable t) {XposedBridge.log(TAG + ": updateAppProcessCpuTimeLPr FAILED: " + t.getMessage());}
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
                        XposedBridge.hookMethod(method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                       try{
                                        logToFile("updatePhantomProcessCpuTimeLPr trigger");
                                        if (param.args != null && param.args.length > 1 && param.args[1] instanceof Boolean) {
                                            param.args[1] = Boolean.FALSE;
                                        }
                                       } catch (Throwable t) {XposedBridge.log(TAG + ": updateAppProcessCpuTimeLPr FAILED: " + t.getMessage());}
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
                        XposedBridge.hookMethod(method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        try{
                                            logToFile("sendKillExcessiveCpuProfilingTrigger trigger");
                                            param.setResult(null);
                                        } catch (Throwable t) {XposedBridge.log(TAG + ": sendKillExcessiveCpuProfilingTrigger FAILED: " + t.getMessage());}
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
                        logToFile("checkExcessivePowerUsageLocked trigger");
                        XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING);
                        XposedBridge.log(TAG + ": Hooked checkExcessivePowerUsageLocked ✓");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": checkExcessivePowerUsageLocked FAILED: " + t.getMessage());
                    }
                };
            }
        };

        // 注意：不要在 handleLoadPackage 里调用 callNMS_Reflection 做启动测试。
        // 此时代码运行在系统启动早期，"notification" 服务尚未注册，
        // 拿到的 NotificationManager.mService 为 null，createNotificationChannel 会 NPE。

        hookNotificationManager(lpparam.classLoader);
//        hookFcmBroadcast(lpparam.classLoader);
        hookBootComplete(lpparam.classLoader);
    }

    // ── Hook B: every message that becomes a notification ─────────────
    private void hookNotificationManager(ClassLoader cl) {
        try {
            // 注意：这里不能调用 callNMS_Reflection 做启动测试。
            // hookNotificationManager 在 handleLoadPackage("android") 里被调用，
            // 时机早于 NotificationManagerService 注册 "notification" binder，
            // 此时永远拿不到 binder（不是反射的问题，是服务还没启动）。

            XposedBridge.hookAllMethods(nms, "enqueueNotificationInternal",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try{
                                logToFile("enqueueNotificationInternal trigger\n");

                                // 防重入：跳过我们自己投递的拦截通知（tag 位于 args[4]，9/10 参数签名位置一致）
                                if (param.args.length > 4 && "fcm_intercept".equals(param.args[4])) return;

                                if (param.args == null || param.args.length <= 6) return;
                                if (!(param.args[6] instanceof Notification)) return;

                                Notification n = (Notification) param.args[6];
                                if (!isMessageNotification(n)) return;
    
                                String pkg = param.args[0] instanceof String ? (String) param.args[0] : null;
                                String opPkg = (param.args.length > 1 && param.args[1] instanceof String)
                                        ? (String) param.args[1] : null;


                                logToFile("enqueueNotificationInternal trigger " + pkg + " " + opPkg + " \n");
                                if (isDuplicateMsg(pkg, opPkg)) return;
                                XposedBridge.log(TAG + ": NMS " + pkg);

                                getWorker().post(new Runnable() {
                                    @Override public void run() {

                                        // FCM 通知类消息在应用处于后台时，由 Google Play services 直接代为展示，
                                        // 系统不会发送 MESSAGING_EVENT 广播（hookFcmBroadcast 收不到这种消息）。
                                        // 通过 opPkg 识别 GMS/GSF 代发的通知，补上拦截通知。
                                        boolean fromGms = "com.google.android.gms".equals(opPkg)
                                                || "com.google.android.gsf".equals(opPkg);
                                        boolean gmsOwn = "com.google.android.gms".equals(pkg)
                                                || "com.google.android.gsf".equals(pkg);
                                        if (fromGms && !gmsOwn) {
                                            CharSequence title = n.extras != null
                                                    ? n.extras.getCharSequence(Notification.EXTRA_TITLE) : null;
                                            CharSequence text = n.extras != null
                                                    ? n.extras.getCharSequence(Notification.EXTRA_TEXT) : null;
                                            callNMS_Reflection(
                                                    title != null ? title.toString()
                                                            : (pkg != null ? getAppName(pkg) : "FCM"),
                                                    text != null ? text.toString() : "",
                                                    cl);
                                        }
                                        String source = "NMS ";
                                        if (pkg != null) { source = source + getAppName(pkg); }
                                        wakeScreen(source, cl);

                                    }
                                });

                            } catch (Throwable t) { XposedBridge.log("Hooked enqueueNotificationInternal failed: " + t); }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hooked enqueueNotificationInternal ✓");
        } catch (Throwable t) { XposedBridge.log("Hooked enqueueNotificationInternal failed: " + t); }
    }







    private boolean hasMessagingStyle(Notification n) {
        try {
            // 方法1：通过 Class.forName（不依赖 Xposed）
            Class<?> styleClass = Class.forName("android.app.Notification$MessagingStyle");
            Method extractMethod = styleClass.getMethod("extractMessagingStyleFromNotification", Notification.class);
            Object result = extractMethod.invoke(null, n);
            return result != null;
        } catch (Throwable t) {
            // Android 10 以下没有这个类
            return false;
        }
    }

    private boolean isMessageNotification(Notification n) {
        if (n == null) return false;
        if (Notification.CATEGORY_MESSAGE.equals(n.category)) return true;
        if (hasMessagingStyle(n)) { return true; }
        if (n.extras == null) return false;
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text == null) { return false; // 没有文字内容，不太可能是消息
        }
        boolean isOngoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        boolean isGroupSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        if (isOngoing || isGroupSummary) { return false; }
        // 有标题 && 有内容文本 => 大概率是消息
        boolean hasTitle = n.extras.getCharSequence(Notification.EXTRA_TITLE) != null;
        return hasTitle;
//        return n.extras.getCharSequence(Notification.EXTRA_TEXT) != null
//                && (n.flags & Notification.FLAG_ONGOING_EVENT) == 0
//                && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0;
    }

    private void wakeScreen(final String source, final ClassLoader cl) {
        // Fix 3: thread-guard — if we are not on the AppAliveWorker thread, re-post
        // ourselves there and return immediately. Belt-and-braces on top of the
        // capture-and-post design: no IPC/wakeUp can ever run on a system_server
        // hook thread (android.display / binder) again.
        if (!"AppAliveWorker".equals(Thread.currentThread().getName())) {
            final ClassLoader fCl = cl;
            getWorker().post(new Runnable() {
                @Override public void run() { wakeScreen(source, fCl); }
            });
            return;
        }
        if (sSystemContext == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastWake < 3000) return;                 // IMPORTANT: both hooks fire for one message
        lastWake = now;
        try {
            PowerManager pm = (PowerManager) sSystemContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            try {
                boolean isInteractive = (boolean) XposedHelpers.callMethod(pm, "isInteractive");
                if (isInteractive) return; // 检查屏幕是否已亮
                TelephonyManager tm = sSystemContext.getSystemService(TelephonyManager.class);
                if (tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) return;
            } catch (Throwable t) { }
            long origId = Binder.clearCallingIdentity(); // 关键：清除调用方身份，使用 system_server 的权限
            try {
                // 尝试 3 参数 wakeUp
                try {
                    XposedHelpers.callMethod(pm, "wakeUp", SystemClock.uptimeMillis(), 1, // WAKE_REASON_APPLICATION
                            "FCM:" + source
                    );
                    lastWake = now;
                    XposedBridge.log(TAG + ": " + source + ", Screen On");
                    AudioManager am = sSystemContext.getSystemService(AudioManager.class);
                    if (am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) != 0) {
                        playNotificationSound(cl); // 静音模式下只亮屏，不播放声音
                    }
                } catch (Throwable t) {
                    // 降级到 2 参数
                    XposedHelpers.callMethod(pm, "wakeUp", SystemClock.uptimeMillis(), "FCM:" + source);
                    lastWake = now;
                    AudioManager am = sSystemContext.getSystemService(AudioManager.class);
                    if (am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) != 0) { playNotificationSound(cl); }
                    XposedBridge.log(TAG + ": " + source + ", Screen On");
                }
            } finally {
                Binder.restoreCallingIdentity(origId); // 恢复原始调用方身份
            }
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

    private void playNotificationSound(ClassLoader cl) {
        try {
            if (sSystemContext == null) return;
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (soundUri == null) { XposedBridge.log(TAG + ": no default notification sound"); return; }
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()
            );
            mediaPlayer.setDataSource(sSystemContext, soundUri);
            mediaPlayer.prepare();
            mediaPlayer.start();
            mediaPlayer.setOnCompletionListener(MediaPlayer::release);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": play sound failed: " + t);
        }
    }

    private void hookFcmBroadcast(ClassLoader cl) {
        try {
            Class<?> ams = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", cl);
            XposedBridge.hookAllMethods(ams, "broadcastIntentLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 提取 Intent 和调用者信息
                                Intent intent = null;
                                String callerPackage = null;
                                Integer callingUid = null;

                                for (Object arg : param.args) {
                                    if (arg instanceof Intent) {
                                        intent = (Intent) arg;
                                    } else if (arg instanceof String) {
                                        String str = (String) arg;
                                        if (str != null && str.contains(".") && str.length() > 5) {
                                            // 可能是包名
                                            callerPackage = str;
                                        }
                                    } else if (arg instanceof Integer && (Integer) arg >= 10000) {
                                        callingUid = (Integer) arg;
                                    }
                                }

                                if (intent == null) return;

                                String action = intent.getAction();
                                if (!ACTION_FCM.equals(action) && !ACTION_GCM.equals(action)) { return; }

                                // 获取目标包名
                                String targetPackage = intent.getPackage();
                                ComponentName component = intent.getComponent();
                                if (targetPackage == null && component != null) { targetPackage = component.getPackageName(); }

                                // 如果目标包名为空，尝试通过 UID 反查
                                if (targetPackage == null && callingUid != null) { targetPackage = getPackageNameByUid(callingUid); }

                                if (targetPackage == null) { XposedBridge.log(TAG + ": target package is null"); return; }
                                String appName = getAppName(targetPackage);
                                String displayText = appName + " FCM ";
                                String title = null;
                                String body = null;

                                // 获取消息内容（如果有）
                                Bundle extras = intent.getExtras();
                                if (extras != null) {
                                    String[] titleKeys = {"gcm.n.title", "title", "notification.title"};
                                    String[] bodyKeys = {"gcm.n.body", "body", "notification.body"};
                                    for (String key : titleKeys) { if (extras.containsKey(key)) { title = extras.getString(key); } }
                                    for (String key2: bodyKeys) { if (extras.containsKey(key2)) { body = extras.getString(key2); } }
                                    if (title != null){ displayText = displayText + "title: " + title; }
                                    if (body != null){ displayText = displayText + " content: " + body; }
                                }
                                // showToast(cl, displayText);
                                callNMS_Reflection(appName, displayText, cl);
                                wakeScreen("FCM " + appName, cl);
                                XposedBridge.log(TAG + ": FCM " + appName + " (" + targetPackage + ")");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": Error in hook: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Hooked broadcastIntentLocked ✓");
        } catch (Throwable t) {
            XposedBridge.log("Hooked broadcastIntentLocked failed: " + t);
        }
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

                                logToFile("start\n");
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

    private String getAppName(String packageName) {
        try {
            if (sSystemContext == null) return packageName;
            PackageManager pm = sSystemContext.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            XposedBridge.log(TAG + ": getAppName failed for " + packageName + ": " + e);
            return packageName;
        }
    }

    // 通过 UID 获取包名
    private String getPackageNameByUid(int uid) {
        try {
            if (sSystemContext == null) return null;
            PackageManager pm = sSystemContext.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                return packages[0];
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": getPackageNameByUid failed: " + e);
        }
        return null;
    }

    private void showToast(ClassLoader cl, String message) {
        try {
            // 在 system_server 中显示 Toast 需要使用系统 UI 线程
            // 方式1：通过 Handler 在主线程显示
            final String finalMessage = message;

            // 获取 ActivityThread 主线程 Handler
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", ClassLoader.getSystemClassLoader());
            Object at = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            Object mainHandler = XposedHelpers.callMethod(at, "getHandler");

            // 在主线程显示 Toast
            XposedHelpers.callMethod(mainHandler, "post", new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sSystemContext == null) return;
                        // 使用系统 Toast 显示
                        Toast toast = Toast.makeText(sSystemContext, finalMessage, Toast.LENGTH_LONG);
                        toast.show();
                        XposedBridge.log(TAG + ": " + finalMessage + "\n");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Toast display failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": showToast failed: " + t);
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

    private boolean isDuplicateMsg(String pkg, String opPkg) {
        String key = (pkg == null ? "?" : pkg) + "|" + (opPkg == null ? "?" : opPkg);
        long now = SystemClock.elapsedRealtime();
        synchronized (sLastWakeByApp) {                       // 用 map 本身当锁，粒度足够粗也最简单
            Long last = sLastWakeByApp.get(key);
            if (last != null && now - last < DEBOUNCE_WINDOW_MS) {
                return true;                                 // 3 秒内同源重复 → 过滤
            }
            sLastWakeByApp.put(key, now);                     // 首次 / 超时 → 更新时间戳并放行
            return false;
        }
    }
}
