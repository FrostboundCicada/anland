# anland 5.x vs 6.x UI differences

This documents the actual **Android-side UI** differences between the two
generations, and what 6.x added to align with 5.x.

---

## Overview

| Dimension | anland 5.x | anland 6.x |
|---|---|---|
| Display protocol | Proprietary Anland Display Protocol | Wayland |
| Android window | **one fullscreen window** (consumer Activity) | **one window per app** (`AwlWindowActivity`) |
| Compositing | on the Android consumer side | host `waylandbridge` |
| Cursor source | drawn into the frame by the far end | client `wl_pointer.set_cursor` |
| Floating controls (HUD) | **present** (SUPER/ENTER/KB/⚙) | those keys removed; now an **extra-keys bar + immersive bottom bar** |
| System bars | hidden | hidden (same) |
| In-container components | none (a patched compositor) | `anlandx` session (wayland link + rootless Xwayland + mini-wm) |

---

## Cursor

### 5.x

The consumer sets the SurfaceView pointer icon to `TYPE_NULL`:

```java
private void setupCursorHiding() {
    surfaceView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
}
```

**The cursor is rendered into the frame by the far end (Linux side)**, so the
Android pointer must be hidden — otherwise there would be two. What the user
sees is the cursor drawn by Linux.

### 6.x

The client sets the cursor through the standard Wayland protocol and the daemon
composites it as the topmost layer:

```
wl_pointer.set_cursor → awl_pointer_cursor_layer() → renderer composites
                      → cbs.pointer_cursor(1) → AwlWindowActivity.setPointerHidden(true)
```

The Android pointer is likewise hidden; the client supplies the cursor.

### 6.x defect (fixed in this branch)

`pointer_set_cursor()` hid the Android pointer on the **first** call, but the
cursor layer is only drawn when the cursor surface carries a **real buffer**
(`awl_pointer_cursor_layer()` requires `w > 0.5 && h > 0.5`).

So when the client called `set_cursor(NULL)` (explicitly drawing no cursor) or
attached an empty buffer:

- client cursor: absent (the client drew nothing)
- Android pointer: hidden (the daemon hid it unconditionally)

**Result: no pointer at all.**

Fix: only hide the system pointer once the cursor surface actually has a usable
buffer.

---

## Floating controls (HUD)

### 5.x

The consumer overlays a HUD layer (`hud/HudLayout`, `DefaultHudLayout2`):

| Control | Position (landscape) | Behaviour |
|---|---|---|
| `SUPER` (gesture ball) | mid-right | key on tap; combos on 4-way swipe; swipe down toggles IME |
| `ENTER` | bottom-right | sends Enter |
| `KB` | bottom-right (above ENTER) | toggles the soft keyboard |
| `⚙` | bottom-left | opens settings |

`dock_enabled` is `false` by default; the dock only appears while the soft
keyboard is up.

### 6.x (current)

**The `SUPER` / `ENTER` / `KB` keys were removed on request**, replaced by an
**extra-keys bar over an immersive bottom bar**:

| Element | Position | Behaviour |
|---|---|---|
| key bar (two rows) | bottom | top `ESC / - / HOME / ↑ / END / PGUP / ⌨`; bottom `TAB / CTRL / ALT / ← / ↓ / → / PGDN / ⚙`; `⌨` toggles the soft keyboard, `⚙` opens settings |
| immersive bar | bottom | **translucent by default**, adds no surface height; opacity adjustable in settings |
| display mode | — | always / with-keyboard / hidden |

Two implementation notes:

- **Gesture-region inset**: the bar pads itself by the left/right
  `WindowInsets.Type.systemGestures()`. The leftmost column (`ESC`/`TAB`) used
  to sit in the back-gesture region `[0,84]`, so the gesture swallowed the
  touch and those keys did nothing.
- **Settings live in the APK**: `libawl` is a library module and must not depend
  on the app module's `WlSettingsActivity`; the bar only emits a `⚙` signal and
  the host APK's *Window Settings* does the rest.

---

## System bars

Both generations call
`WindowInsetsController.hide(statusBars | navigationBars)` +
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`. Behaviour is identical.

---

## Known unresolved

- **Lock screen / AOD**: if the device is locked, what appears is the lock
  screen (clock, date, notifications, bottom shortcuts) with no pointer — the
  anland window sits behind it. `module/service.sh` now runs
  `svc power stayon true` + `wm dismiss-keyguard` on daemon start; the latter
  only works on devices with **no lock-screen password**.

- **MIUI Floating Dock** (`com.miui.home`, bottom `[0,2712]–[2136,3200]`) is a
  **system launcher window** above application windows. anland6's window is
  fullscreen but does not cover it. Not an anland control.

- 5.x's `SUPER` is a full gesture ball (4-way combo swipes); 6.x currently only
  sends Left Meta.

---

## See also

- [Android version requirements](ANDROID-VERSION-REQUIREMENTS.en.md) — anland6
  needs Android 14 (API 34)+; on Android 13 `waylandbridge` fails to load
  because `ANativeWindow_readFromParcel` is missing.
- [中文版](anland5-vs-anland6-ui.md)
