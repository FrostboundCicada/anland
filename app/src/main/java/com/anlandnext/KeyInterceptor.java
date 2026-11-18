package com.anlandnext;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.anlandnext.awl.AwlWindowActivity;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 硬件键拦截（对齐 anland5 的 KeyInterceptor）：把 Fn/F1-F12、Back→ESC 等
 * 硬件键转发给获得焦点的 anland 窗口。
 *
 * 5.x 直接引用 MainActivity；6.x 是「一个 wayland toplevel 一个 AwlWindowActivity」，
 * 所以这里通过 {@link AwlWindowActivity#focusedLive()} 定位目标窗口，并把已转发的
 * 按键记成 evdev → 窗口 id，释放时精确回发到当时的窗口。
 */
public class KeyInterceptor extends AccessibilityService {
    private static final String TAG = "KeyInterceptor";
    private static final String PREFS_NAME = "anland_settings";
    private static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_key_intercept";

    /**
     * 本服务消费过的按键，按 设备+keycode+scancode 区分。只用 keycode 会让一个
     * 键盘的 release 抬掉另一个键盘上按下的同 keycode，也会把别的设备的 press
     * 误当成重复。
     */
    private final LinkedHashSet<KeyIdentity> pressedKeys = new LinkedHashSet<>();

    /** 已转发的 evdev → 目标窗口 id（释放时要回到当时的窗口）。 */
    private static final Map<Integer, Long> forwarded = new ConcurrentHashMap<>();

    private static final class KeyIdentity {
        final int deviceId, keyCode, scanCode;

        KeyIdentity(int deviceId, int keyCode, int scanCode) {
            this.deviceId = deviceId;
            this.keyCode = keyCode;
            this.scanCode = scanCode;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof KeyIdentity)) return false;
            KeyIdentity key = (KeyIdentity) other;
            return deviceId == key.deviceId && keyCode == key.keyCode && scanCode == key.scanCode;
        }

        @Override
        public int hashCode() {
            return (deviceId * 31 + keyCode) * 31 + scanCode;
        }
    }

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static KeyInterceptor self;
    private static boolean launchedAutomatically = false;
    private boolean enabled = false;

    public KeyInterceptor() {
        self = this;
    }

    /** 写 ENABLED_ACCESSIBILITY_SERVICES（需要 WRITE_SECURE_SETTINGS，root 下可用）。 */
    public static void launch(Context ctx) {
        try {
            String service = "com.anlandnext/.KeyInterceptor";
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (enabled == null || enabled.isEmpty())
                enabled = service;
            else if (!enabled.contains(service))
                enabled += ":" + service;

            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            launchedAutomatically = true;
        } catch (SecurityException e) {
            android.util.Log.w(TAG, "No WRITE_SECURE_SETTINGS permission", e);
            // 用户需在系统设置 > 无障碍里手动开启
        }
    }

    public static void shutdown(boolean onlyIfEnabledAutomatically) {
        if (onlyIfEnabledAutomatically && !launchedAutomatically)
            return;

        releaseHeldKeys();

        if (self != null) {
            self.disableSelf();
            self.pressedKeys.clear();
            self = null;
        }
    }

    public static boolean isLaunched() {
        AccessibilityServiceInfo info = self == null ? null : self.getServiceInfo();
        return info != null && info.getId() != null;
    }

    private static final Runnable disableImmediatelyCallback = KeyInterceptor::disableImmediately;

    private static void disableImmediately() {
        if (self == null) return;
        android.util.Log.d(TAG, "disabling interception service");
        releaseHeldKeys();
        AccessibilityServiceInfo info = self.getServiceInfo();
        info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        self.setServiceInfo(info);
        self.enabled = false;
    }

    /** 释放本服务代为按下的键：它们按在当时的窗口上，释放也要回到那个窗口。 */
    private static void releaseHeldKeys() {
        for (Map.Entry<Integer, Long> e : forwarded.entrySet()) {
            AwlWindowActivity a = e.getValue() == null ? null : AwlWindowActivity.findLive(e.getValue());
            if (a != null) a.handleAccessibilityKeyUp(e.getKey());
        }
        forwarded.clear();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        self = this;
        recheck();
    }

    public static void recheck() {
        AwlWindowActivity a = AwlWindowActivity.focusedLive();
        boolean shouldBeEnabled = (a != null && self != null) && a.isAccessibilityInterceptEnabled();
        if (self != null && shouldBeEnabled != self.enabled) {
            if (shouldBeEnabled) {
                handler.removeCallbacks(disableImmediatelyCallback);
                android.util.Log.d(TAG, "enabling interception service");
                AccessibilityServiceInfo info = self.getServiceInfo();
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                self.setServiceInfo(info);
                self.enabled = true;
            } else {
                handler.postDelayed(disableImmediatelyCallback, 120000);
            }
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        AwlWindowActivity instance = AwlWindowActivity.focusedLive();

        if (instance == null)
            return false;

        KeyIdentity identity = identityOf(event);
        boolean releaseTrackedKey = event.getAction() == KeyEvent.ACTION_UP
                && pressedKeys.contains(identity);

        // 只在窗口在前台且获得焦点时拦截；已消费按键的 release 例外（否则桌面会一直按着）。
        if (!releaseTrackedKey && !instance.hasWindowFocus())
            return false;
        boolean intercept = instance.isAccessibilityInterceptEnabled();

        boolean ret = false;
        if (intercept || releaseTrackedKey) {
            ret = forward(instance, event);
            if (ret) {
                int evdev = com.anlandnext.awl.ui.KeyResolver.resolveEvdev(event);
                if (evdev != -1 && event.getAction() == KeyEvent.ACTION_DOWN)
                    forwarded.put(evdev, instance.windowId());
                if (event.getAction() == KeyEvent.ACTION_UP)
                    forwarded.remove(evdev);
            }
        }

        if (intercept && ret && event.getAction() == KeyEvent.ACTION_DOWN)
            pressedKeys.add(identity);
        else if (event.getAction() == KeyEvent.ACTION_UP)
            pressedKeys.remove(identity);

        recheck();

        return ret;
    }

    /** 转发一个按键到目标窗口。 */
    private boolean forward(AwlWindowActivity target, KeyEvent event) {
        return target.handleAccessibilityKey(event);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}

    private static KeyIdentity identityOf(KeyEvent event) {
        return new KeyIdentity(event.getDeviceId(), event.getKeyCode(),
                event.getScanCode());
    }
}
