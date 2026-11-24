/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * qm-entry-19 · QQ音乐简洁版适配 模块入口
 * qm-pkgmove-4E1A · 包路径 io.github.jiangshangwan.qqmusiclitefix
 */
package io.github.jiangshangwan.qqmusiclitefix;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口（libxposed Modern API 102）。
 *
 * <p>无界面模块：仅在目标包加载时安装媒体通知适配 hook。目标包见 scope.list。
 */
public final class XposedInit extends XposedModule {

    /** 目标包名：QQ音乐简洁版。 */
    static final String TARGET_PACKAGE = "com.tencent.freemusic";

    /** 日志标签。 */
    private static final String TAG = "[QMAdapt]";

    private static volatile XposedInit sInstance;

    public XposedInit() {
        sInstance = this;
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        final String pkg = param.getPackageName();
        if (pkg == null || !TARGET_PACKAGE.equals(pkg)) {
            return;
        }
        log("package loaded: " + pkg + " (first=" + param.isFirstPackage() + ")");
        try {
            ClassLoader cl = param.getDefaultClassLoader();
            if (cl == null) {
                log("no classloader, skip");
                return;
            }
            MediaNotificationHook.install(this, cl);
            log("media notification hook installed");
            // QM-THEME-72B9 主题自动跟随系统模式入口。
            ThemeAutoHook.install(this, cl);
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    /** 统一打日志（框架日志汇聚于 LSPosed 模块日志）。 */
    static void log(String msg) {
        XposedInit inst = sInstance;
        if (inst != null) {
            inst.log(Log.INFO, TAG, msg);
        } else {
            Log.i(TAG, msg);
        }
    }

    /** 挂 hook（保护模式：hooker 异常不影响目标进程）。 */
    static void installHook(XposedModule module, java.lang.reflect.Executable method,
                            XposedInterface.Hooker hooker) {
        module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }
}
