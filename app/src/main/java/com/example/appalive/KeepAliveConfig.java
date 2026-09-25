package com.example.appalive;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 保活包名配置。
 * - 内置默认列表
 * - 可选从文件加载/覆盖
 * - 全部静态，直接用 KeepAliveConfig.contains(pkg) 调用，无需 new
 */
public class KeepAliveConfig {

    /** 配置文件路径（可改成你模块自己的数据目录） */
    private static final String CONFIG_PATH = "/data/system/appalive.txt";

    /** 内置默认保活列表 */
    private static final Set<String> DEFAULTS = new HashSet<>(Arrays.asList(
            "com.example.appalive",
            "com.tencent.mm",
            "com.xingin.xhs",
            "com.twitter.android",
            "com.hundsun.stockwinner.paqh",
            "com.hundsun.winner.pazq",
            "com.brave.browser",
            "com.deepseek.chat"
    ));

    /** 当前生效的集合，volatile 保证多线程可见 */
    private static volatile Set<String> sPackages = null;

    /** 私有构造：纯工具类，禁止实例化 */
    private KeepAliveConfig() {}

    /** 获取当前列表（懒加载 + 缓存） */
    public static Set<String> get() {
        Set<String> local = sPackages;
        if (local != null) return local;

        synchronized (KeepAliveConfig.class) {
            if (sPackages != null) return sPackages;

            Set<String> loaded = loadFromFile();
            if (loaded == null || loaded.isEmpty()) {
                loaded = new HashSet<>(DEFAULTS);
            }
            sPackages = Collections.unmodifiableSet(loaded);
            return sPackages;
        }
    }

    /** 判断某个包名是否在保活列表 */
    public static boolean contains(String packageName) {
        if (packageName == null) return false;
        return get().contains(packageName);
    }

    /** 清缓存，下次 get() 重新读文件（热更新用） */
    public static void invalidate() {
        synchronized (KeepAliveConfig.class) {
            sPackages = null;
        }
    }

    /** 从文件读取；文件不存在或读失败返回 null */
    private static Set<String> loadFromFile() {
        File f = new File(CONFIG_PATH);
        if (!f.exists()) return null;

        Set<String> set = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                set.add(line);
            }
        } catch (Throwable t) {
            return null;
        }
        return set;
    }
}