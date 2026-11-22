# Changelog

This is the English changelog. See [CHANGELOG.md](CHANGELOG.md) for the
Chinese one; the version-numbering rationale is documented there as well.

Releases used to be tagged `6.2-dsN`, where `dsN` named the **Droidspaces
release** the build targeted (`ds73` = Droidspaces 7.3) rather than a patch
counter. Tags are now `vMAJOR.MINOR.PATCH` so the order is readable at a
glance: `MAJOR.MINOR` follows upstream anland's 6.2 and `PATCH` continues the
old `ds` count, so `6.2-ds1` became `v6.2.1`. Every release on the Releases
page was renamed to a `vMAJOR.MINOR.PATCH` tag (the old `6.2-dsN` download
links therefore no longer resolve); `6.2-ds8` had no separate entry because its
content is `v6.2.8`, so that release was deleted to avoid a duplicate. All
content is archived below.

## 6.2.11 - 2026-11-22

### Fixed

**X popups were forced fullscreen, so Steam context menus landed in the wrong
place.** anland 6 gives every X toplevel its own Android window and stretches
that window over the screen. That holds for a main window; it does not hold for
an override-redirect popup (context menu, drop-down, completion list), which is
a small rectangle attached at a position. Promoted to a full-screen window it
no longer sits next to the pointer and taps miss it. mini-wm now recognises O-R
windows, reparents them into the toplevel under the pointer and keeps their
absolute origin, so drawing and hit testing both happen at (100,100)-style
coordinates. The owner cannot be guessed from the pointer. mini-wm used to ask only whichwindow sat under the pointer, so an owner's menu was painted into whateverother window the pointer happened to be over — in one measurement an OR-ownermenu was adopted into the Steam window. It now reads WM_TRANSIENT_FOR (and theEWMH _NET_WM_TRANSIENT_FOR that some toolkits set on override-redirect windowsinstead) and uses it when it resolves to a **mapped** root child; the pointerremains the fallback, and an unmapped parent is rejected because a popupadopted into one is invisible. Measured with the pointer over a second window:the journal still reports  andadopts into 0x2200001.

Verified with test_ord5: the journal shows
`mini-wm: mapped 0x400001`, then
`adopted O-R 0x400002 into 0x400001 at 100,100 (200x120)`, and the red menu is
drawn at (100,100) inside the window rather than at the screen origin.

**One transient portrait window pinned the X screen at a square size.** The
wl_output mode was a per-axis running maximum that never came down: once a
window larger than the current mode appeared, the mode only grew. On a
2780x1264 device this is fatal — while negotiating its size Xwayland first
attaches a 1264x2780 portrait window and only then turns into 2780x1264, which
left the X screen at 2780x2780 for the rest of the session, and every
fullscreen client read a 1:1 screen from RandR. The demand is now tracked per
window id: the mode is the per-axis maximum over live windows, floored at the
boot mode, and a shrink or a destroy (awl_output_forget) returns that window's
demand. Measured mode sequence: `1280x720 → 1280x2780 → 2780x1264`, and
`xdpyinfo` reports 2780x1264 instead of 2780x2780.

## 6.2.10 - 2026-11-21

### Added

**Microphone capture for the container.** The PulseAudio build shipped playback
only: prepare-src.sh removed the Termux `module-sles-source` entry from the
meson module list, so the host exposed an `android` sink but no capture source.
Container apps therefore saw an empty source list through the PipeWire
pulse-tunnel and could not record anything. The Termux sles source module is
now vendored (pulse/termux/module-sles-source.c) and built into
module-sles-source.so; default.pa loads it as `android_microphone` and makes it
the default source, and service.sh grants RECORD_AUDIO to com.anlandnext
before PulseAudio starts — the recorder runs under the app uid, and Android
refuses AudioRecord without the permission.

The vendored module needed two fixes before it delivered audio. It only ever
enqueued a capture buffer from its own completion callback, but OpenSL ES fires
that callback only after a buffer has already been enqueued, so the record queue
stayed empty and every block PulseAudio received was the zeroed allocation. The
queue is now primed when the source opens. The module also posted a memblock
that wrapped the live recorder buffer and then re-armed the queue, letting the
recorder rewrite the block while PulseAudio was still reading it; each captured
block is copied out first. The recorder also runs with the generic (MIC) preset instead
of VOICE_RECOGNITION: Android silences the latter whenever another client
holds a DIRECT_RECORD track, which left the container with a muted microphone.

### Fixed

**The container microphone is silent whenever no anland window is on screen.**
RECORD_AUDIO is granted in the foreground-only app-op mode, and
AudioPolicyManager re-applies the record silencing the moment the app uid stops
being foreground: the log shows `startInput on silenced input ... Unsilencing`
followed by `setRecordSilenced(general), silenced 1` in the same millisecond, and
the sles source then hands every libpulse client a full-length all-zero stream —
parecord writes a file of the right size and the container records pure silence.
Recording therefore only worked while an anland Activity was visible on an
unlocked, awake screen, which a container desktop cannot guarantee. The APK now
runs AudioBridgeService — a foreground service started by service.sh before
PulseAudio — which keeps the app uid foreground with no window and only a
minimum-importance notification. Measured on 2410CRP4CG / Android 16: with the
launcher in front and the screen off, a 3 s capture is 95.6% non-zero samples
(peak 562) instead of an all-zero file. targetSdk stays 29, so the API 34
FOREGROUND_SERVICE_MICROPHONE requirement and the while-in-use start restriction
do not apply; an older APK without the service makes the call a no-op and keeps
the Activity-based behaviour.

**PulseAudio fails to initialize OpenSL ES on Android 16** (SL_RESULT_RESOURCE_ERROR).
Background audio hardening starts at Android 16 (SDK 36), not Android 17: AudioTrack /
AAudioStream creation is rejected for any process without a visible Activity or a
non-SHORT_SERVICE foreground service. com.anlandnext has neither, so every boot
PulseAudio logged module-sles-sink.c: Failed to initialize OpenSL ES: error 9 and
fell back to auto_null, leaving container audio silent through the PipeWire
pulse-tunnel. service.sh now brings the APK to foreground on SDK 36 and above;
verified on Android 16/SDK 36 where the Android sink is created even after the
Activity is force-stopped. The v6.2.9 fix gated the foreground call to SDK 37+ and
therefore missed Android 16 devices.

**PulseAudio falls back to a null sink on a cold boot.** The foreground
call ran once and then slept three seconds; on a cold boot am start is still
refused while the device is locked, so PulseAudio hit the same createTrack
rejection and ended up on auto_null. The APK is now brought up repeatedly and
the script waits for the Activity to actually reach RESUMED. The foreground
call also moved into the start-up retry loop, because on a cold boot the
first am start is refused while the launcher is still coming up — without it
the sink stayed on auto_null for the whole boot. This entry is about the
playback path only: the recorder was muted by a different mechanism (the
foreground-only RECORD_AUDIO app-op), fixed separately by AudioBridgeService.

**Container audio handshake fails with PA_ERR_HOSTDOWN.** The container
reaches the host PulseAudio over the anland runtime socket, but the SELinux
rule only granted the droidspacesd domain connectto: the server side
(awl_daemon) still lacked accept/read/write/getopt/setopt on the accepted
socket, so the connection was established and then silently dropped —
libpipewire-module-pulse-tunnel reported "can't connect: Host is down" and the
container fell back to auto_null instead of the Android sink. Three pieces
were missing: the server-side socket permissions, the search permission on the
runtime directory (shell_data_file and the underlying system_data_file) that
has to be traversed before the socket inode can be opened, and auth-group=any on
the host PulseAudio so anonymous clients from the container are accepted.

**Container clients cannot authenticate against the host PulseAudio.** The
container runs under a different uid and cannot read the PulseAudio home
directory, so it had no way to authenticate: the anonymous handshake was
rejected and the tunnel gave up with "can't connect: Host is down". The cookie
is now published in the runtime directory, which is bind-mounted into the
container as /run/anland.

## 6.2.9 - 2026-11-21

### Fixed

**PulseAudio fails to initialize OpenSL ES on Android 17** (SL_RESULT_RESOURCE_ERROR).
Android 17 (SDK 37+) enforces background audio hardening — AudioTrack / AAudioStream
creation is rejected from any background process regardless of uid. com.anlandnext
has no Activity or foreground service, so every boot PulseAudio logged
module-sles-sink.c: Failed to initialize OpenSL ES: error 9 and fell back to
auto_null. PipeWire pulse-tunnel then had nothing to tunnel to and container
audio stayed silent. service.sh now calls am start to bring the APK to
foreground before launching PulseAudio. Once the initial AudioTrack exists the
Activity can go to home and subsequent playbacks are unaffected — the hardening
gates only createTrack, not normal play. Gated to SDK 37+ so pre-Android-17
devices see no behaviour change.

> **Note:** the SDK gate was too narrow — Android 16 (SDK 36) is affected as well.
> See v6.2.10.

## 6.2.8 - 2026-11-20

### Fixed

**Damage from scale-aware clients was uploaded as buffer pixels, leaving half
a window on the previous frame.** `wl_surface.damage` carries surface
coordinates while `wl_surface.damage_buffer` carries buffer coordinates; the
daemon used the surface-space rectangle as buffer pixels, so a scale-aware
client (a browser at zoom > 100%) had its damage uploaded at the wrong offset
and at a fraction of the size — a misplaced block over the canvas. The
rectangle is now scaled into the buffer grid (kwin's `mapToBuffer`). The
compositor keeps its own canvas (kwin's `BufferImage`), so the pixels outside
the damage rect stay correct across the client's buffer swap and only the
damaged region is uploaded: 15x less traffic than the full re-upload this
replaces.

### Performance

**Every frame re-sent an unchanged layer geometry.** The position, scale,
transform and transparency are now applied only when they actually change
instead of on every buffer latch.

## 6.2.7 - 2026-11-20

### Fixed

**Only part of the shm texture was updated on a new buffer attach.** The whole
texture is re-uploaded now, so a client that attaches a fresh buffer no longer
sees pixels left over from the previous one.

**Stale PulseAudio instances.** `service.sh` kills the old server by process
name as well as by pid, so moving `runtime_dir` can no longer leave two
servers holding two Android tracks.

### Added

**`auto_attach` now defaults to on.** The daemon starts the host activity as
soon as a wayland window is created instead of waiting for a binder SURFACE —
without it, tapping an app in anland-shell did nothing.

**Native PipeWire apps are audible.** `anlandx` installs a PipeWire tunnel sink
to the host PulseAudio, plus a watchdog that rebuilds it after a host daemon
restart.

## 6.2.6 - 2026-11-19

### Fixed

**X11 apps could not read the clipboard.** The selection only advertised
`text/plain;charset=utf-8` and `text/plain`, while Konsole and other Xwayland
clients ask Xwayland for the CLIPBOARD selection as `UTF8_STRING` (or
`TEXT`/`STRING`). With those three types advertised the paste shortcut in a
terminal finally works.

## 6.2.5 - 2026-11-19

### Fixed

**Reinstalling the module wiped `config.json`.** The release archive omits
`config.json` (`runtime_dir` is device-specific), but KernelSU replaces the
whole module directory on reinstall and deletes the existing `config.json` with
it. `runtime_dir` then fell back to the compiled default and stopped matching
the container's `bind_mounts`: the socket was invisible inside the container,
`anland-session` reported `no wayland socket at /run/anland/wayland-0`, and
anland-shell did nothing when an app was tapped. `customize.sh` now regenerates
it from the container's bind source.

### Added

**The paste shortcut is configurable.** Whether Konsole-style X11 apps honour a
paste shortcut depends on their own configuration: on the default setup
`ctrl+shift+v`, `ctrl+v` and `shift+insert` all failed while `ctrl+c` and
`shift+a` worked — so key delivery was fine and the binding was what differed.
New `paste_shortcut` key in `config.json` (default `ctrl+shift+v`).

## 6.2.4 - 2026-11-19

### Fixed

**"Daemon not connected" on some devices.** On some devices the `/system`
overlay mounts after servicemanager starts, so the `anland.host` mapping in
`plat_service_contexts` is not read and the service falls back to
`default_android_service`; the app's `getService` then returns null. The
fallback service is now accepted too (verified on the 2410CRP4CG tablet).

## 6.2.3 - 2026-11-19

### Fixed

**Windows never appeared.** `send_configure_locked` used
`wl_display_get_serial`: when the daemon had not allocated a serial yet it
returns 0, but Wayland serials start at 1 — clients discard an
`xdg_surface.configure` with serial 0, never ack it and never commit a buffer,
so the surface never maps. The daemon only saw the toplevel being created, sent
no `window_created`, Android's `g_wins` stayed empty, the app list showed
"Windows: 0", and anland-shell did nothing on tap. It now uses `next_serial`.

### Added

**Chinese input in terminals.** Terminal-style apps (Konsole/XTerm) do not
implement `zwp_text_input_v3` and Chinese has no evdev code, so the key
degradation cannot express it — which is why letters and symbols worked while
Chinese did not. Chinese now goes through the Wayland selection plus the
terminal's paste shortcut.

## 6.2.2 - 2026-11-19

This release makes the whole input chain work.

### Fixed

**Soft-keyboard text never reached apps.** `awl_ime_text()` requires
`o->enabled && o->entered` and otherwise returns silently, so every commit from
the Android IME was dropped: candidates showed up in the IME and nothing
reached the desktop. ASCII text is now degraded to `wl_keyboard` key events.

**Not a single key was sent.** KeyEvents synthesised by an IME always carry
`scanCode == 0`, and `dispatchKeyEvent` only had `fallbackSc()` to fall back
on, which covers 15 keys (Enter/Tab/Esc/arrows, ...). Letters, digits and
symbols all returned 0. The lookup now uses the full evdev table.

**The leftmost column of the key bar was swallowed by the system gesture
region.** The InputDispatcher log is direct evidence: the ESC/TAB column sits
in the back-gesture interception region at the left edge `[0,84]`. The bar is
now inset by `WindowInsets.Type.systemGestures()`.

**Recursive read locks in the `zwp_input_method_v2` implementation.**
Recursively taking `pthread_rwlock_rdlock` deadlocks once a writer is queued:
the read-lock holder waits for the writer while the writer waits for the new
read lock (classic AB-BA). Split out `awl_ime_im_state_unlocked` for callers
that already hold the lock.

**Key forwarding was not atomic.** `tr_key` called `awl_im_grabbed()` and then
`awl_im_send_key()`: the grab resource is created and destroyed by client
requests (dispatch thread) while keys arrive from the binder thread, so the
grab could be released in between and the second call became a use-after-free.
The check and the send now happen under the same lock.

**Why this was hard to find.** `LOGD` is compiled out of release builds, so
whether the fallback path fired at all was invisible. Those points use `LOGI`
now.

### Added

**Container IMEs (Chinese input).** `zwp_input_method_v2` is implemented so a
container IME such as fcitx5 can take over — the root cause of upstream 6.2
being unable to type Chinese.

**`text_input` state changes are forwarded to `input_method`.** After
`ti3_commit` applies a new `surrounding_text` / `content_type`, every active
`input_method` is notified; otherwise fcitx5's candidates and preedit were
computed from stale state.

## 6.2.1 - 2026-11-18

The fork's first 6.x release: it ports the 5.x UI and input experience onto 6.x
and adds the module and CI fixes that make it usable.

### Added

**The 5.x input UI (extra-keys bar, virtual keyboard, hardware key
interception).** A touchscreen device has no physical Enter, no way to toggle
the soft keyboard and no way to reach Fn keys. Ported to libawl's
`AwlWindowActivity` (6.x is "one wayland toplevel, one Activity"), with input
going through the new public API `Awl.sendKey` / `Awl.sendText`.

**The 5.x immersive mode (root evdev capture).** It hands the whole device to
the desktop: a root helper exclusively grabs the touchscreen, keyboard and
pointer so Android no longer sees them. 6.x only had the wayland-level pointer
lock/confine, not this layer.

**Adjustable opacity for an immersive floating key bar.** The bar floats over
the content and takes no screen space (`visibleBarHeight` returns 0); adds a
10%–100% opacity setting (default 60% translucent) where
`ExtraKeysBar.setOpacity()` drives the background alpha uniformly,
`setFloating()` is gone and settings gains a slider.

**The extra-keys bar's custom JSON layout is editable in settings.** Aligns
with anland5: a multiline monospace editor pre-filled with the current layout
(the built-in default when none), validated with
`ExtraKeysBar.validateLayout` before saving with errors reported; leaving it
empty means the built-in default, and a neutral button restores the default in
one click.

**The 5.x touchpad settings (acceleration / scroll / gestures / natural
scrolling).** A captured physical touchpad uses the `anland_settings` keys
shared with 5.x (`mouse_speed` / `scroll_speed` / `touchpad_gesture_scale` /
`scroll_reverse` / `disable_multi_finger_gestures`), and settings gains three
sliders and two switches (bilingual).

**The screen orientation setting (default / force landscape / force
portrait).** Aligns with 5.x's `screen_orientation`: `AwlWindowActivity`
applies `setRequestedOrientation` in `onCreate` and `onResume`, and settings
gains a three-way choice.

**A settings notification while a window is open.** Aligns with 5.x: when
enabled, a resident low-priority notification is posted on window resume and
tapping it returns to the settings page (`anland.action.OPEN_SETTINGS`); it is
removed on pause. The manifest declares `POST_NOTIFICATIONS`, requested at
runtime once in `onCreate` on API 33+, and settings gains a toggle.

**Optionally lock the OnePlus refresh rate during immersive sessions.** Ports
5.x's `OplusRefreshRateLease`: a process-wide reference count, acquired on
session start and released on end (once in `onImmersiveChanged(false)` and once
in `onDestroy`); settings gains an `oplus_refresh_rate_lock` switch.

### Changed

**The three HUD floating keys (SUPER / KB / ENTER) are gone.** Removes
`addHud`, `hudButton`, `sendHudKey`, `toggleHud` and the `dp` / `hudRoot`
fields; `toggleHudIme` is renamed `toggleIme`. The key bar's keyboard key still
toggles the IME and swiping up on it still toggles the virtual keyboard.

**i18n.** `libawl`'s `values-zh` gains 16 hardware key names and 4 layout
validation messages (en/zh 34:34); `MainActivity`'s hardcoded
"Attached"/"Detached" become `state_attached` / `state_detached` (app en/zh
64:64); `ExtraKeysBar.validateLayout` becomes `validateLayout(Context, String)`
and its messages come from `R.string`.

### Fixed

**The two-row key bar measured 0 high ("anland5.x had two tool rows, now there
are none").** `ExtraKeysBar`'s buttons are `GridLayout` with `height=0` +
`FILL`; the `GridLayout`'s first measure pass uses `lp.height` (=0) to produce
`EXACTLY 0`, so the buttons measure 0 high, the row height is 0, the
`WRAP_CONTENT` `GridLayout` totals 0 and the bar is completely invisible. The
buttons get an explicit height.

**Inconsistent touch-coordinate origin in immersive mode.**
`ImmersiveMode.mapContact` produces window coordinates (with `outputOrigin`
added) while `handleTouchEvent` subtracts the SurfaceView offset when sending
via `getEvtRelX/Y`; both must use the same origin, otherwise the touch position
is shifted as a whole whenever the window is offset (IME / letterbox).

**The direct input source had no immersive key bound.** Aligns with 5.x:
`input_grab.c` rejects `toggle==0` for non-Gold sources (there is no per-key
exit), so `ImmersiveInputSource.allowsUnboundToggle()` must return false and
`ImmersiveMode.startWith` must refuse to start with no key bound — rather than
allow a session that can only be left with the power key or by killing the
process.

**`anland.host` in `plat_service_contexts` was eaten by the wildcard.** The
file matches the first entry, so the `'*'` line
(`default_android_service`) must come last; the original implementation
appended with `cat ... >>`, putting `anland.host` at the end of the file where
it becomes a dead line on platform files that have a wildcard. It is inserted
before `'*'` now.

**Assorted cursor and HUD overlay fixes.** Merges everything from
`feat/cursor-keep-system-pointer`.

### Documentation

- The README points at this fork and at the release of the time, and gains a
  "why this fork" section (six fixes, plus Chinese in terminals via the
  clipboard and a configurable paste shortcut).
- The HUD section of `doc/anland5-vs-anland6-ui.{md,zh-CN.md}` now describes the
  current state (SUPER/ENTER/KB removed, replaced by the extra-keys bar over an
  immersive bottom bar; gesture-region insets; adjustable opacity).

### CI

**A tag push publishes a Release (only the three artifacts 6.x needs).** The
original workflow only uploaded a CI artifact (expires after 90 days, requires
being logged in) and had no Release; the whole point of the fork is to let
users download a "modified build".
