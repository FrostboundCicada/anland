package com.anlandnext;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/** Window behavior settings.
 *  IME mode → SharedPreferences (APK-local, read by WlWindowActivity);
 *  display zoom → daemon config.json (the daemon is the single source of
 *  truth, read/written over binder, #31).
 *  (The exit-behavior setting is gone: swiping away / killing the background
 *  is fixed to minimize-and-keep-alive, and the only close entry is the
 *  window-list long-press menu — all decisions live in the daemon, the APK
 *  has no policy left to configure.) */
public class WlSettingsActivity extends Activity {
    /** 绑定沉浸式按键时的「正在监听」窗口。 */
    private boolean listeningKey = false;
    private long listeningUntil = 0;

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        if (listeningKey && ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            listeningKey = false;
            getSharedPreferences("anland_settings", MODE_PRIVATE).edit()
                    .putInt("immersive_keycode", ev.getKeyCode())
                    .putInt("immersive_scancode", ev.getScanCode())
                    .apply();
            android.widget.Toast.makeText(this,
                    com.anlandnext.awl.ui.KeyCodeMapper.keyName(this,
                            ev.getKeyCode(), ev.getScanCode()),
                    android.widget.Toast.LENGTH_SHORT).show();
            return true;
        }
        if (listeningKey && android.os.SystemClock.elapsedRealtime() > listeningUntil)
            listeningKey = false;
        return super.dispatchKeyEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings_title);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        /* ---- IME display mode ---- */
        TextView imeTip = new TextView(this);
        imeTip.setText(R.string.ime_mode_tip);
        root.addView(imeTip);

        RadioGroup imeRg = new RadioGroup(this);
        RadioButton inset = new RadioButton(this);
        inset.setId(3);
        inset.setText(R.string.ime_mode_inset);
        RadioButton overlay = new RadioButton(this);
        overlay.setId(4);
        overlay.setText(R.string.ime_mode_overlay);

        imeRg.addView(inset);
        imeRg.addView(overlay);
        int imeMode = getSharedPreferences("awl", MODE_PRIVATE).getInt("ime_mode", 0);
        (imeMode != 0 ? overlay : inset).setChecked(true);
        imeRg.setOnCheckedChangeListener((g, checkedId) ->
                getSharedPreferences("awl", MODE_PRIVATE).edit()
                        .putInt("ime_mode", checkedId == 4 ? 1 : 0)
                        .apply());
        root.addView(imeRg);

        /* ---- Auto zoom (daemon config auto_scale: derive the zoom from the
         *      panel density so one logical pixel is one dp — a phone panel
         *      then reads as a phone viewport. Overrides the manual zoom
         *      below while on; applies immediately.) ---- */
        android.widget.Space gap15 = new android.widget.Space(this);
        gap15.setMinimumHeight(64);
        root.addView(gap15);

        TextView autoScaleTip = new TextView(this);
        autoScaleTip.setText(R.string.auto_scale_tip);
        root.addView(autoScaleTip);

        android.widget.Switch autoScaleSw = new android.widget.Switch(this);
        autoScaleSw.setText(R.string.auto_scale);
        int gotAutoScale = WlBinder.configGet("auto_scale");
        autoScaleSw.setChecked(gotAutoScale == 1);   /* daemon default off */
        /* listener AFTER the initial state: opening the page must not fire a
         * write back to the daemon */
        autoScaleSw.setOnCheckedChangeListener((b, on) ->
                WlBinder.configSet("auto_scale", on ? 1 : 0));
        root.addView(autoScaleSw);

        /* ---- Display zoom (arbitrary ratio, daemon-side; #31) ---- */
        android.widget.Space gap2 = new android.widget.Space(this);
        gap2.setMinimumHeight(64);
        root.addView(gap2);

        TextView zoomTip = new TextView(this);
        zoomTip.setText(R.string.zoom_tip);
        root.addView(zoomTip);

        TextView zoomVal = new TextView(this);
        zoomVal.setTextSize(20);
        root.addView(zoomVal);

        int got = WlBinder.configGet("zoom");
        final int[] cur = {(got < 50 || got > 500) ? 100 : got};   /* daemon down / unknown → 100 */

        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(450);          /* progress = pct - 50 → any value in 50..500 */
        seek.setProgress(cur[0] - 50);
        root.addView(seek);

        /* 200ms drag debounce + immediate on release/preset — applies
         * dynamically without bombarding per tick (each set = a
         * preferred_scale broadcast + re-configure of every window) */
        android.os.Handler h = new android.os.Handler(getMainLooper());
        final Runnable[] pending = new Runnable[1];
        Runnable apply = () -> WlBinder.configSet("zoom", cur[0]);
        java.util.function.IntConsumer showZoom = pct ->
                zoomVal.setText(pct + "%  =  " + (pct / 100.0) + "×");

        java.util.function.IntConsumer setZoom = pct -> {
            cur[0] = pct;
            seek.setProgress(pct - 50);
            showZoom.accept(pct);
            if (pending[0] != null) h.removeCallbacks(pending[0]);
            h.post(apply);   /* preset: immediate */
        };
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int prog, boolean fromUser) {
                if (!fromUser) return;
                cur[0] = prog + 50;
                showZoom.accept(cur[0]);
                if (pending[0] != null) h.removeCallbacks(pending[0]);
                int pct = cur[0];
                pending[0] = () -> WlBinder.configSet("zoom", pct);
                h.postDelayed(pending[0], 200);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                if (pending[0] != null) h.removeCallbacks(pending[0]);
                pending[0] = null;
                h.post(apply);   /* release: immediate */
            }
        });
        showZoom.accept(cur[0]);

        android.widget.LinearLayout presets = new android.widget.LinearLayout(this);
        presets.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int[] ratios = {100, 150, 175, 200, 250};
        for (int r : ratios) {
            android.widget.Button btn = new android.widget.Button(this);
            btn.setText(r + "%");
            btn.setOnClickListener(v -> setZoom.accept(r));
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            presets.addView(btn, lp);
        }
        root.addView(presets);

        /* ---- Window scaling mode (fixed-size windows under resize, daemon
         *      config scale_mode; #34 — stretch/fit-letterbox/centered 1:1) ---- */
        android.widget.Space gap25 = new android.widget.Space(this);
        gap25.setMinimumHeight(64);
        root.addView(gap25);

        TextView scaleTip = new TextView(this);
        scaleTip.setText(R.string.scale_mode_tip);
        root.addView(scaleTip);

        RadioGroup scaleRg = new RadioGroup(this);
        RadioButton stretch = new RadioButton(this);
        stretch.setId(5);   /* unique within this activity (IME group uses 3/4) */
        stretch.setText(R.string.scale_mode_stretch);
        RadioButton fit = new RadioButton(this);
        fit.setId(6);
        fit.setText(R.string.scale_mode_fit);
        RadioButton center = new RadioButton(this);
        center.setId(7);
        center.setText(R.string.scale_mode_center);
        scaleRg.addView(stretch);
        scaleRg.addView(fit);
        scaleRg.addView(center);
        int gotMode = WlBinder.configGet("scale_mode");
        if (gotMode < 0 || gotMode > 2) gotMode = 0;   /* daemon down / unknown → stretch */
        (gotMode == 2 ? center : gotMode == 1 ? fit : stretch).setChecked(true);
        /* listener AFTER the initial setChecked: opening the page must not
         * fire a write back to the daemon */
        scaleRg.setOnCheckedChangeListener((g, checkedId) ->
                WlBinder.configSet("scale_mode",
                        checkedId == 7 ? 2 : checkedId == 6 ? 1 : 0));
        root.addView(scaleRg);

        /* ---- XWayland scaling (daemon config xwayland_scale: X11 clients
         *      receive the zoom-adjusted X size on resize, then the existing
         *      stretch path scales them over the Android window) ---- */
        android.widget.Space gap26 = new android.widget.Space(this);
        gap26.setMinimumHeight(64);
        root.addView(gap26);

        TextView xwaylandScaleTip = new TextView(this);
        xwaylandScaleTip.setText(R.string.xwayland_scale_tip);
        root.addView(xwaylandScaleTip);

        android.widget.Switch xwaylandScaleSw = new android.widget.Switch(this);
        xwaylandScaleSw.setText(R.string.xwayland_scale);
        int gotXwaylandScale = WlBinder.configGet("xwayland_scale");
        xwaylandScaleSw.setChecked(gotXwaylandScale != 0); /* daemon default on */
        /* listener AFTER the initial state: opening the page must not fire a
         * write back to the daemon */
        xwaylandScaleSw.setOnCheckedChangeListener((b, on) ->
                WlBinder.configSet("xwayland_scale", on ? 1 : 0));
        root.addView(xwaylandScaleSw);

        /* ---- Auto attach (daemon config auto_attach: on = the daemon am-starts
         *      the host window the moment a wayland window is created; off =
         *      the window waits for a binder SURFACE from its app — the
         *      third-party path. Effective for windows created from now on.) */
        android.widget.Space gap24 = new android.widget.Space(this);
        gap24.setMinimumHeight(64);
        root.addView(gap24);

        TextView autoTip = new TextView(this);
        autoTip.setText(R.string.auto_attach_tip);
        root.addView(autoTip);

        android.widget.Switch autoSw = new android.widget.Switch(this);
        autoSw.setText(R.string.auto_attach);
        int gotAuto = WlBinder.configGet("auto_attach");
        autoSw.setChecked(gotAuto == 1);   /* daemon down / unknown → off */
        /* listener AFTER the initial state: opening the page must not fire a
         * write back to the daemon */
        autoSw.setOnCheckedChangeListener((b, on) -> WlBinder.configSet("auto_attach", on ? 1 : 0));
        root.addView(autoSw);

        /* ---- Initial window size (first-frame configure placeholder; #33,
         *      daemon config init_w/init_h — new windows only) ---- */
        android.widget.Space gap3 = new android.widget.Space(this);
        gap3.setMinimumHeight(64);
        root.addView(gap3);

        TextView sizeTip = new TextView(this);
        sizeTip.setText(R.string.init_size_tip);
        root.addView(sizeTip);

        final TextView sizeVal = new TextView(this);
        sizeVal.setTextSize(20);
        root.addView(sizeVal);

        /* pickers bounded to the daemon's accepted domain (a set outside it
         * is rejected — keep the UI from producing one) */
        final android.widget.NumberPicker wp = new android.widget.NumberPicker(this);
        final android.widget.NumberPicker hp = new android.widget.NumberPicker(this);
        android.widget.LinearLayout pickers = new android.widget.LinearLayout(this);
        pickers.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        TextView wl = new TextView(this);
        wl.setText(R.string.init_size_w);
        android.widget.LinearLayout.LayoutParams plp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        android.widget.LinearLayout.LayoutParams tlp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        pickers.addView(wl, tlp);
        pickers.addView(wp, plp);
        TextView hl = new TextView(this);
        hl.setText(R.string.init_size_h);
        pickers.addView(hl, tlp);
        pickers.addView(hp, plp);
        root.addView(pickers);

        int gw = WlBinder.configGet("init_w");
        int gh = WlBinder.configGet("init_h");
        final int[] sz = {(gw >= 100 && gw <= 7680) ? gw : 800,   /* daemon down → defaults */
                          (gh >= 100 && gh <= 4320) ? gh : 600};

        /* debounce like zoom (a scroll fires many changes); presets apply
         * immediately */
        final Runnable[] pendSz = new Runnable[1];
        final Runnable applySz = () -> {
            WlBinder.configSet("init_w", sz[0]);
            WlBinder.configSet("init_h", sz[1]);
        };
        final Runnable showSz = () -> sizeVal.setText(sz[0] + " × " + sz[1]);
        final Runnable changedSz = () -> {
            showSz.run();
            if (pendSz[0] != null) h.removeCallbacks(pendSz[0]);
            int w = sz[0], ht = sz[1];
            pendSz[0] = () -> {
                WlBinder.configSet("init_w", w);
                WlBinder.configSet("init_h", ht);
            };
            h.postDelayed(pendSz[0], 300);
        };
        android.widget.NumberPicker.OnValueChangeListener ncl = (p, o, n) -> {
            if (p == wp) sz[0] = n; else sz[1] = n;
            changedSz.run();
        };
        wp.setMinValue(100);
        wp.setMaxValue(7680);
        wp.setWrapSelectorWheel(false);
        wp.setValue(sz[0]);
        wp.setOnValueChangedListener(ncl);
        hp.setMinValue(100);
        hp.setMaxValue(4320);
        hp.setWrapSelectorWheel(false);
        hp.setValue(sz[1]);
        hp.setOnValueChangedListener(ncl);

        android.widget.LinearLayout sizes = new android.widget.LinearLayout(this);
        sizes.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int[][] presets2 = {{800, 600}, {1024, 768}, {1280, 720}, {1920, 1080}};
        for (int[] s : presets2) {
            android.widget.Button btn = new android.widget.Button(this);
            btn.setText(s[0] + "×" + s[1]);
            btn.setOnClickListener(v -> {
                if (pendSz[0] != null) h.removeCallbacks(pendSz[0]);
                pendSz[0] = null;
                sz[0] = s[0];
                sz[1] = s[1];
                wp.setValue(s[0]);
                hp.setValue(s[1]);
                showSz.run();
                h.post(applySz);
            });
            sizes.addView(btn, lp1());
        }
        root.addView(sizes);
        showSz.run();

        /* ---- Compositor backend (daemon config sc_enabled: on = the SC
         *      path — wayland layers become sibling ASurfaceControls
         *      composed by SurfaceFlinger/HWC, dma-buf scanout when
         *      allowed; off = the per-window GL renderer fallback.
         *      Windows already attached keep their backend until
         *      re-attach — effective for windows attached from now on.) */
        android.widget.Space gap4 = new android.widget.Space(this);
        gap4.setMinimumHeight(64);
        root.addView(gap4);

        TextView scTip = new TextView(this);
        scTip.setText(R.string.sc_backend_tip);
        root.addView(scTip);

        android.widget.Switch scSw = new android.widget.Switch(this);
        scSw.setText(R.string.sc_backend);
        int gotSc = WlBinder.configGet("sc_enabled");
        scSw.setChecked(gotSc != 0);   /* daemon down / unknown → on (the daemon default) */
        /* listener AFTER the initial state: opening the page must not fire a
         * write back to the daemon */
        scSw.setOnCheckedChangeListener((b, on) -> WlBinder.configSet("sc_enabled", on ? 1 : 0));
        root.addView(scSw);

        /* ---- anland5 风格输入 UI（额外按键栏 / 硬件键拦截）----
         * 存在 anland_settings（与 libawl 的 AwlWindowActivity 共用），
         * 改完下次 attach/ resume 生效。 */
        android.widget.Space gap5 = new android.widget.Space(this);
        gap5.setMinimumHeight(64);
        root.addView(gap5);

        SharedPreferences anland = getSharedPreferences("anland_settings", MODE_PRIVATE);

        TextView extraTip = new TextView(this);
        extraTip.setText(R.string.extra_keys_tip);
        root.addView(extraTip);

        RadioGroup extraRg = new RadioGroup(this);
        RadioButton extraAlways = new RadioButton(this);
        extraAlways.setId(8);
        extraAlways.setText(R.string.extra_keys_always);
        RadioButton extraNever = new RadioButton(this);
        extraNever.setId(9);
        extraNever.setText(R.string.extra_keys_never);
        RadioButton extraIme = new RadioButton(this);
        extraIme.setId(10);
        extraIme.setText(R.string.extra_keys_with_ime);
        extraRg.addView(extraAlways);
        extraRg.addView(extraNever);
        extraRg.addView(extraIme);
        int extraMode = anland.getInt("extra_keys_mode", 0);
        (extraMode == 1 ? extraNever : extraMode == 2 ? extraIme : extraAlways).setChecked(true);
        extraRg.setOnCheckedChangeListener((g, checkedId) ->
                anland.edit().putInt("extra_keys_mode",
                        checkedId == 9 ? 1 : checkedId == 10 ? 2 : 0).apply());
        root.addView(extraRg);

        /* 沉浸式底栏：浮在画面上、不占用屏幕空间（默认开） */
        android.widget.Switch extraFloatSw = new android.widget.Switch(this);
        extraFloatSw.setText(R.string.extra_keys_floating);
        extraFloatSw.setChecked(anland.getBoolean("extra_keys_floating", true));
        extraFloatSw.setOnCheckedChangeListener((b, on) ->
                anland.edit().putBoolean("extra_keys_floating", on).apply());
        root.addView(extraFloatSw);

        /* 底栏透明度（默认 60% = 半透明） */
        root.addView(touchpadSlider(anland, R.string.extra_keys_opacity,
                "extra_keys_opacity", 10f, 100f, 60f, v -> String.format("%.0f%%", v)));

        android.widget.Button layoutBtn = new android.widget.Button(this);
        layoutBtn.setText(R.string.extra_keys_layout);
        layoutBtn.setOnClickListener(v -> showExtraKeysLayoutEditor());
        root.addView(layoutBtn);

        android.widget.Space gap6 = new android.widget.Space(this);
        gap6.setMinimumHeight(64);
        root.addView(gap6);

        TextView keyTip = new TextView(this);
        keyTip.setText(R.string.key_intercept_tip);
        root.addView(keyTip);

        android.widget.Switch keySw = new android.widget.Switch(this);
        keySw.setText(R.string.key_intercept);
        keySw.setChecked(anland.getBoolean("accessibility_key_intercept", false));
        keySw.setOnCheckedChangeListener((b, on) -> {
            anland.edit().putBoolean("accessibility_key_intercept", on).apply();
            if (on) KeyInterceptor.launch(this);
            else KeyInterceptor.shutdown(true);
            KeyInterceptor.recheck();
        });
        root.addView(keySw);

        /* ---- 沉浸式模式（anland5 样式，root 抓 evdev）---- */
        android.widget.Space gap7 = new android.widget.Space(this);
        gap7.setMinimumHeight(64);
        root.addView(gap7);

        TextView immersiveTip = new TextView(this);
        immersiveTip.setText(R.string.immersive_tip);
        root.addView(immersiveTip);

        android.widget.Switch immersiveSw = new android.widget.Switch(this);
        immersiveSw.setText(R.string.immersive_switch);
        immersiveSw.setChecked(anland.getBoolean("immersive_enabled", false));
        immersiveSw.setOnCheckedChangeListener((b, on) ->
                anland.edit().putBoolean("immersive_enabled", on).apply());
        root.addView(immersiveSw);

        android.widget.Switch autoEnterSw = new android.widget.Switch(this);
        autoEnterSw.setText(R.string.immersive_auto_enter);
        autoEnterSw.setChecked(anland.getBoolean("immersive_auto_enter", false));
        autoEnterSw.setOnCheckedChangeListener((b, on) ->
                anland.edit().putBoolean("immersive_auto_enter", on).apply());
        root.addView(autoEnterSw);

        android.widget.Switch oplusSw = new android.widget.Switch(this);
        oplusSw.setText(R.string.immersive_oplus_refresh);
        oplusSw.setChecked(anland.getBoolean("oplus_refresh_rate_lock", false));
        oplusSw.setOnCheckedChangeListener((b, on) ->
                anland.edit().putBoolean("oplus_refresh_rate_lock", on).apply());
        root.addView(oplusSw);

        TextView bindStatus = new TextView(this);
        bindStatus.setTextSize(14);
        root.addView(bindStatus);

        Runnable showBind = () -> {
            int kc = anland.getInt("immersive_keycode", -1);
            int sc = anland.getInt("immersive_scancode", 0);
            bindStatus.setText(getString(R.string.immersive_current,
                    com.anlandnext.awl.ui.KeyCodeMapper.keyName(this, kc, sc)));
        };
        showBind.run();

        android.widget.Button bindBtn = new android.widget.Button(this);
        bindBtn.setText(R.string.immersive_bind);
        bindBtn.setOnClickListener(v -> {
            listeningKey = true;
            listeningUntil = android.os.SystemClock.elapsedRealtime() + 8000;
            android.widget.Toast.makeText(this, R.string.immersive_bind_listening,
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        root.addView(bindBtn);

        /* ---- 触摸板设置（被抓取的物理触摸板，anland5 同一组键）---- */
        android.widget.Space gap8 = new android.widget.Space(this);
        gap8.setMinimumHeight(64);
        root.addView(gap8);

        TextView touchpadTip = new TextView(this);
        touchpadTip.setText(R.string.touchpad_settings_tip);
        root.addView(touchpadTip);

        root.addView(touchpadSlider(anland, R.string.mouse_accel_label,
                "mouse_speed", 0.5f, 10f, 1.0f, v -> String.format("%.1fx", v)));
        root.addView(touchpadSlider(anland, R.string.mouse_sensitivity_label,
                "mouse_sensitivity", 0.25f, 4f, 1.0f, v -> String.format("%.2fx", v)));
        root.addView(touchpadSlider(anland, R.string.right_click_timeout_label,
                "right_click_timeout_ms", 200f, 1500f, 500f, v -> String.format("%.0f ms", v)));
        root.addView(touchpadSlider(anland, R.string.drag_activation_timeout_label,
                "drag_activation_timeout_ms", 100f, 1500f, 500f, v -> String.format("%.0f ms", v)));
        root.addView(touchpadSlider(anland, R.string.scroll_speed_label,
                "scroll_speed", 0.05f, 2f, 0.5f, v -> String.format("%.2f", v)));
        root.addView(touchpadSlider(anland, R.string.gesture_scale_label,
                "touchpad_gesture_scale", 100f, 2000f, 800f, v -> String.format("%.0f px", v)));

        android.widget.Switch scrollReverseSw = new android.widget.Switch(this);
        scrollReverseSw.setText(R.string.scroll_reverse);
        scrollReverseSw.setChecked(anland.getBoolean("scroll_reverse", false));
        scrollReverseSw.setOnCheckedChangeListener((b, on) ->
                anland.edit().putBoolean("scroll_reverse", on).apply());
        root.addView(scrollReverseSw);

        android.widget.Switch disableMultiSw = new android.widget.Switch(this);
        disableMultiSw.setText(R.string.disable_multi_finger);
        disableMultiSw.setChecked(anland.getBoolean("disable_multi_finger_gestures", false));
        disableMultiSw.setOnCheckedChangeListener((b, on) ->
                anland.edit().putBoolean("disable_multi_finger_gestures", on).apply());
        root.addView(disableMultiSw);

        /* ---- 屏幕方向（anland5 设置）---- */
        android.widget.Space gap9 = new android.widget.Space(this);
        gap9.setMinimumHeight(64);
        root.addView(gap9);

        TextView orientationTip = new TextView(this);
        orientationTip.setText(R.string.orientation_tip);
        root.addView(orientationTip);

        RadioGroup orientationRg = new RadioGroup(this);
        RadioButton oriDefault = new RadioButton(this);
        oriDefault.setId(11);
        oriDefault.setText(R.string.orientation_default);
        RadioButton oriLandscape = new RadioButton(this);
        oriLandscape.setId(12);
        oriLandscape.setText(R.string.orientation_landscape);
        RadioButton oriPortrait = new RadioButton(this);
        oriPortrait.setId(13);
        oriPortrait.setText(R.string.orientation_portrait);
        orientationRg.addView(oriDefault);
        orientationRg.addView(oriLandscape);
        orientationRg.addView(oriPortrait);
        String ori = anland.getString("screen_orientation", "default");
        ("portrait".equals(ori) ? oriPortrait : "landscape".equals(ori) ? oriLandscape : oriDefault)
                .setChecked(true);
        orientationRg.setOnCheckedChangeListener((g, checkedId) ->
                anland.edit().putString("screen_orientation",
                        checkedId == 13 ? "portrait" : checkedId == 12 ? "landscape" : "default")
                        .apply());
        root.addView(orientationRg);

        /* ---- 设置通知（anland5）---- */
        android.widget.Space gap10 = new android.widget.Space(this);
        gap10.setMinimumHeight(64);
        root.addView(gap10);

        android.widget.Switch notificationSw = new android.widget.Switch(this);
        notificationSw.setText(R.string.notification_switch);
        notificationSw.setChecked(anland.getBoolean("settings_notification", false));
        notificationSw.setOnCheckedChangeListener((b, on) ->
                anland.edit().putBoolean("settings_notification", on).apply());
        root.addView(notificationSw);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    /** 额外按键栏的自定义 JSON 布局编辑器（anland5 行为）。 */
    private void showExtraKeysLayoutEditor() {
        SharedPreferences anland = getSharedPreferences("anland_settings", MODE_PRIVATE);
        String cur = anland.getString("extra_keys_layout", null);
        if (cur == null || cur.trim().isEmpty())
            cur = com.anlandnext.awl.ui.ExtraKeysBar.defaultLayoutJson();

        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(cur);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setMinLines(10);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setPadding(48, 24, 48, 0);
        sv.addView(et);

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.extra_keys_layout)
                .setMessage(R.string.extra_keys_layout_hint)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String json = et.getText().toString();
                    if (!json.trim().isEmpty()) {
                        CharSequence err = com.anlandnext.awl.ui.ExtraKeysBar
                                .validateLayout(this, json);
                        if (err != null) {
                            android.widget.Toast.makeText(this, err,
                                    android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    anland.edit().putString("extra_keys_layout", json).apply();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.extra_keys_layout_default,
                        (d, w) -> et.setText(com.anlandnext.awl.ui.ExtraKeysBar.defaultLayoutJson()))
                .show();
    }

    /** 触摸板设置里的一行滑动条（值存 anland_settings，浮点）。 */
    private android.widget.LinearLayout touchpadSlider(SharedPreferences prefs,
            int labelRes, String key, float min, float max, float def,
            java.util.function.Function<Float, String> fmt) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.VERTICAL);

        android.widget.TextView label = new android.widget.TextView(this);
        row.addView(label);

        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        final int steps = 100;
        bar.setMax(steps);
        float cur = prefs.getFloat(key, def);
        bar.setProgress((int) Math.round((cur - min) / (max - min) * steps));
        label.setText(getString(labelRes) + "  " + fmt.apply(cur));

        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                float v = min + (max - min) * p / steps;
                label.setText(getString(labelRes) + "  " + fmt.apply(v));
                prefs.edit().putFloat(key, v).apply();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        row.addView(bar);
        return row;
    }

    private static android.widget.LinearLayout.LayoutParams lp1() {        return new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }
}
