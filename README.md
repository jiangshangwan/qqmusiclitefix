<!--
Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
SPDX-License-Identifier: GPL-3.0-only
qm-readme-7A31 · 项目说明文档
-->

# QQ音乐简洁版适配（酷安：江上晚）

一个**无界面 LSPosed 模块**，用于修复 QQ音乐简洁版在 HyperOS 上播放音乐时通知栏与锁屏不显示播放器控件的问题，并让应用主题在启动时跟随系统深色 / 浅色模式。

模块名称：`QQ音乐简洁版适配(酷安：江上晚)`

## 问题背景

QQ音乐简洁版（`com.tencent.freemusic`）在构建播放通知时使用了 `NotificationCompat.MediaStyle`，却没有把应用已经创建的 `MediaSession` token 绑定到通知上。结果是：

- 通知栏与锁屏虽然携带播放控制按钮，但系统不认为它是媒体会话的载体；
- HyperOS SystemUI 的媒体中心 / 锁屏富媒体卡片不会渲染；
- 通知渠道在历史设置影响下可能被置为 `NONE`，导致播放通知根本不显示。

## 已实现功能

1. **恢复 HyperOS 系统样式富媒体播放卡片**  
   将 framework 层的 `MediaSession.Token` 写入通知 extras 的 `android.mediaSession` 键，使通知被系统识别为媒体会话载体，通知栏与锁屏正常显示大封面、进度条与系统媒体中心样式卡片。

2. **播放按钮居中**  
   HyperOS 媒体卡片使用 5 个固定按钮槽（`action0` ~ `action4`）。模块把紧凑按钮索引从 `[0,1,2]` 调整为 `[1,2,3]`，两侧用透明占位 action 补齐，使「上一曲 / 播放暂停 / 下一曲」落在卡片中部。

3. **系统矢量图标**  
   播放控制按钮使用 `Icon.createWithResource("android", ...)` 载入 framework 矢量资源，避免位图放大造成的模糊。

4. **通知渠道修正**  
   Hook `NotificationManager.createNotificationChannel()`，强制播放渠道 `com.tencent.qqfreemusic.play` 为 `IMPORTANCE_LOW` 且锁屏可见，避免因历史设置导致通知被关闭。

5. **主题启动跟随系统**  
   Hook `SharedPreferencesImpl` 的 `getBoolean` / `getAll`，把 Flutter 主题键 `flutter.isDark` 覆盖为当前系统 `uiMode`。应用启动或重新启动时会按系统当前深色 / 浅色模式加载主题。

## 技术实现

| 目标 | 方式 |
|---|---|
| 通知构建 hook | `cn.wenyu.bodian.HostMediaNotification.b()` |
| Session 获取 | 反射读取 `cn.wenyu.bodian.HostMediaSession.a` 中的 support session，再沿字段路径取 framework `MediaSession` |
| Token 注入 | `Notification.extras.putParcelable("android.mediaSession", token)` |
| 按钮槽位 | `Notification.extras.putIntArray("android.compactActions", [1,2,3])` |
| 主题覆盖 | `SharedPreferencesImpl.getBoolean("flutter.isDark", ...)` |
| Hook 框架 | libxposed Modern API 102，`ExceptionMode.PROTECTIVE` |

## 已知限制

- **播放器内实时切换系统主题不生效**：目标应用的 Dart 层 `isDarkNotifier` 只在 Flutter 引擎初始化时读取 `flutter.isDark`，不响应 `platformBrightness` 变化，也不监听 SharedPreferences 变更。因此系统主题切换后需要结束应用并重新打开，模块不会强行重建引擎或重绘 Dart widget。
- **播放器「唱」按钮与点赞后的粉红心特效不在处理范围**：两者均由 Flutter / Dart 在 Skia 层渲染，不走 Android View 系统，常规 Xposed hook 无法移除。

## 编译

环境要求：

- JDK 17
- Android SDK Platform 35 / Build Tools
- Gradle 8.13（仓库已带 wrapper）
- Android Gradle Plugin 8.9.2
- 依赖：`io.github.libxposed:api:102.0.0`（仅编译期）

```bash
./gradlew :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。

签名策略：仓库不收录密钥文件。若本地 `../qqmusicadapter.jks` 存在则使用正式签名；否则回退到 Gradle 默认签名，保证任何人 clone 后仍可构建。

## 安装与使用

1. 安装支持 libxposed API 102 的 LSPosed 框架（实测框架版本 `2.2.1 / 7949` 可用）。
2. 安装本模块 APK。
3. 在 LSPosed 中启用模块，并勾选作用域：**QQ音乐简洁版（`com.tencent.freemusic`）**。
4. 强制停止并重新打开 QQ音乐简洁版；若媒体卡片未刷新，可重启 SystemUI 或设备。
5. 模块无界面，不需要启动任何 Activity。

模块元数据位于 `app/src/main/resources/META-INF/xposed/`：

- `java_init.list`：入口 `io.github.jiangshangwan.qqmusiclitefix.XposedInit`
- `module.prop`：`minApiVersion=101`、`targetApiVersion=102`
- `scope.list`：`com.tencent.freemusic`

<!-- qm-readme-pkg-5D91 包路径说明同步 -->

## 目录结构

```text
app/src/main/java/io/github/jiangshangwan/qqmusiclitefix/
├── XposedInit.java           模块入口与 hook 安装
├── MediaNotificationHook.java 媒体通知 / MediaSession / 卡片槽位适配
└── ThemeAutoHook.java         Flutter 主题偏好覆盖
```

## 许可证

源码文件头采用 SPDX 声明 `GPL-3.0-only`。仓库根目录的 `LICENSE` 文件为 GitHub 初始化时生成的 MIT 文本，两者尚未统一；如需二次分发，请以源码文件头声明为准并处理该差异。

## 作者

江上晚（酷安） · GitHub：[jiangshangwan](https://github.com/jiangshangwan) · <liyd9745@gmail.com>
