/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * qm-theme-auto-7D3A · Flutter 主题配置跟随系统
 * qm-pkgmove-7B2D · 包路径 io.github.jiangshangwan.qqmusiclitefix
 */
package io.github.jiangshangwan.qqmusiclitefix;

import android.content.res.Configuration;
import android.content.res.Resources;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** 让目标应用的 Flutter 主题配置始终读取当前系统深色/浅色模式。 */
final class ThemeAutoHook {

    /** Flutter shared_preferences 中保存主题模式的键。 */
    private static final String KEY_DARK = "flutter.isDark";

    private ThemeAutoHook() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        hookPreferences(module);
        XposedInit.log("theme auto-follow installed for " + KEY_DARK);
    }

    private static boolean systemDark() {
        int uiMode = Resources.getSystem().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** QM-PREF-OVERRIDE-19AC 覆盖 getAll/getBoolean，Flutter 初始化主题时读到系统当前模式。 */
    private static void hookPreferences(XposedModule module) {
        try {
            Class<?> impl = Class.forName("android.app.SharedPreferencesImpl");

            Method getAll = impl.getDeclaredMethod("getAll");
            XposedInit.installHook(module, getAll, (XposedInterface.Hooker) chain -> {
                Object result = chain.proceed();
                if (!(result instanceof Map)) {
                    return result;
                }
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) result);
                if (copy.containsKey(KEY_DARK)) {
                    copy.put(KEY_DARK, systemDark());
                }
                return copy;
            });

            Method getBoolean = impl.getDeclaredMethod("getBoolean", String.class, boolean.class);
            XposedInit.installHook(module, getBoolean, (XposedInterface.Hooker) chain -> {
                if (KEY_DARK.equals(chain.getArg(0))) {
                    return systemDark();
                }
                return chain.proceed();
            });

            XposedInit.log("theme preference hooks installed");
        } catch (Throwable t) {
            XposedInit.log("theme preference hooks failed: " + t);
        }
    }
}
