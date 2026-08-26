package com.example.appalive;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    private static final String ACTION_FCM = "com.google.firebase.MESSAGING_EVENT";
    private static final String ACTION_GCM = "com.google.android.c2dm.intent.RECEIVE"; // legacy GCM
    private static long lastWake = 0;
    private static Context sSystemContext = null;
    private static final Object lock = new Object();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
//        if (!"android".equals(lpparam.packageName)) return;
        if (!"android".equals(lpparam.packageName) && !"system".equals(lpparam.packageName)) {
            return;
        }

        // Get context using the simplest method
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

        hookNotificationManager(lpparam.classLoader); // Hook B
        hookFcmBroadcast(lpparam.classLoader);        // Hook A
    }

    // ── Hook B: every message that becomes a notification ─────────────
    private void hookNotificationManager(ClassLoader cl) {
        try {
            Class<?> nms = XposedHelpers.findClass(
                    "com.android.server.notification.NotificationManagerService", cl);
            // 使用 findAndHookMethod 替代 hookAllMethods，因为我们需要精确匹配参数签名

            XposedBridge.hookAllMethods(nms, "enqueueNotificationInternal",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Notification n = (Notification) param.args[6];
                            if (n == null || !isMessageNotification(n)) return;
//                            wake(cl);
                            wakeScreen("NMS", cl);
                        }
                    }
            );

//            XposedHelpers.findAndHookMethod(
//                    nms,
//                    "enqueueNotificationInternal",
//                    String.class,      // pkg
//                    String.class,      // opPkg
//                    int.class,         // callingUid
//                    int.class,         // callingPid
//                    String.class,      // tag
//                    int.class,         // id
//                    Notification.class,// notification
//                    int[].class,       // idOut (注意：int数组)
//                    int.class,         // incomingUserId
//                    new XC_MethodHook() {
//                        @Override
//                        protected void beforeHookedMethod(MethodHookParam param) {
//                            if (param.args.length < 9) return;
//                            Notification n = (Notification) param.args[6];
//                            if (n == null || !isMessageNotification(n)) return;
//                            wake(cl);
//                        }
//                    }
//            );

            XposedBridge.log(TAG + ": Hooked enqueueNotificationInternal ✓");

        } catch (Throwable t) {
            XposedBridge.log("Hooked enqueueNotificationInternal failed: " + t);
        }
    }

    // ── Hook A: FCM delivery to the app (before it posts anything) ────
//    private void hookFcmBroadcast(ClassLoader cl) {
//        try {
//            Class<?> ams = XposedHelpers.findClass(
//                    "com.android.server.am.ActivityManagerService", cl);
//            XposedBridge.hookAllMethods(ams, "broadcastIntentLocked",
//                    new XC_MethodHook() {
//                        @Override
//                        protected void beforeHookedMethod(MethodHookParam param) {
//                            for (Object arg : param.args) {          // arg order drifts across versions
//                                if (!(arg instanceof Intent)) continue;
//                                String action = ((Intent) arg).getAction();
//                                if (ACTION_FCM.equals(action) || ACTION_GCM.equals(action)) {
////                                    wake(cl);                        // fires even for killed-in-recents apps
//                                    wakeScreen("FcmBroadcast", cl);
//                                }
//                                return;
//                            }
//                        }
//                    });
//
//            XposedBridge.log(TAG + ": Hooked broadcastIntentLocked ✓");
//        } catch (Throwable t) { XposedBridge.log("Hooked broadcastIntentLocked failed: " + t); }
//    }



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

    private boolean isMessageNotification(Notification n) {
        if (Notification.CATEGORY_MESSAGE.equals(n.category)) return true;
        if (hasMessagingStyle(n)) {
            return true;
        }


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

//    private void wake(ClassLoader cl) {
//        long now = SystemClock.elapsedRealtime();
//        if (now - lastWake < 3000) return;                 // IMPORTANT: both hooks fire for one message
//        lastWake = now;
//        try {
//            PowerManager pm = sSystemContext.getSystemService(PowerManager.class);
//            if (pm == null) return;
//            if (pm.isInteractive()) return; // 屏幕已亮则跳过
//
//            Method wakeUp = PowerManager.class.getMethod(
//                    "wakeUp", long.class, int.class, String.class
//            );
//            wakeUp.invoke(pm, SystemClock.uptimeMillis(),
//                    0, "SMS");
//
//
//        } catch (Throwable t) { XposedBridge.log(t); }
//    }

    private void wakeScreen(String source, ClassLoader cl) {
        if (sSystemContext == null) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastWake < 3000) return;                 // IMPORTANT: both hooks fire for one message
        lastWake = now;

        try {
            PowerManager pm = (PowerManager) sSystemContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;

            // 检查屏幕是否已亮
            try {
                boolean isInteractive = (boolean) XposedHelpers.callMethod(pm, "isInteractive");
                if (isInteractive) return;

                TelephonyManager tm = sSystemContext.getSystemService(TelephonyManager.class);
                if (tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) return;

            } catch (Throwable t) {
                // 忽略
            }

            // ═══════════════════════════════════════════════════════
            // 关键：清除调用方身份，使用 system_server 的权限
            // ═══════════════════════════════════════════════════════
            long origId = Binder.clearCallingIdentity();
            try {
                // 尝试 3 参数 wakeUp
                try {
                    XposedHelpers.callMethod(pm, "wakeUp",
                            SystemClock.uptimeMillis(),
                            1, // WAKE_REASON_APPLICATION
                            "FCM:" + source
                    );
                    lastWake = now;
                    XposedBridge.log("Screen woken (3-param) by " + source);

                    playNotificationSound(cl);


                    return;
                } catch (Throwable t) {
                    // 降级到 2 参数
                    XposedHelpers.callMethod(pm, "wakeUp",
                            SystemClock.uptimeMillis(),
                            "FCM:" + source
                    );
                    lastWake = now;

                    playNotificationSound(cl);

                    XposedBridge.log("Screen woken (2-param) by " + source);
                }
            } finally {
                // 恢复原始调用方身份
                Binder.restoreCallingIdentity(origId);
            }

        } catch (Throwable t) {
            XposedBridge.log("wakeScreen failed: " + t);
        }
    }




    private Context getSystemContext(ClassLoader cl) {
        try {
            // 直接通过 ActivityThread 获取（Android 8+ 通用）
            Class<?> activityThread = XposedHelpers.findClass(
                    "android.app.ActivityThread", cl);
            Object currentActivityThread = XposedHelpers.callStaticMethod(
                    activityThread, "currentActivityThread");

            if (currentActivityThread != null) {
                // 尝试 getSystemContext
                Context ctx = (Context) XposedHelpers.callMethod(
                        currentActivityThread, "getSystemContext");
                if (ctx != null) {
                    return ctx;
                }

                // 降级：尝试 getApplication
                ctx = (Context) XposedHelpers.callMethod(
                        currentActivityThread, "getApplication");
                return ctx;
            }
        } catch (Throwable t) {
            XposedBridge.log("getSystemContext failed: " + t);
        }
        return null;
    }

    private void playNotificationSound(ClassLoader cl) {
        try {
            // 获取系统 Context
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread",
                            cl),
                    "currentActivityThread"
            );
            Context sysCtx = (Context) XposedHelpers.callMethod(at, "getSystemContext");

            // 获取默认通知声音 URI
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

            if (soundUri == null) {
                XposedBridge.log(TAG + ": no default notification sound");
                return;
            }

            // 使用 MediaPlayer 播放
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()
            );
            mediaPlayer.setDataSource(sysCtx, soundUri);
            mediaPlayer.prepare();
            mediaPlayer.start();

            // 播放完成后自动释放
            mediaPlayer.setOnCompletionListener(MediaPlayer::release);

            XposedBridge.log(TAG + ": notification sound played");
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
                                if (!ACTION_FCM.equals(action) && !ACTION_GCM.equals(action)) {
                                    return;
                                }

                                // 获取目标包名
                                String targetPackage = intent.getPackage();
                                ComponentName component = intent.getComponent();
                                if (targetPackage == null && component != null) {
                                    targetPackage = component.getPackageName();
                                }

                                // 如果目标包名为空，尝试通过 UID 反查
                                if (targetPackage == null && callingUid != null) {
                                    targetPackage = getPackageNameByUid(callingUid);
                                }

                                if (targetPackage == null) {
                                    XposedBridge.log(TAG + ": target package is null");
                                    return;
                                }

                                // 获取应用名并显示 Toast
                                String appName = getAppName(targetPackage);
                                String displayText = "📱 " + appName + "\n📨 FCM 消息";

                                // 获取消息内容（如果有）
                                Bundle extras = intent.getExtras();
                                if (extras != null) {
                                    String title = extras.getString("gcm.n.title");
                                    String body = extras.getString("gcm.n.body");
                                    if (title != null && body != null) {
                                        displayText = "📱 " + appName +
                                                "\n📩 " + title + "\n" + body;
                                    } else if (body != null) {
                                        displayText = "📱 " + appName +
                                                "\n📩 " + body;
                                    }
                                }

                                // 显示 Toast（在 system_server 中需要特殊处理）
                                showToast(cl, displayText);

                                // 执行唤醒
                                wakeScreen("FCM from " + appName, cl);

                                XposedBridge.log(TAG + ": FCM detected for " + appName +
                                        " (" + targetPackage + ")");

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

    /**
     * 获取应用名
     */
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

    /**
     * 通过 UID 获取包名
     */
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


    /**
     * 在 system_server 中显示 Toast（需要特殊处理）
     */
    private void showToast(ClassLoader cl, String message) {
        try {
            // 在 system_server 中显示 Toast 需要使用系统 UI 线程
            // 方式1：通过 Handler 在主线程显示
            final String finalMessage = message;

            // 获取 ActivityThread 主线程 Handler
            Class<?> activityThreadClass = XposedHelpers.findClass(
                    "android.app.ActivityThread",
                    ClassLoader.getSystemClassLoader()
            );
            Object at = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            Object mainHandler = XposedHelpers.callMethod(at, "getHandler");

            // 在主线程显示 Toast
            XposedHelpers.callMethod(mainHandler, "post", new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sSystemContext == null) return;

                        // 使用系统 Toast 显示
                        Toast toast = Toast.makeText(
                                sSystemContext,
                                finalMessage,
                                Toast.LENGTH_LONG
                        );

                        // 设置 Toast 类型为系统级
                        try {
                            // Android 8.0+ 需要设置窗口类型
                            Object windowManager = XposedHelpers.callMethod(toast, "getWindowManager");
                            if (windowManager != null) {
                                // 使用 TYPE_SYSTEM_ALERT 或 TYPE_TOAST
                                int type = 0x7D3; // TYPE_SYSTEM_ALERT
                                if (Build.VERSION.SDK_INT >= 26) {
                                    type = 0x7D5; // TYPE_APPLICATION_OVERLAY
                                }
                                XposedHelpers.setIntField(windowManager, "mLayoutParams.type", type);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": set toast type failed: " + t);
                        }

                        toast.show();
                        XposedBridge.log(TAG + ": Toast shown: " + finalMessage);

                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Toast display failed: " + t);
                    }
                }
            });

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": showToast failed: " + t);
        }
    }

}