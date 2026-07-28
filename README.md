
---

## Project Structure

```
CpuKillDisabler/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── xposed_init
│       └── java/com/example/cpukilldisabler/
│           └── MainHook.java
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## File by file

### 1. `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven {url=uri("https://maven.aliyun.com/repository/public/")}
    }
}

rootProject.name = "CpuKillDisabler"
include(":app")
```

### 2. `build.gradle.kts` (root)

```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
}
```

### 3. `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.nonTransitiveRClass=true
```

### 4. `app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.cpukilldisabler"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.cpukilldisabler"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
```

### 5. `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:supportsRtl="true">

        <!-- Tell LSPosed this is an Xposed module -->
        <meta-data
            android:name="xposedmodule"
            android:value="true" />

        <!-- Description shown in LSPosed manager -->
        <meta-data
            android:name="xposeddescription"
            android:value="Disable 'excessive cpu' background app kills" />

        <!-- Minimum LSPosed API version (93 = 1.8.0+) -->
        <meta-data
            android:name="xposedminversion"
            android:value="93" />

        <!-- Hook into the system_server process (where ActivityManager runs) -->
        <meta-data
            android:name="xposedscope"
            android:resource="@array/scope" />

    </application>

</manifest>
```

### 6. `app/src/main/res/values/arrays.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="scope">
        <item>system</item>
        <item>android</item>
    </string-array>
</resources>
```

### 7. `app/src/main/assets/xposed_init`

```
com.example.cpukilldisabler.MainHook
```

### 8. `app/src/main/java/com/example/cpukilldisabler/MainHook.java`

```java
package com.example.cpukilldisabler;

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

```
If this **still** fails with `#exact` errors, your ROM's signatures differ from AOSP. Run this discovery snippet first and paste the logcat:

```java
// Replace handleLoadPackage body temporarily
Class<?> c = lpparam.classLoader.loadClass(AMS);
for (Method m : c.getDeclaredMethods()) {
    String name = m.getName();
    if (name.contains("Excessive") || name.contains("CpuTime") || name.contains("excessive")) {
        XposedBridge.log(TAG + ": FOUND → " + name
                + "(" + java.util.Arrays.toString(m.getParameterTypes()) + ")");
    }
}

---

## Build & Install

```bash
# In Android Studio:
# Build → Build APK
# Or command line:
./gradlew assembleRelease

# Install to device
adb install app/build/outputs/apk/release/app-release.apk

# Then open LSPosed manager → enable module → check "System Framework" → reboot
```

After reboot, verify:

```bash
logcat -d | grep CpuKillDisabler
# Should show all three hooks loaded

logcat -d | grep "excessive cpu"
# Should be completely empty
```

---

## How it works

```
ActivityManager checks background CPU usage
        │
        ▼
updateAppProcessCpuTimeLPr(doCpuKills=true, cpuLimit=10)
        │
        │   ← Hook 2: sets doCpuKills = false → entire check skipped
        │
        ▼
checkExcessivePowerUsageLPr(cpuTimeUsed, cpuLimit, app)
        │
        │   ← Hook 1: returns false immediately → no kill even if check runs
        │
        ▼
app.killLocked("excessive cpu ...")
        │
        │   ← Never reached
        ▼
sendKillExcessiveCpuProfilingTrigger(uid, pkg)
        │
        │   ← Hook 3: no-op'd just in case
```

Three layers of defense — it won't miss.

---

## Removal

Disable the module in LSPosed manager and reboot. Or uninstall the APK. No permanent changes to your system.

## Install

Install magisk first, use magisk install JinMatrix/Vector, install this app, enable this module in JinMatrix/Vector, toggle system framework, reboot
