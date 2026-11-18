# Android version requirements: anland6 needs Android 14+

**anland6 supports Android 14 (API 34) and newer only. On Android 13 and
earlier the daemon `waylandbridge` cannot load.**

---

## Symptom

After flashing the module on an Android 13 device the daemon does not start,
and the log shows:

```
CANNOT LINK EXECUTABLE "/data/adb/modules/anland-awl/waylandbridge":
cannot locate symbol "ANativeWindow_readFromParcel" referenced by
"/data/adb/modules/anland-awl/waylandbridge"
```

(From [upstream issue #112](https://github.com/SuperTurtleDev/anland/issues/112);
reporting device: Samsung S20+ / One UI 5.1 / Android 13.)

## Cause

`waylandbridge` calls `ANativeWindow_readFromParcel()` directly when attaching
a window (`services/waylandbridge/waylandbridge.cpp`):

```cpp
ANativeWindow* anw = nullptr;
binder_status_t st = ANativeWindow_readFromParcel(in, &anw);
```

The symbol comes from the NDK header `android/native_window_aidl.h`, annotated:

```c
/**
 * Available since API level 34.
 */
binder_status_t ANativeWindow_readFromParcel(...)
    __INTRODUCED_IN(__ANDROID_API_U__);
```

i.e. it was **introduced in API 34 (Android 14)** — the AIDL version of
NativeWindow parcel support. Android 13 is API 33, so the symbol does not exist
in its system libraries.

The call is **linked directly**: no `dlsym`, no `__builtin_available` guard, no
`< 34` fallback. The code does read `android_get_device_api_level()`
(`awl_sc.cpp`), but only for other paths (`>=36` compositor backend, `>=33`
choreographer) — **there is no version branch for this symbol**.

CI builds with `PLATFORM_VER: android-36`, so **the build succeeds** (the
declaration exists in the header); the failure only appears **at runtime**, when
the dynamic linker cannot find the symbol in Android 13's
`libnativewindow.so`.

## Official stance

In [issue #112](https://github.com/SuperTurtleDev/anland/issues/112), maintainer
superturtlee replied on **2026-11-05**:

> unfortunately anland6 is not in active development because there are not
> enough developers :(

So **anland6 is not in active development and there is no plan to support
Android 13.**

## Do not confuse this with droidspaces-lkm's "Android 12/13" support

The `droidspaces-lkm` KMI coverage matrix (Android 12/13/14…) describes
**kernel modules** (`lkm4ctr.ko`, `mali_nsfix.ko`, `vkms.ko`, …) — which
**kernel** they can be loaded into. That is **kernel space**.

anland6's `waylandbridge` is a **user-space** daemon using NDK/binder. The two
have entirely independent version constraints. lkm supporting Android 13 does
**not** mean anland6's user-space stack runs on Android 13.

## If you want to rescue Android 13 yourself

A `< 34` fallback for `ANativeWindow_readFromParcel` is needed. The function
deserializes an `ANativeWindow*` from an `AParcel`; on Android 13 the older
mechanisms are the HIDL parcelable (`android::view::Surface`) or reading a
strong binder from the parcel and converting it to `ANativeWindow`.

The hard part is that **both ends of the protocol must change** (the writing
side in `AwlClient.java` / `Awl.java` as well as the daemon's reading side); it
is not a matter of adding one `if`. The project is unmaintained, so any such
change is at your own risk.
