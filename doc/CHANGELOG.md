# Changelog

## 版本号说明

本仓库早先的 Release 用 `6.2-dsN` 标记，其中 `dsN` 指的是**该版本面向的
Droidspaces 版本**（`ds73` = Droidspaces 7.3），不是补丁序号——所以序号既
不连续也不可预测。为了让版本号能像 `droidspaces-lkm` 一样一眼读出先后，
Release 改为 `vMAJOR.MINOR.PATCH`：

- `MAJOR.MINOR` 跟随上游 anland 的 6.2；
- `PATCH` 接着旧的 `ds` 计数往下走，因此 `6.2-ds1` 对应 `v6.2.1`。

Release 页面上的每个条目都按发布先后重命名为新式 tag（tag 一并移动），旧的
`6.2-dsN` 直链因此失效。`6.2-ds8` 没有对应的新条目——它的内容就是
`v6.2.8`，所以该 Release 直接删除，避免重复。它们的内容全部归档在本文件里。

| 旧 tag | 新 tag | 日期 |
|---|---|---|
| `6.2-ds1` | `v6.2.1` | 2026-11-18 |
| `6.2-ds2` | `v6.2.2` | 2026-11-19 |
| `6.2-ds3` | `v6.2.3` | 2026-11-19 |
| `6.2-ds4` | `v6.2.4` | 2026-11-19 |
| `6.2-ds5` | `v6.2.5` | 2026-11-19 |
| `6.2-ds6` | `v6.2.6` | 2026-11-19 |
| `6.2-ds7` | `v6.2.7` | 2026-11-20 |
| `6.2-ds8` | （并入 `v6.2.8`，原 Release 已删除） | 2026-11-20 |

## v6.2.10

### 新增

**容器麦克风采集。** 之前的 PulseAudio 构建只带播放：prepare-src.sh 把 Termux 的
`module-sles-source` 从 meson 模块列表里删掉了，宿主因此只有 `android` sink、
没有采集 source。容器应用经 PipeWire pulse-tunnel 看到的 source 列表是空的，
什么也录不到。现在 Termux 的 sles source 模块已内置
（`pulse/termux/module-sles-source.c`）并编进 `module-sles-source.so`；
`default.pa` 以 `android_microphone` 加载它并设为默认 source，service.sh 在
PulseAudio 启动前把 RECORD_AUDIO 授予 com.anlandnext —— 录音器跑在应用 uid 下，
缺这个权限 Android 会直接拒绝 AudioRecord。

这个内置模块要修两处才能出声音。它只在自身的完成回调里投递采集缓冲，而 OpenSL ES
只在已有缓冲入队后才会触发该回调，于是录音队列始终是空的、PulseAudio 收到的每个
数据块都是清零后的分配区；现在 source 打开时就预热队列。模块还把包住实时录音缓冲的
memblock 投递出去后立刻重新入队，录音器会在 PulseAudio 还在读的时候改写该块；现在
每个采集块先拷贝出来。录音器也改用通用（MIC）预设而非 VOICE_RECOGNITION：后者在
有客户端持有 DIRECT_RECORD 轨道时会被 Android 静音，容器麦克风因此是哑的。

### 修复

**屏幕上没有 anland 窗口时容器麦克风是哑的。** RECORD_AUDIO 以「仅前台」的 app-op
模式授予，一旦应用 uid 不再是前台，AudioPolicyManager 会立刻重新施加录音静音：
日志里 `startInput on silenced input ... Unsilencing` 之后同一毫秒就是
`setRecordSilenced(general), silenced 1`，sles source 于是交给每个 libpulse 客户端
一段等长的全零流 —— parecord 写出的文件长度正常，内容却是纯静音。录音因此只在
「anland 的 Activity 可见、且屏幕已解锁亮屏」时才有效，而容器桌面无法保证这一点。
现在 APK 运行 AudioBridgeService（由 service.sh 在 PulseAudio 之前拉起的前台服务），
用极低重要性通知把应用 uid 保持在前台，无需任何窗口。在 2410CRP4CG / Android 16 上
实测：桌面在顶层且息屏时，3 秒采集有 95.6% 非零采样（峰值 562），不再是全零文件。
targetSdk 仍为 29，因此 API 34 的 FOREGROUND_SERVICE_MICROPHONE 要求与 while-in-use
启动限制都不适用；旧版 APK 没有该服务时这个调用是空操作，行为退回基于 Activity 的
方式。

**PulseAudio 在 Android 16 上初始化 OpenSL ES 失败**（SL_RESULT_RESOURCE_ERROR）。
后台音频加固从 Android 16（SDK 36）起生效，不是 Android 17：任何既无可见 Activity、
又无非 SHORT_SERVICE 前台服务的进程，AudioTrack / AAudioStream 创建都会被拒绝。
com.anlandnext 两者都没有，因此每次开机 PulseAudio 都记录
`module-sles-sink.c: Failed to initialize OpenSL ES: error 9` 并退回 auto_null，
容器音频经 PipeWire pulse-tunnel 一直是静音。service.sh 现在在 SDK 36 及以上把 APK
拉到前台；在 Android 16/SDK 36 上验证过，即使 Activity 被强制停止，android sink
仍能建立。v6.2.9 的修复把前台调用限制在 SDK 37+，因此漏掉了 Android 16 设备。

**冷启动时 PulseAudio 退回空 sink。** 前台调用只跑一次然后睡三秒；冷启动时设备
仍处于锁定，am start 依然被拒，PulseAudio 撞上同一个 createTrack 拒绝并落到
auto_null。现在会反复拉起 APK，并等 Activity 真正进入 RESUMED。前台调用也移进了
启动重试循环，因为冷启动时第一次 am start 会在 launcher 尚未就绪时被拒 —— 没有它
整个开机过程都停在 auto_null。本条目只涉及播放路径：录音被静音是另一套机制
（「仅前台」的 RECORD_AUDIO app-op），由 AudioBridgeService 单独修复。

**容器音频握手以 PA_ERR_HOSTDOWN 失败。** 容器通过 anland 运行态 socket 连到宿主
PulseAudio，但 SELinux 规则只给了 droidspacesd 域 connectto：服务端（awl_daemon）
在已接受的 socket 上仍缺 accept/read/write/getopt/setopt，于是连接建立后又被静默
丢弃 —— `libpipewire-module-pulse-tunnel` 报 "can't connect: Host is down"，容器
退回 auto_null 而不是 Android sink。缺的是三处：服务端 socket 权限、运行目录
（shell_data_file 及其下的 system_data_file）的 search 权限（打开 socket inode 之前
必须能穿越该目录）、以及宿主 PulseAudio 的 auth-group=any（让容器里的匿名客户端被
接受）。

**容器客户端无法向宿主 PulseAudio 认证。** 容器以另一个 uid 运行，读不到
PulseAudio 的 home 目录，因此无从认证：匿名握手被拒，tunnel 以
"can't connect: Host is down" 放弃。现在 cookie 发布到运行目录，该目录以
/run/anland 绑定挂载进容器。

## v6.2.9

### 修复

**PulseAudio 在 Android 17 上初始化 OpenSL ES 失败**（SL_RESULT_RESOURCE_ERROR）。
Android 17（SDK 37+）强制执行后台音频加固 —— 无论 uid 如何，任何后台进程创建
AudioTrack / AAudioStream 都会被拒绝。com.anlandnext 既没有 Activity 也没有前台
服务，因此每次开机 PulseAudio 都记录 `module-sles-sink.c: Failed to initialize
OpenSL ES: error 9` 并退回 auto_null。PipeWire pulse-tunnel 没有可隧道的东西，
容器音频一直是静音。service.sh 现在在启动 PulseAudio 之前调用 am start 把 APK 拉到
前台。初始 AudioTrack 建立后 Activity 可以回到桌面，后续播放不受影响 —— 加固只拦
createTrack，不拦正常播放。限在 SDK 37+，因此 Android 17 之前的设备行为不变。

> **注：** SDK 门限过窄 —— Android 16（SDK 36）同样受影响。见 v6.2.10。

## v6.2.8

### 修复

**缩放感知客户端的脏区被当成 buffer 像素上传，窗口一半卡在上一个画面。**
`wl_surface.damage` 报的是 surface 坐标，`wl_surface.damage_buffer` 报的是
buffer 坐标；daemon 把 surface 空间的矩形直接当 buffer 像素用，于是
zoom > 100% 的浏览器这类客户端的脏区落在了错误位置、大小也只有应有的一部分
——画布上出现错位方块。现在矩形按 buffer 网格换算（kwin 的 `mapToBuffer`）。
合成器同时持有自己的画布（kwin 的 `BufferImage`），脏区之外的像素在客户端
换 buffer 后依然正确，因此只上传脏区：比它替代的整张重传少约 15 倍流量。

### 性能

**每帧都重发一遍没变的图层几何。** 位置、缩放、变换和透明度现在只在真正
变化时才下发，而不是每次 buffer latch 都重发。

## v6.2.7

### 修复

**新 buffer attach 后纹理只更新了一部分。** shm 纹理改为整张重传，避免
客户端 attach 一块新 buffer 后仍看到上一块的残留像素。

**残留的 PulseAudio 实例。** `service.sh` 现在除了按 pid 还会按进程名杀掉
旧服务器，`runtime_dir` 变动后不会再留下两个服务器各占一条 Android 音频轨。

### 新增

**`auto_attach` 默认打开。** daemon 在 wayland 窗口创建时就直接拉起宿主
Activity，不再等 binder SURFACE——否则 anland-shell 点应用没有反应。

**原生 PipeWire 应用有了声音。** `anlandx` 会装一个到主机 PulseAudio 的
PipeWire 隧道 sink，并带一个 watchdog：主机 daemon 重启后自动重建。

## v6.2.6

### 修复

**X11 应用读不到剪贴板。** 选区只声明 `text/plain;charset=utf-8` 与
`text/plain`，而 Konsole 这类 Xwayland 客户端向 Xwayland 要的是
`UTF8_STRING`（或 `TEXT`/`STRING`）。补上这三个类型后，终端里的粘贴键
才拿得到内容。

## v6.2.5

### 修复

**重装模块会丢 `config.json`。** Release 包里不含 `config.json`（`runtime_dir`
是设备相关的），而 KernelSU 重装模块时会整个替换模块目录，把已有的
`config.json` 一起删掉。`runtime_dir` 于是退回编译默认值，与容器的
`bind_mounts` 对不上：socket 在容器里看不见，`anland-session` 报
`no wayland socket at /run/anland/wayland-0`，anland-shell 点应用没反应。
`customize.sh` 现在按容器的 bind 源目录重新生成它。

### 新增

**粘贴键可配置。** Konsole 一类的 X11 应用是否响应粘贴取决于它自己的配置：
默认环境下 `ctrl+shift+v`、`ctrl+v`、`shift+insert` 都不行，而 `ctrl+c` 与
`shift+a` 正常——说明按键投递路径没问题，差别在绑定。新增
`config.json` 的 `paste_shortcut`（默认 `ctrl+shift+v`）。

## v6.2.4

### 修复

**部分设备上界面显示"daemon 未连接"。** 在某些设备上 `/system` 的 overlay
在 servicemanager 启动之后才挂载，`plat_service_contexts` 里的
`anland.host` 映射没被读到，服务退化成 `default_android_service`，
app 的 `getService` 返回 null。现在把 `default_android_service` 也接受为
回退（在平板 2410CRP4CG 上实测）。

## v6.2.3

### 修复

**应用窗口起不来。** `send_configure_locked` 用
`wl_display_get_serial`：daemon 还没分配过 serial 时它返回 0，而 Wayland 的
serial 从 1 开始——客户端会丢弃 serial 为 0 的 `xdg_surface.configure`，
从不 ack、从不提交 buffer，surface 永远不 map。daemon 只看得到 toplevel
被创建，没有 `window_created`，Android 侧 `g_wins` 为空，应用列表显示
"Windows: 0"，anland-shell 点应用没反应。改用 `next_serial`。

### 新增

**终端里的中文输入。** 终端型应用（Konsole/XTerm）不实现
`zwp_text_input_v3`，而中文没有 evdev 键码，按键降级路径表达不了——这就是
"字母和符号能打、中文打不出来"的原因。现在中文走 Wayland 选区加终端的
粘贴键。

## v6.2.2

本次发布把整条输入链修通。

### 修复

**软键盘文本到不了应用。** `awl_ime_text()` 要求 `o->enabled && o->entered`，
否则静默返回——于是 Android IME 的每一次 commit 都被丢掉：候选词在 IME 上
看得到，桌面上什么也没有。改为把 ASCII 文本降级成 `wl_keyboard` 按键事件。

**一个键也发不出去。** IME 合成的 KeyEvent 的 `scanCode` 恒为 0，
`dispatchKeyEvent` 只有覆盖 15 个键（Enter/Tab/Esc/方向键等）的
`fallbackSc()` 兜底，字母、数字、符号全部返回 0。改为在完整的 evdev 表里查。

**额外按键栏最左列被系统手势吃掉。** InputDispatcher 日志是直接证据：
ESC/TAB 所在的 `[0,84]` 左边缘是返回手势拦截区。底栏按
`WindowInsets.Type.systemGestures()` 让边。

**`zwp_input_method_v2` 实现里的递归读锁。** 在有写者在排队时，递归
`pthread_rwlock_rdlock` 会死锁（读者等写者，写者等新读者，经典 AB-BA）。
拆出 `awl_ime_im_state_unlocked`，调用方已持锁时用它。

**按键转发不是原子操作。** `tr_key` 先 `awl_im_grabbed()` 再
`awl_im_send_key()`：grab 资源由客户端请求（dispatch 线程）创建销毁，而按键
来自 binder 线程，两次调用之间 grab 可能已经被释放，第二次就成了
use-after-free。改为检查并发送在同一次加锁内完成。

**为什么之前查不出这些问题。** `LOGD` 在 release 构建里被编译掉，
"降级路径到底有没有触发"完全不可见。降级点改用 `LOGI`。

### 新增

**容器内 IME（中文输入）。** 实现 `zwp_input_method_v2`，让 fcitx5 之类的
容器 IME 接管，这是上游 6.2 无法输入中文的根因。

**`text_input` 状态变化同步给 `input_method`。** `ti3_commit` 更新
`surrounding_text` / `content_type` 之后通知所有活跃的 `input_method`，
否则 fcitx5 的候选词与预编辑是按过时状态算的。

## v6.2.1

本 fork 的第一个 6.x 发布：把 5.x 的界面与输入体验移植到 6.x 上，并补上
让它能真正用起来的模块与 CI 修复。

### 新增

**5.x 的输入界面（额外按键栏 / 虚拟键盘 / 硬件按键拦截）。** 触屏设备上没有
物理 Enter、没法切换软键盘、够不到 Fn 键。移植到 libawl 的
`AwlWindowActivity`（6.x 是"一个 wayland toplevel 一个 Activity"），输入走
新的公开接口 `Awl.sendKey` / `Awl.sendText`。

**5.x 的沉浸模式（root evdev 独占抓取）。** 5.x 的沉浸模式把整台设备交给
桌面：root 助手独占抓取触摸屏/键盘/指针，Android 看不到它们。6.x 原先只有
wayland 层的 pointer lock/confine，没有这一层。

**底部按键栏的可调不透明度与沉浸式浮动。** 按键栏默认浮在内容之上、不占
屏幕空间（`visibleBarHeight` 返回 0）；新增 10%–100% 的透明度设置
（默认 60% 半透明），`ExtraKeysBar.setOpacity()` 统一驱动背景 alpha，
`setFloating()` 被删除，设置页加滑块。

**设置页可直接编辑按键栏的自定义 JSON 布局。** 与 anland5 对齐：等宽多行
编辑器预填当前布局（没有则填内置默认值），保存前用
`ExtraKeysBar.validateLayout` 校验并报错；留空即用内置默认值，中性按钮一键
恢复默认。

**移植 5.x 的触控板设置（加速 / 滚动 / 手势 / 自然滚动）。** 抓取到的物理
触控板使用 `anland_settings` 里与 5.x 相同的键
（`mouse_speed` / `scroll_speed` / `touchpad_gesture_scale` / `scroll_reverse` /
`disable_multi_finger_gestures`），设置页加三个滑块与两个开关（双语）。

**移植屏幕方向设置（默认 / 强制横屏 / 强制竖屏）。** 对齐 5.x 的
`screen_orientation`：`AwlWindowActivity` 在 `onCreate` 与 `onResume` 里
`setRequestedOrientation`，设置页加三选一。

**移植"窗口打开时挂一个设置通知"。** 对齐 5.x：开启后窗口 resume 时挂一个
常驻低优先级通知，点它回到设置页（`anland.action.OPEN_SETTINGS`），pause 时
移除。Manifest 声明 `POST_NOTIFICATIONS`，API 33+ 在 `onCreate` 请求一次，
设置页加开关。

**可选在沉浸会话期间锁定一加的高刷。** 移植 5.x 的 `OplusRefreshRateLease`：
进程级引用计数，会话开始获取、结束释放（`onImmersiveChanged(false)` 与
`onDestroy` 各一次）；设置页加 `oplus_refresh_rate_lock` 开关。

### 变更

**删掉三个 HUD 悬浮键（SUPER / KB / ENTER）。** 删除 `addHud`、`hudButton`、
`sendHudKey`、`toggleHud` 以及 `dp` / `hudRoot` 字段；`toggleHudIme` 改名
`toggleIme`。按键栏上的键盘键仍然切换 IME，向上滑动仍然切换虚拟键盘。

**i18n。** `libawl` 的 `values-zh` 补上 16 个硬件键名与 4 条布局校验文案
（en/zh 34:34）；`MainActivity` 里硬编码的 "Attached"/"Detached" 改为
`state_attached` / `state_detached`（app en/zh 64:64）；
`ExtraKeysBar.validateLayout` 改为 `validateLayout(Context, String)`，文案走
`R.string`。

### 修复

**两行按键栏高度测出来是 0（"5.x 有两行工具栏，现在没有了"）。**
`ExtraKeysBar` 的按钮是 `GridLayout` 且 `height=0` + `FILL`，`GridLayout` 的
第一次测量用 `lp.height`（=0）得出 `EXACTLY 0`，按钮高度 0 → 行高 0 →
`WRAP_CONTENT` 的 `GridLayout` 合计 0，整条栏完全不可见。给按钮一个显式高度。

**沉浸模式的触摸坐标原点不一致。** `ImmersiveMode.mapContact` 产出窗口坐标
（加了 `outputOrigin`），而 `handleTouchEvent` 经 `getEvtRelX/Y` 发送时又减掉
了 SurfaceView 偏移；两处必须用同一个原点，否则窗口一旦有偏移（IME /
letterbox），触摸位置整体错位。

**直接输入源没绑沉浸键。** 对齐 5.x：`input_grab.c` 对非 Gold 源拒绝
`toggle==0`（没有逐键退出），所以
`ImmersiveInputSource.allowsUnboundToggle()` 必须返回 false，
`ImmersiveMode.startWith` 也应在没有绑定任何键时拒绝启动——而不是开一个只能
用电源键或杀进程才能退出的会话。

**`plat_service_contexts` 的 `anland.host` 被通配符吃掉。** 该文件按第一条
匹配，所以 `'*'`（`default_android_service`）必须放最后一行；原实现用
`cat … >>` 追加，把 `anland.host` 放到了文件末尾，在有通配符的平台文件上
是一行死配置。改为插到 `'*'` 之前。

**光标 / HUD 叠加层的若干修正。** 合并 `feat/cursor-keep-system-pointer`
分支的全部改动。

### 文档

- README 指向本 fork 与当时的 Release，新增"为什么用本 fork"一节
  （6 项修复，加上终端中文走剪贴板与可配置的粘贴键）。
- `doc/anland5-vs-anland6-ui.{md,zh-CN.md}` 的 HUD 一节改写为当前状态
  （SUPER/ENTER/KB 已删除，改为沉浸式底栏之上的按键栏；手势区让边；可调
  不透明度）。

### CI

**打 tag 就发布 Release（只发 6.x 需要的三个产物）。** 原 workflow 只上传
CI artifact（90 天过期、需要登录），没有 Release；fork 的意义就是让用户能
下载到"改过的构建"。
