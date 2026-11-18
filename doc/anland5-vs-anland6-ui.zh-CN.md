# anland 5.x 与 6.x 的 UI 差异

本文记录两代在** Android 侧界面**上的实际差异，以及 6.x 为对齐 5.x 所做的补齐。

---

## 总览

| 维度 | anland 5.x | anland 6.x |
|---|---|---|
| 显示协议 | 私有 Anland Display Protocol | Wayland |
| Android 窗口 | **整屏单窗口**（consumer Activity） | **每个应用一个窗口**（`AwlWindowActivity`） |
| 合成位置 | Android consumer 侧 | 宿主 `waylandbridge` |
| 光标来源 | 远端画进画面 | 客户端 `wl_pointer.set_cursor` |
| 悬浮控件（HUD） | **有** | 原**无**，本轮补齐 |
| 系统栏 | 隐藏 | 隐藏（相同） |
| 容器内组件 | 无（靠打过补丁的合成器） | `anlandx` 会话（wayland 链接 + rootless Xwayland + mini-wm） |

---

## 光标

### 5.x

consumer 主动把 SurfaceView 的指针图标设成 `TYPE_NULL`：

```java
private void setupCursorHiding() {
    surfaceView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
}
```

**光标由远端（Linux 侧）渲染进画面**，所以 Android 指针必须隐藏，否则会有两个。
用户在画面上看到的那个光标是 Linux 侧画出来的。

### 6.x

客户端通过标准 Wayland 协议设置光标，daemon 把它合成成最上层：

```
wl_pointer.set_cursor → awl_pointer_cursor_layer() → renderer 合成
                      → cbs.pointer_cursor(1) → AwlWindowActivity.setPointerHidden(true)
```

Android 指针同样被隐藏，光标由客户端提供。

### 6.x 的缺陷（本轮修复）

`pointer_set_cursor()` 在**第一次**被调用时就隐藏 Android 指针，但 cursor 图层只在
cursor surface 带**真实 buffer** 时才绘制（`awl_pointer_cursor_layer()` 要求
`w > 0.5 && h > 0.5`）。

于是客户端 `set_cursor(NULL)`（明确表示"我不画光标"）或 attach 了空 buffer 时：

- 客户端光标：没有（客户端没画）
- Android 指针：被隐藏（daemon 无条件隐藏了）

**结果：完全看不到指针。**

修复：只有客户端真的提供了 cursor surface 时才隐藏系统指针；`set_cursor(NULL)`
显式恢复（`pointer_cursor(0)`）。

---

## 悬浮控件（HUD）

### 5.x

consumer 在画面上叠一层悬浮控件（`hud/HudLayout`、`DefaultHudLayout2`）：

| 控件 | 位置（横屏） | 行为 |
|---|---|---|
| `SUPER`（手势球） | 右侧中部 | 点按发键；四个方向滑动发组合键；下滑切 IME |
| `ENTER` | 右下 | 发送回车 |
| `KB` | 右下（ENTER 上方） | 切换软键盘 |
| `⚙` | 左下 | 打开设置 |

`dock_enabled` 默认 `false`，dock 只在软键盘弹出时出现。

### 6.x（补齐后）

`AwlWindowActivity` 上加一层不抢焦点的悬浮层：

| 按钮 | 位置 | 行为 |
|---|---|---|
| `SUPER` | 右侧中部 | 点按发 Left Meta（evdev 125）；**长按收起/展开整排** |
| `ENTER` | 右下 | evdev `0x1c` 的 press + release |
| `KB` | 右下（ENTER 上方） | 切换软键盘（`imeWanted`） |

**没有做设置按钮**：`libawl` 是 library 模块，不能反向依赖 app 模块的
`WlSettingsActivity`，硬加会破坏库的独立性。

---

## 系统栏

两代都调用 `WindowInsetsController.hide(statusBars | navigationBars)` +
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，行为一致。

---

## 已知未解决

- **MIUI Floating Dock**（`com.miui.home` 的 `Floating Dock` 窗口，占底部
  `[0,2712]–[2136,3200]`）是**系统 launcher 的窗口**，层级高于应用窗口。
  anland6 的窗口虽然全屏（`[0,0][2136,3200]`），盖不住它。这不是 anland 的
  控件，需要在会话层处理（或确认它是否真的出现在 anland 窗口之上）。

- 5.x 的 `SUPER` 是完整手势球（四个方向的组合键滑动），6.x 目前只发 Left Meta。
---

## 相关文档

- [Android 版本要求](ANDROID-VERSION-REQUIREMENTS.zh-CN.md) ·
  [English](ANDROID-VERSION-REQUIREMENTS.en.md)
- [English version](anland5-vs-anland6-ui.en.md) —— anland6 需要
  Android 14（API 34）+；Android 13 上 `waylandbridge` 因
  `ANativeWindow_readFromParcel` 缺失而无法加载。
