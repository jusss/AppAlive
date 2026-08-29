# Getting a Working `NotificationManager` Inside system_server (Xposed/LSPosed)

> Summary of the debugging journey for `MainHook.java` — from the original NPE to the final working
> implementation. The core lesson: **the reflection code was never the real problem; the real
> problems were (1) *when* the call happened and (2) *which class* `asInterface` lives on.**

---

## 1. Original problem

```
java.lang.NullPointerException: Attempt to invoke interface method
void android.app.INotificationManager.createNotificationChannels(
    java.lang.String, android.content.pm.ParceledListSlice)
on a null object reference
```

Crash at `callNMS_Reflection` (old line 817), triggered at **boot time** during
`handleLoadPackage("android")`.

### Root cause

`handleLoadPackage("android")` runs in **early system_server boot** — long before
`NotificationManagerService` has registered its `"notification"` binder in `ServiceManager`.

At that moment:

```java
NotificationManager nm = sSystemContext.getSystemService(Context.NOTIFICATION_SERVICE);
```

- returns a **non-null** `NotificationManager` object (so a `null` check passed!),
- but its internal `mService` field (`INotificationManager`) is **null**,
- so `nm.createNotificationChannel(ch)` → `mService.createNotificationChannels(...)` → **NPE**.

Worse: `SystemServiceRegistry` may **cache that broken instance**, so even later calls can
return the same null-service wrapper.

**Rule #1: never call NMS APIs from `handleLoadPackage` or `hookNotificationManager`
installation time — the `"notification"` service simply does not exist yet.**

---

## 2. Attempts that failed (and why)

| # | Approach | Result | Why it failed |
|---|----------|--------|---------------|
| 1 | `sSystemContext.getSystemService(NOTIFICATION_SERVICE)` then `createNotificationChannel` | NPE | Early boot: returns `NotificationManager` with `mService == null`; bad instance cached by `SystemServiceRegistry` |
| 2 | Boot-time test call inside `handleLoadPackage` / `hookNotificationManager` | `NMS binder is null` | Both hooks install during early boot, **before** NMS registers `"notification"` in `ServiceManager` |
| 3 | `ServiceManager.getService("notification")` + reflection `asInterface` on `android.app.INotificationManager` | `NoSuchMethodError: android.app.INotificationManager#asInterface(...)` | In AIDL-generated code, `asInterface` is a **static method of `INotificationManager$Stub`**, not of the interface itself |
| 4 | Direct reflective call to `NotificationManagerService.enqueueNotificationInternal` with a `ParceledListSlice` | workable but fragile | Version-drift on parameter counts (9/10 args across Android 11–14); `ParceledListSlice` constructor reflection is unnecessary |

None of these were "wrong" per se — #3 was *almost* right, it just invoked `asInterface` on the
wrong class.

---

## 3. The fix that finally worked

### 3.1 Timing: only call after boot completes

`hookBootComplete` hooks `ActivityManagerService.finishBooting()` — guaranteed to run after
`NotificationManagerService` has started (`SystemServer.startOtherServices()` runs it earlier).
A 5-second delayed test notification on a background thread proves the pipeline works, with no
manual trigger needed.

```java
XposedBridge.hookAllMethods(ams, "finishBooting", new XC_MethodHook() {
    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        if (bootTestDone) return;
        bootTestDone = true;
        new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            callNMS_Reflection("Boot test", "NMS reflection OK after boot", cl);
        }, "AppAliveBootTest").start();
    }
});
```

Real traffic (FCM broadcast hook, `enqueueNotificationInternal` hook) only ever runs at
runtime — after boot — so the timing is inherently safe there.

### 3.2 The correct way to obtain `NotificationManager` (the key part)

```java
private NotificationManager getSystemNotificationManager(ClassLoader cl) {
    try {
        // 1. Fetch the binder DIRECTLY from ServiceManager — no caching pitfalls
        Class<?> serviceManagerClass = XposedHelpers.findClass(
                "android.os.ServiceManager", cl);
        IBinder binder = (IBinder) XposedHelpers.callStaticMethod(
                serviceManagerClass, "getService", "notification");
        if (binder == null) {
            XposedBridge.log(TAG + "NMS binder is null");
            return null;
        }

        // 2. Interface class = constructor parameter type
        Class<?> iNotificationManagerClass = XposedHelpers.findClass(
                "android.app.INotificationManager", cl);

        // 3. ★ asInterface is a static method of INotificationManager$Stub,
        //      NOT of the INotificationManager interface itself ★
        Class<?> stubClass = XposedHelpers.findClass(
                "android.app.INotificationManager$Stub", cl);
        Object iNotificationManager = XposedHelpers.callStaticMethod(
                stubClass, "asInterface", binder);   // returns Stub.Proxy (implements the interface)

        // 4. Build NotificationManager wired directly to that binder
        Class<?> notificationManagerClass = XposedHelpers.findClass(
                "android.app.NotificationManager", cl);
        Constructor<?> constructor = notificationManagerClass.getDeclaredConstructor(
                Context.class, iNotificationManagerClass);
        constructor.setAccessible(true);
        return (NotificationManager) constructor.newInstance(
                sSystemContext, iNotificationManager);
    } catch (Throwable t) {
        // Fallback: after boot completes, getSystemService is actually fine
        Object nm = sSystemContext.getSystemService(Context.NOTIFICATION_SERVICE);
        return (nm instanceof NotificationManager) ? (NotificationManager) nm : null;
    }
}
```

Why this works:

- `ServiceManager.getService` is called **fresh every time** (until cached — see below), so there
  is no `SystemServiceRegistry` stale-cache problem.
- `Stub.asInterface(binder)` returns a `Proxy` that **does** implement `INotificationManager`, so
  the `NotificationManager(Context, INotificationManager)` constructor accepts it and `mService`
  is **never null**.
- The constructor path means `createNotificationChannel()` / `notify()` are plain public API
  calls — no fragile 9/10-parameter reflective `enqueueNotificationInternal` invocations.

### 3.3 Caching the instance (safe, because of process lifecycle)

```java
private static volatile NotificationManager sNms = null;

// in callNMS_Reflection:
NotificationManager nms_obj = sNms;
if (nms_obj == null) {
    nms_obj = getSystemNotificationManager(cl);
    if (nms_obj == null) { log("NMS binder not ready"); return; }
    sNms = nms_obj;
}
// ... createNotificationChannel + notify ...
// on ANY exception: sNms = null;   ← drop dead binder, refetch next time
```

Is caching safe? **Yes**, because:

- `NotificationManagerService` lives inside `system_server` — the **same process** as the hook.
- Its binder is a process-lifetime singleton; it cannot die or be replaced unless system_server
  restarts, which resets all statics anyway.
- `volatile` gives cross-thread visibility (broadcast hook and enqueue hook run on different
  binder threads); a benign duplicate-build race at boot is harmless (last write wins, both
  instances valid).
- Per-message cost drops from ~6 reflective lookups to zero after the first intercept.

### 3.4 Re-entrancy guard (avoid posting intercept notifications recursively)

The intercept notification uses tag `"fcm_intercept"`, and Hook B skips it:

```java
if (param.args.length > 4 && "fcm_intercept".equals(param.args[4])) return;
```

---

## 4. Additional lesson: background FCM notification messages bypass the broadcast

The original "only Screen woken by NMS App" symptom (no intercept notification for a test
message) was **not** a NMS failure — it was that `hookFcmBroadcast` never fired:

- FCM **notification** messages to a backgrounded app are displayed **by Google Play services
  directly** (`opPkg = com.google.android.gms`).
- No `com.google.firebase.MESSAGING_EVENT` broadcast is sent → `hookFcmBroadcast` never runs.

Fix: `enqueueNotificationInternal` (Hook B) now detects GMS-posted messages and triggers the
intercept notification for that delivery path too:

| Message type | Delivery path | Intercept source |
|---|---|---|
| Data message / foreground app | `MESSAGING_EVENT` broadcast | Hook A (`broadcastIntentLocked`) |
| Notification message, background app | GMS posts notification directly (`opPkg=gms`) | Hook B (`enqueueNotificationInternal`) |

---

## 5. Checklist for anyone doing the same thing

1. **Never** call NMS at hook-install / `handleLoadPackage` time — binder not registered yet.
2. Get the binder via `ServiceManager.getService("notification")`; if null → skip, don't crash.
3. Call **`INotificationManager$Stub.asInterface`**, *not* `INotificationManager.asInterface`.
4. Construct `NotificationManager(Context, INotificationManager)` (the 2-arg package-private
   constructor, `setAccessible(true)`).
5. Use the public `createNotificationChannel()` + `notify()` — avoid reflective
   `enqueueNotificationInternal` (parameter count drifts across Android versions).
6. Cache the instance in a `volatile` field; reset to null on exception.
7. Clear calling identity (`Binder.clearCallingIdentity()`) around the call so system_server
   permissions are used.
8. Mark your own notifications with a unique tag and skip them in the enqueue hook
   (re-entrancy guard).
9. Verify with a post-boot test (hook `finishBooting`) — expected log:

   ```
   Hooked finishBooting ✓
   Boot complete, NMS test in 5s
   ✅ Got system NotificationManager via ServiceManager
   ✅ NMS call succeeded
   ```
