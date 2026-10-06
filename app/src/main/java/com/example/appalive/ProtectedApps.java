package com.example.appalive;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Dynamic keep-alive list, replaces the static file config (KeepAliveConfig) at runtime.
 *
 * Lifecycle (all methods are called from system_server hooks, must be lock-free & fast):
 *   - seeded once at boot from KeepAliveConfig (file /data/system/appalive.txt or DEFAULTS)
 *   - ADD      : ActivityRecord.completeResumeLocked()   -> app came to foreground
 *   - REMOVE   : ActivityTaskSupervisor/ActivityStackSupervisor
 *                .removeTask() / .cleanUpRemovedTaskLocked()
 *                -> task removed from Recents (user swiped the card away / Clear all)
 *                  (never fired by LMK / background service kills)
 *   - QUERY    : checkExcessivePowerUsageLPr hook
 *
 * Thread-safety: ConcurrentHashMap.newKeySet() is a concurrent set, safe for the
 * binder/AM/WM thread mix calling checkExcessivePowerUsageLPr (LPr) and the WM lock
 * holders firing resume/removeTask. All operations are O(1) in-memory, no binder, no
 * disk, no blocking -> safe to call while holding the AMS/WM locks.
 *
 * Note: removal events are naturally idempotent (Set.remove), so hooking both
 * removeTask and cleanUpRemovedTaskLocked (which overlap on some paths) is harmless.
 */
public final class ProtectedApps {

    private static final String TAG = "AppAlive";

    private static final Set<String> sPackages = ConcurrentHashMap.newKeySet();
    private static final Set<String> rPackages = ConcurrentHashMap.newKeySet();

    // set false if you want to load package list from KeepAliveConfig
    private static volatile boolean sSeeded = false;

    private ProtectedApps() {}

    /** Seed once at system_server hook time from the static config (file + defaults). */
    public static void seedIfNeeded() {
        if (sSeeded) return;
        synchronized (ProtectedApps.class) {
            if (sSeeded) return;
            rPackages.addAll(KeepAliveConfig.get());
            sSeeded = true;
        }
    }

    /** App came to foreground (started or resumed). */
    public static void add(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        if (sPackages.add(packageName)) {
            XposedBridge.log(TAG + ": PROTECT + " + packageName);
        }
    }

    /** Task removed from Recents (swiped away / Clear all), drop protection. */
    public static void remove(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        if (sPackages.remove(packageName)) {
            XposedBridge.log(TAG + ": PROTECT - " + packageName);
        }
    }

    /** Queried by the checkExcessivePowerUsageLPr hook. */
    public static boolean contains(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return sPackages.contains(packageName);
    }

    public static boolean rcontains(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        for (String r: rPackages) {
            if (packageName.startsWith(r,0)) return true;
        }
        return false;
//        return rPackages.contains(packageName);
    }

    /** Debug helper: dump current protected set to the Xposed log. */
    public static void dump() {
        XposedBridge.log(TAG + ": PROTECTED(" + sPackages.size() + ")=" + sPackages);
    }
}
