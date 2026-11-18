# Android 版本要求：anland6 需要 Android 14+

**结论：anland6 只支持 Android 14（API 34）及以上。Android 13 及更早版本上，守护进程
`waylandbridge` 无法加载。**

---

## 现象

在 Android 13 设备上刷入模块后，守护进程起不来，日志里是：

```
CANNOT LINK EXECUTABLE "/data/adb/modules/anland-awl/waylandbridge":
cannot locate symbol "ANativeWindow_readFromParcel" referenced by
"/data/adb/modules/anland-awl/waylandbridge"
```

（来自 [上游 issue #112](https://github.com/SuperTurtleDev/anland/issues/112)，
报告设备：三星 S20+ / One UI 5.1 / Android 13。）

## 原因

`waylandbridge` 在挂载窗口时直接调用
`ANativeWindow_readFromParcel()`（`services/waylandbridge/waylandbridge.cpp`）：

```cpp
ANativeWindow* anw = nullptr;
binder_status_t st = ANativeWindow_readFromParcel(in, &anw);
```

这个符号来自 NDK 头 `android/native_window_aidl.h`，标注是：

```c
/**
 * Available since API level 34.
 */
binder_status_t ANativeWindow_readFromParcel(...)
    __INTRODUCED_IN(__ANDROID_API_U__);
```

即 **API 34（Android 14）才引入**（AIDL 版本的 NativeWindow parcel 支持）。
Android 13 是 API 33，系统库里没有这个符号。

调用是**直接链接**，没有 `dlsym`、没有 `__builtin_available` 保护、也没有
`< 34` 的 fallback。代码里虽然读了 `android_get_device_api_level()`
（`awl_sc.cpp`），但只用于别的路径（`>=36` 的合成后端、`>=33` 的
choreographer），**没有为这个符号做版本分支**。

CI 用 `PLATFORM_VER: android-36` 编译，所以**编译能过**（头文件里有声明），
问题只在**运行时**暴露 —— Android 13 的 `libnativewindow.so` 里没有这个符号，
动态链接直接失败。

## 官方态度

上游 [issue #112](https://github.com/SuperTurtleDev/anland/issues/112) 里，
维护者 superturtlee 于 **2026-11-05** 回复：

> unfortunately anland6 is not in active development because there are not
> enough developers :(

即 **anland6 停止活跃开发，没有 Android 13 的适配计划**。

## 不要和 droidspaces-lkm 的「支持 Android 12/13」混淆

`droidspaces-lkm` 的 KMI 覆盖矩阵（Android 12/13/14…）说的是**内核模块**
（`lkm4ctr.ko`、`mali_nsfix.ko`、`vkms.ko` 等）能在哪些内核上加载 —— 那是
**内核态**的东西。

anland6 的 `waylandbridge` 是**用户态**守护进程，走 NDK/binder，两者的版本
约束完全独立。lkm 支持 Android 13 **不代表** anland6 用户态能在 Android 13 跑。

## 如果要自己救 Android 13

需要给 `ANativeWindow_readFromParcel` 加一条 `< 34` 的替代路径。这个函数的
作用是「从 `AParcel` 反序列化出 `ANativeWindow*`」，Android 13 上可用的旧
机制是 HIDL parcelable（`android::view::Surface`）或从 parcel 读 strong
binder 再转 `ANativeWindow`。

难点在于**协议两端都要改**（`AwlClient.java` / `Awl.java` 的写入端，以及
daemon 的读取端），不是加个 `if` 就完事。官方已明确不维护，改动风险自负。
