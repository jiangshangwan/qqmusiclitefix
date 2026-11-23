/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * qm-hook-1a54 · 媒体通知 MediaSession 绑定适配
 */
package com.qqmusiclite.adapter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Parcelable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 媒体通知适配。
 *
 * <p>目标应用（QQ音乐简洁版）在构建播放通知时使用了 {@code NotificationCompat.MediaStyle}，
 * 却未调用 {@code setMediaSession(token)}。结果是通知虽带 transport 控制按钮，却没有与
 * 应用已创建的 {@code MediaSession} 关联，系统媒体中心与锁屏因此不渲染播放卡片。
 *
 * <p>QM-DOC-9B7E 本类 hook 通知构建方法的返回值，将应用的 MediaSession token 写入通知 extras 的
 * {@code android.mediaSession} 键（framework 标准键），从而让通知被识别为媒体会话的
 * 载体，恢复通知栏与锁屏的 HyperOS 系统样式富媒体播放卡片。同时确保播放通知渠道的重要性与锁屏可见性。
 */
final class MediaNotificationHook {

    /** 目标应用构建播放通知的类与方法（返回 android.app.Notification）。 */
    private static final String CLS_NOTIFICATION = "cn.wenyu.bodian.HostMediaNotification";
    private static final String METHOD_BUILD = "b";

    /** 持有 MediaSessionCompat 实例的类及其静态字段。 */
    private static final String CLS_SESSION = "cn.wenyu.bodian.HostMediaSession";
    private static final String FIELD_SESSION = "a";

    /** 播放通知渠道 id（与应用 c() 方法创建的一致）。 */
    private static final String CHANNEL_PLAY = "com.tencent.qqfreemusic.play";

    /** framework 读取媒体会话 token 的 extras 键（Notification.EXTRA_MEDIA_SESSION）。 */
    private static final String KEY_MEDIA_SESSION = "android.mediaSession";

    private static volatile ClassLoader sLoader;

    private MediaNotificationHook() {
    }

    static void install(XposedModule module, ClassLoader cl) throws Throwable {
        sLoader = cl;

        // 1) 确保通知渠道可用（应用自身的 c() 可能因历史设置把渠道置为 NONE）。
        hookChannelCreation(module, cl);

        // 2) 核心：hook 通知构建，注入 MediaSession token。
        Class<?> notifCls = Class.forName(CLS_NOTIFICATION, false, cl);
        Method build;
        try {
            build = notifCls.getDeclaredMethod(METHOD_BUILD);
        } catch (NoSuchMethodException e) {
            // 版本差异兜底：扫描所有无参且返回 Notification 的方法。
            build = findBuildMethod(notifCls);
        }
        if (build == null) {
            XposedInit.log("build method not found, hook skipped");
            return;
        }
        build.setAccessible(true);
        XposedInit.log("hooking " + notifCls.getName() + "." + build.getName());
        XposedInit.installHook(module, build, (XposedInterface.Hooker) chain -> {
            Object result = chain.proceed();
            if (result instanceof Notification) {
                try {
                    bindSessionToken((Notification) result);
                } catch (Throwable t) {
                    XposedInit.log("bind token failed: " + t);
                }
            }
            return result;
        });

        // 3) QM-ALIGN-6F21 在通知侧重排 compactActions 槽位，使三个播放按钮落在卡片中部。
        //    由 bindSessionToken 中的 shiftCompactActions 完成，无需单独 hook。
    }

    /**
     * hook 通知渠道创建：强制把播放渠道设为 IMPORTANCE_LOW 且锁屏可见，
     * 避免历史设置把渠道关成 NONE 导致通知根本发不出。
     */
    private static void hookChannelCreation(XposedModule module, ClassLoader cl) {
        try {
            final Class<?> nmCls = Class.forName("android.app.NotificationManager");
            Method create = nmCls.getDeclaredMethod("createNotificationChannel", NotificationChannel.class);
            XposedInit.installHook(module, create, (XposedInterface.Hooker) chain -> {
                Object arg = chain.getArg(0);
                if (arg instanceof NotificationChannel) {
                    NotificationChannel ch = (NotificationChannel) arg;
                    if (CHANNEL_PLAY.equals(ch.getId())) {
                        try {
                            ch.setImportance(NotificationManager.IMPORTANCE_LOW);
                            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                            ch.enableVibration(false);
                            ch.setSound(null, null);
                        } catch (Throwable t) {
                            XposedInit.log("channel tweak failed: " + t);
                        }
                    }
                }
                return chain.proceed();
            });
            XposedInit.log("channel hook installed");
        } catch (Throwable t) {
            XposedInit.log("channel hook skipped: " + t);
        }
    }

    /**
     * QM-SLOT-6D3A HyperOS 媒体卡片使用 5 个固定按钮槽位。
     * 目标应用原本把三个按钮放入 0/1/2，导致整组偏左；改到 1/2/3 后与卡片中心对齐。
     */
    private static final String KEY_COMPACT_ACTIONS = "android.compactActions";
    private static final String KEY_COMPACT_SHIFTED = "qma.compact.shifted";
    private static final int[] CENTERED_COMPACT_SLOTS = {1, 2, 3};
    private static final int GENERIC_SLOT_COUNT = 5;

    /** 从应用静态字段取 MediaSessionCompat，绑定 token 到通知并设置 session extras。 */
    private static void bindSessionToken(Notification notification) throws Exception {
        ClassLoader cl = sLoader;
        if (cl == null) return;

        Class<?> sessionCls = Class.forName(CLS_SESSION, false, cl);
        Field field = sessionCls.getDeclaredField(FIELD_SESSION);
        field.setAccessible(true);
        Object sessionCompat = field.get(null); // 静态字段
        if (sessionCompat == null) {
            XposedInit.log("session compat null, skip bind");
            return;
        }

        // 取 framework MediaSession（同时用于 token 与 extras）。
        android.media.session.MediaSession fwSession = getFrameworkSession(sessionCompat);
        Parcelable frameworkToken = fwSession != null
                ? fwSession.getSessionToken() : extractFrameworkToken(sessionCompat);
        if (frameworkToken == null) {
            XposedInit.log("framework token null, skip bind");
            return;
        }

        Bundle extras = notification.extras;
        if (extras == null) {
            extras = new Bundle();
            notification.extras = extras;
        }
        extras.putParcelable(KEY_MEDIA_SESSION, frameworkToken);
        XposedInit.log("bound MediaSession token to notification");

        boolean playing = isPlaying(fwSession);
        shiftCompactActions(notification, extras, playing);
    }

    /** QM-STATE-7C2D 从 framework session 读取当前播放状态，用于选择播放/暂停矢量图标。 */
    private static boolean isPlaying(android.media.session.MediaSession session) {
        if (session == null) {
            return false;
        }
        try {
            PlaybackState state = session.getController().getPlaybackState();
            return state != null && state.getState() == PlaybackState.STATE_PLAYING;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * QM-SHIFT-91CE 把原本位于 0/1/2 的三个播放按钮移动到 1/2/3 槽位。
     * 两侧使用透明占位 action 补齐，保留 SystemUI 的 5 槽布局不变。
     */
    private static void shiftCompactActions(Notification notification, Bundle extras,
                                            boolean playing) {
        if (extras.getBoolean(KEY_COMPACT_SHIFTED, false)) {
            return;
        }
        Notification.Action[] original = notification.actions;
        if (original == null || original.length < 3) {
            XposedInit.log("compact shift skipped, actions=" +
                    (original == null ? 0 : original.length));
            return;
        }

        Notification.Action[] shifted = new Notification.Action[GENERIC_SLOT_COUNT];
        shifted[0] = createPlaceholderAction(original);
        shifted[1] = withSystemIcon(original[0], android.R.drawable.ic_media_previous);
        shifted[2] = withSystemIcon(original[1], playing
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        shifted[3] = withSystemIcon(original[2], android.R.drawable.ic_media_next);
        shifted[4] = createPlaceholderAction(original);
        notification.actions = shifted;

        extras.putIntArray(KEY_COMPACT_ACTIONS, CENTERED_COMPACT_SLOTS);
        extras.putBoolean(KEY_COMPACT_SHIFTED, true);
        XposedInit.log("compact actions shifted to centered slots 1/2/3");
    }

    /** QM-PLACE-3E7B 生成无可见图标、无文本的占位 action，仅用于占据边缘按钮槽位。 */
    private static Notification.Action createPlaceholderAction(Notification.Action[] source) {
        PendingIntent intent = null;
        for (Notification.Action action : source) {
            if (action != null && action.actionIntent != null) {
                intent = action.actionIntent;
                break;
            }
        }
        Bitmap transparent = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        transparent.eraseColor(0);
        Icon icon = Icon.createWithBitmap(transparent);
        return new Notification.Action.Builder(icon, "", intent).build();
    }

    /** QM-VECTOR-91DA 用 framework 矢量图标重建 action，避免位图放大导致的模糊。 */
    private static Notification.Action withSystemIcon(Notification.Action action, int drawableRes) {
        if (action == null) {
            return null;
        }
        try {
            Icon icon = Icon.createWithResource("android", drawableRes);
            return new Notification.Action.Builder(
                    icon, action.title, action.actionIntent).build();
        } catch (Throwable t) {
            XposedInit.log("system icon failed: " + t);
            return action;
        }
    }

    /** 从 MediaSessionCompat 取 framework MediaSession（字段路径优先）。 */
    private static android.media.session.MediaSession getFrameworkSession(Object sessionCompat) {
        try {
            Object impl = readFirstFieldOfType(sessionCompat, "MediaSessionImpl");
            if (impl == null) {
                impl = readFieldByName(sessionCompat, "a");
            }
            if (impl != null) {
                return findFrameworkSession(impl);
            }
        } catch (Throwable t) {
            XposedInit.log("get framework session failed: " + t);
        }
        return null;
    }

    /**
     * support-v4 {@code MediaSessionCompat} → framework {@code MediaSession.Token}。
     *
     * <p>目标 APK 的 support-v4 经过 R8 混淆，公开方法 getSessionToken() / getToken()
     * 已被改名为 a/b/c/d，无法按方法名调用。改走字段路径：
     * MediaSessionCompat.mImpl（字段 a，MediaSessionImplApi22）→ 其超类
     * MediaSessionImplApi21 的字段 a（android.media.session.MediaSession）→ 调用 framework
     * 标准方法 getSessionToken()。framework 类未被混淆，方法名稳定。
     *
     * <p>同时保留未混淆场景的方法路径作为兜底。
     */
    private static Parcelable extractFrameworkToken(Object sessionCompat) {
        // 路径一：字段路径（适用于被 R8 混淆的 support-v4）。
        try {
            Object impl = readFirstFieldOfType(sessionCompat, "MediaSessionImpl");
            if (impl == null) {
                // 字段名 a 在 MediaSessionCompat 中即为 mImpl。
                impl = readFieldByName(sessionCompat, "a");
            }
            if (impl != null) {
                android.media.session.MediaSession fwSession =
                        findFrameworkSession(impl);
                if (fwSession != null) {
                    android.media.session.MediaSession.Token token = fwSession.getSessionToken();
                    if (token != null) {
                        XposedInit.log("token via impl field path");
                        return token;
                    }
                }
            }
        } catch (Throwable t) {
            XposedInit.log("impl field path failed: " + t);
        }

        // 路径二：未混淆时的公开方法路径。
        try {
            Method getSessionToken = findMethod(sessionCompat.getClass(), "getSessionToken");
            if (getSessionToken != null) {
                getSessionToken.setAccessible(true);
                Object compatToken = getSessionToken.invoke(sessionCompat);
                if (compatToken != null) {
                    Method getToken = findMethod(compatToken.getClass(), "getToken");
                    if (getToken != null) {
                        getToken.setAccessible(true);
                        Object token = getToken.invoke(compatToken);
                        if (token instanceof Parcelable) {
                            XposedInit.log("token via method path");
                            return (Parcelable) token;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XposedInit.log("method path failed: " + t);
        }
        return null;
    }

    /** 从 impl 对象的自身及其超类中找 android.media.session.MediaSession 类型的字段并读取。 */
    private static android.media.session.MediaSession findFrameworkSession(Object impl) {
        Class<?> cur = impl.getClass();
        while (cur != null) {
            for (Field f : cur.getDeclaredFields()) {
                if (android.media.session.MediaSession.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return (android.media.session.MediaSession) f.get(impl);
                    } catch (Throwable ignored) {
                        // 继续尝试其他字段。
                    }
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** 按字段名在继承链上读取（用于 mImpl 这类被混淆为单字母的字段）。 */
    private static Object readFieldByName(Object obj, String name) {
        Class<?> cur = obj.getClass();
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 按类型名粗略查找字段（类型名包含关键字）。 */
    private static Object readFirstFieldOfType(Object obj, String typeSimpleName) {
        Class<?> cur = obj.getClass();
        while (cur != null) {
            for (Field f : cur.getDeclaredFields()) {
                if (f.getType().getName().contains(typeSimpleName)) {
                    try {
                        f.setAccessible(true);
                        return f.get(obj);
                    } catch (Throwable ignored) {
                        // 继续尝试其他字段。
                    }
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** 在类继承链上按名称找无参方法。 */
    private static Method findMethod(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** 扫描返回 Notification 的无参方法，作为方法名变化的兜底。 */
    private static Method findBuildMethod(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && Notification.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        return null;
    }
}
