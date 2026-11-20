# Changelog

This is the English changelog. See [CHANGELOG.md](CHANGELOG.md) for the
Chinese one; the version-numbering rationale is documented there as well.

Releases used to be tagged `6.2-dsN`, where `dsN` named the **Droidspaces
release** the build targeted (`ds73` = Droidspaces 7.3) rather than a patch
counter. Tags are now `vMAJOR.MINOR.PATCH` so the order is readable at a
glance: `MAJOR.MINOR` follows upstream anland's 6.2 and `PATCH` continues the
old `ds` count, so `6.2-ds1` became `v6.2.1`. The old tags and releases are
kept as-is; their content is archived below.

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
