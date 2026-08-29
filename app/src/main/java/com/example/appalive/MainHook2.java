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
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook2 implements IXposedHookLoadPackage {

    private static final String TAG = "AppAlive";
    // AOSP source code https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=602?q=ActivityManagerService&sq=
    // lineage 18.1 source code https://github.com/LineageOS/android_frameworks_base/blob/lineage-18.1/services/core/java/com/android/server/am/ActivityManagerService.java
    // NMS -> screen off -> wakeup screen, play sound, no toast
    //     -> screen on -> nothing

    // FCM -> screen off -> toast/notification, wakeup screen, play sound
    //     -> screen on -> toast/notification, nothing


    private static final String ACTION_FCM = "com.google.firebase.MESSAGING_EVENT";
    private static final String ACTION_GCM = "com.google.android.c2dm.intent.RECEIVE"; // legacy GCM
    private static long lastWake = 0;
    private static Context sSystemContext = null;
    private static final Object lock = new Object();
    private static int notificationId = 0;
    private static Object sNms = null; // NotificationManagerService 单例（进程内直接投递用）

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

            // 缓存 NMS 单例，供 showSystemNotification 在进程内直接投递通知
            try {
                sNms = XposedHelpers.getStaticObjectField(nms, "sSelf");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": NMS sSelf not found, will use thisObject fallback");
            }

            // hookAllMethods 会命中所有重载（9/10 参数），回调内逻辑对参数位置是兼容的

            XposedBridge.hookAllMethods(nms, "enqueueNotificationInternal",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 兜底：hook 回调里的 thisObject 一定是当前进程的 NMS 实例
                            if (sNms == null) sNms = param.thisObject;

                            // 防重入：跳过我们自己投递的拦截通知（tag 位于 args[4]，9/10 参数签名位置一致）
                            if (param.args.length > 4 && "fcm_intercept".equals(param.args[4])) return;

                            Notification n = (Notification) param.args[6];
                            if (n == null || !isMessageNotification(n)) return;
//                            wake(cl);
                            String source = "NMS ";
                            if (param.args[0] instanceof String){
                                if (param.args[0] != null) {
                                    source = source +  getAppName((String) param.args[0]);
                                }
                            }

                            wakeScreen(source, cl);
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
                    XposedBridge.log(TAG + ": Screen woken by " + source);

                    AudioManager am = sSystemContext.getSystemService(AudioManager.class);
                    if (am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) != 0) {
                        // 静音模式下只亮屏，不播放声音
                        playNotificationSound(cl);
                    }

                    return;
                } catch (Throwable t) {
                    // 降级到 2 参数
                    XposedHelpers.callMethod(pm, "wakeUp",
                            SystemClock.uptimeMillis(),
                            "FCM:" + source
                    );
                    lastWake = now;

                    AudioManager am = sSystemContext.getSystemService(AudioManager.class);
                    if (am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) != 0) {
                        // 静音模式下只亮屏，不播放声音
                        playNotificationSound(cl);
                    }

                    XposedBridge.log(TAG + ": Screen woken by " + source);
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

//            XposedBridge.log(TAG + ": notification sound played");
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
                                String displayText = appName + " FCM ";
                                String title = null;
                                String body = null;

                                // 获取消息内容（如果有）
                                Bundle extras = intent.getExtras();
                                if (extras != null) {
                                    String[] titleKeys = {"gcm.n.title", "title", "notification.title"};
                                    String[] bodyKeys = {"gcm.n.body", "body", "notification.body"};

                                    for (String key : titleKeys) {
                                        if (extras.containsKey(key)) {
                                            title = extras.getString(key);
                                        }
                                    }

                                    for (String key2: bodyKeys) {
                                        if (extras.containsKey(key2)) {
                                            body = extras.getString(key2);
                                        }
                                    }

                                    if (title != null){
                                        displayText = displayText + "title: " + title;
                                    }

                                    if (body != null){
                                        displayText = displayText + " content: " + body;
                                    }
                                }

                                // 显示 Toast（在 system_server 中需要特殊处理）
                                // showToast(cl, displayText);

                                showSystemNotification(appName, displayText, cl);

                                // 已废弃：callNMS_Reflection 调用骨架（语法错误无法编译，注释保留）
//    				callNMS_Reflection(Object nms, String pkg, String opPkg,
//                                    int callingUid, int callingPid,
//                                    String tag, int id, Notification notification,
//                                    int userId)


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

                        toast.show();
                        XposedBridge.log(TAG + ": Toast shown: " + finalMessage + "\n");

                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Toast display failed: " + t);
                    }
                }
            });

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": showToast failed: " + t);
        }
    }

    /**
     * 按方法名 + 参数个数反射查找方法（含父类，避免不同版本参数漂移导致找不到）
     */
    private Method findMethodByParamCount(Class<?> clazz, String name, int paramCount) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                    m.setAccessible(true); // enqueueNotificationInternal 是包私有方法，必须 setAccessible
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 不经过 NotificationManager（其内部 INotificationManager binder 为 null），
     * 直接调用 NMS 的 public createNotificationChannels(String, ParceledListSlice) 注册渠道。
     * 注意：必须在 clearCallingIdentity() 之后调用。
     */
    private void ensureChannel(Object nms, ClassLoader cl) throws Exception {
        NotificationChannel ch = new NotificationChannel("fcm_intercept_channel",
                "FCM 消息拦截", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("FCM interception notices");

        Class<?> parceledListSliceClass = XposedHelpers.findClass(
                "android.content.pm.ParceledListSlice",
                cl  // 使用传入的 ClassLoader
        );

        // 创建 List 包装
        List<NotificationChannel> channelList = Collections.singletonList(ch);

        // 通过反射调用构造函数
        Object slice = XposedHelpers.newInstance(
                parceledListSliceClass,
                channelList
        );





        Method m = findMethodByParamCount(nms.getClass(), "createNotificationChannels", 2);
        if (m == null) {
            XposedBridge.log(TAG + ": createNotificationChannels(2) not found, channel skipped");
            return;
        }
        m.invoke(nms, "android", slice);
    }

    private void showSystemNotification(String title, String content, ClassLoader cl) {
        try {
            if (sSystemContext == null) return;
            if (sNms == null) {
                XposedBridge.log(TAG + ": NMS not ready, skip notification");
                return;
            }

            Notification n = new Notification.Builder(sSystemContext, "fcm_intercept_channel")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .build();


            int USER_SYSTEM = XposedHelpers.getStaticIntField(
                    android.os.UserHandle.class, "USER_SYSTEM"
            );

            // 清除调用方身份：hook 运行在 broadcastIntentLocked 中，
            // 当前 binder uid 是 FCM 发送方，NMS 内部会做
            // checkCallerIsSystemOrSameApp("android")，必须让 uid 读成 system(1000)
            long ident = Binder.clearCallingIdentity();
            try {
                ensureChannel(sNms, cl);  // 用 NMS 实例直接注册渠道，绕过 NotificationManager（其内部 binder 为 null）

                notificationId = (notificationId + 1) & 0x7FFFFFFF;

                // 按参数个数反射定位 Android 14 的 10 参数 enqueueNotificationInternal
                Method enqueue = findMethodByParamCount(
                        sNms.getClass(), "enqueueNotificationInternal", 10);
                if (enqueue == null) {
                    XposedBridge.log(TAG + ": 10-param enqueueNotificationInternal not found");
                    return;
                }

                enqueue.invoke(sNms,
                        "android",                          // pkg
                        "android",                          // opPkg
                        android.os.Process.SYSTEM_UID,      // callingUid
                        android.os.Process.myPid(),         // callingPid
                        "fcm_intercept",                    // tag（固定，替换上一张卡片）
                        notificationId,                     // id
                        n,                                  // notification
                        new int[]{notificationId},          // idOut
                        USER_SYSTEM,  // incomingUserId
                        true);                              // allowForegroundService (第10参，Android 12+)

                XposedBridge.log(TAG + ": interception notification posted");
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": showSystemNotification failed: " + t);
        }
    }

    /*
     * ════════════════════════════════════════════════════════════════
     * [BROKEN SKELETON — 保留作参考，勿再调用]
     *   1. nms.getClass() 返回 java.lang.Class：遍历的是 Class 自身的方法，永远找不到目标方法
     *   2. method.invoke(nms, args) 的接收者是 Class 对象而非 NMS 实例（正确应为 sNms）
     *   3. hookFcmBroadcast 中的调用语句是参数声明，语法错误，从未真正执行过
     * 正确实现见 showSystemNotification / findMethodByParamCount / ensureChannel。
     * ════════════════════════════════════════════════════════════════
    private boolean callNMS_Reflection(String pkg, String opPkg,
                                       int callingUid, int callingPid,
                                       String tag, int id, Notification notification,
                                       int userId, ClassLoader cl) {
        try {

            int USER_SYSTEM = XposedHelpers.getStaticIntField(
                    android.os.UserHandle.class, "USER_SYSTEM"
            );


            Class<?> nms = XposedHelpers.findClass(
                    "com.android.server.notification.NotificationManagerService", cl);

            Method[] methods = nms.getClass().getDeclaredMethods();

            for (Method method : methods) {
                if (!method.getName().equals("enqueueNotificationInternal")) continue;

                method.setAccessible(true);
                Class<?>[] paramTypes = method.getParameterTypes();
                int paramCount = paramTypes.length;

                XposedBridge.log(TAG + "Found method with " + paramCount + " params");

                // 检查参数类型是否匹配
                if (paramCount < 7) continue;
                if (!paramTypes[0].equals(String.class)) continue;
                if (!paramTypes[1].equals(String.class)) continue;
                if (!paramTypes[2].equals(int.class)) continue;
                if (!paramTypes[3].equals(int.class)) continue;
                if (!paramTypes[4].equals(String.class)) continue;
                if (!paramTypes[5].equals(int.class)) continue;
                if (!paramTypes[6].equals(Notification.class)) continue;

                // 构建参数数组
                Object[] args = new Object[paramCount];
                args[0] = "android";
                args[1] = "android";
                args[2] = android.os.Process.SYSTEM_UID;
                args[3] = android.os.Process.myPid();
                args[4] = "fcm_intercept_" + System.currentTimeMillis();
                args[5] = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                args[6] = notification;

                // 填充第7个参数 (通常是 idOut)
                if (paramCount > 7) {
                    if (paramTypes[7] == int[].class) {
                        args[7] = new int[]{id};
                    } else if (paramTypes[7] == int.class) {
                        args[7] = id;
                    } else {
                        args[7] = null;
                    }
                }

                // 填充第8个参数 (通常是 userId)
                if (paramCount > 8) {
                    if (paramTypes[8] == int.class) {
                        args[8] = USER_SYSTEM;
                    } else if (paramTypes[8] == String.class) {
                        args[8] = "";
                    } else {
                        args[8] = null;
                    }
                }

                // 填充第9个及以后的参数 (flags 等)
                for (int i = 9; i < paramCount; i++) {
                    if (paramTypes[i] == int.class) {
                        args[i] = 0;
                    } else if (paramTypes[i] == long.class) {
                        args[i] = 0L;
                    } else if (paramTypes[i] == boolean.class) {
                        args[i] = false;
                    } else if (paramTypes[i] == String.class) {
                        args[i] = "";
                    } else {
                        args[i] = null;
                    }
                }

                // 执行调用
                method.invoke(nms, args);
                XposedBridge.log(TAG + "✅ NMS call succeeded with " + paramCount + " params");
                return true;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Reflection call failed: " + t);
        }
        return false;
    }
*/


}