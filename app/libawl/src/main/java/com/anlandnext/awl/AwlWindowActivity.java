package com.anlandnext.awl;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.anlandnext.awl.immersive.ImmersiveInputController;
import com.anlandnext.awl.immersive.ImmersiveMode;
import com.anlandnext.awl.immersive.OplusRefreshRateLease;
import com.anlandnext.awl.immersive.Touchpad;
import com.anlandnext.awl.ui.ExtraKeysBar;
import com.anlandnext.awl.ui.KeyResolver;
import com.anlandnext.awl.ui.VirtualKeyboardView;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One wayland window = one Activity instance (id passed as extra), running
 * INSIDE the consuming app: the Android window/task identity, process, uid
 * and binder credentials are the third-party app's — the anland host APK
 * takes no part. The Surface is reported to the daemon over binder (the
 * daemon holds the rendering resources and renders straight into this
 * window; its uid pass accepts the report because the wayland connection
 * came from {@link Awl#getWaylandFd} under the same uid).
 *
 * Lifecycle (single-attach model: this side only reports facts, all
 * decisions live in the daemon):
 *   onPause → PAUSE(id,host): daemon full detach (minimize: the wayland
 *   window stays alive); onResume / surfaceChanged → re-send SURFACE to
 *   re-attach (the daemon evicts the previous holder).
 *   Killed-from-recents / swiped away = minimize-and-keep-alive (onPause +
 *   binder death already cover it, no onDestroy report).
 *   The window is closed via Awl.closeWindow (daemon T_CLOSE). Client
 *   closed its own window / evicted by a newer instance → daemon ctrl
 *   C_CLOSE → finish.
 *
 * IME bridge (text-input protocol ↔ Android IME): hidden EditText display
 * side + a full InputConnection table bridged to the text-input protocol
 * over binder; the daemon pushes the client's editor state.
 */
public class AwlWindowActivity extends Activity implements ImmersiveMode.Host {
    private static final String TAG = "anland-awlwin";
    private static final ConcurrentHashMap<Long, AwlWindowActivity> LIVE = new ConcurrentHashMap<>();

    /** Control-channel descriptor (daemon AIBinder_Class_define "anland.ICtrl") */
    private static final String CTRL_DESC = "anland.ICtrl";
    /** daemon → Activity command codes */
    private static final int C_CLOSE = 1;      /* wayland window destroyed → exit */
    private static final int C_TITLE = 2;      /* (title:string16) title update → Recents label */
    private static final int C_IME_SHOW = 3;   /* (hint purpose:i32) text field focused → show soft keyboard */
    private static final int C_IME_HIDE = 4;   /* () text field unfocused → hide soft keyboard */
    private static final int C_IME_STATE = 5;  /* (hint purpose cursor anchor cx cy cw ch flags
                                                  + text:string16) editor state snapshot */
    private static final int C_CLIP_WRITE = 6; /* (text:string16) wl set_selection → write
                                                  the Android clipboard (empty = clear) */
    private static final int C_CAPTURE = 7;    /* (mode x y w h:i32×5) pointer-constraints
                                                  state sync (zwp lock/confine):
                                                  mode = 1 confine / 2 lock →
                                                  requestPointerCapture
                                                  (x,y,w,h = confine region
                                                  in view px, zeros = whole
                                                  window), 0 → release;
                                                  re-sent on set_region and
                                                  re-attach */
    private static final int C_CURSOR = 8;     /* (hidden:i32) wl client took over the
                                                  cursor (image composited by the
                                                  daemon renderer on top of the
                                                  window, or NULL = invisible)
                                                  → hide the Android pointer; 0 → restore */
    private static final int C_KEEPON = 9;     /* (on:i32) zwp_idle_inhibit_manager_v1
                                                  aggregate flipped: 1 →
                                                  FLAG_KEEP_SCREEN_ON, 0 → clear;
                                                  re-sent on re-attach */
    private static final int C_ICON = 10;      /* (has:i32) toplevel icon applied/reset
                                                  → re-fetch the pixels and re-apply
                                                  the task description */
    private static final int STATE_RESET = 0x1;   /* v1 reset → clear composing state + restartInput */
    private static final int CAPTURE_NONE = 0;
    private static final int CAPTURE_CONFINE = 1;
    private static final int CAPTURE_LOCK = 2;

    /** Unique Activity instance id (trailing host field of SURFACE/PAUSE: daemon eviction criterion) */
    private static final AtomicLong HOST_SEQ = new AtomicLong();

    private long id = -1;   /* -1 = unbound (bindWindowId's firstBind test; a plain long would read 0 = "bound to window 0") */
    private long host;
    private SurfaceView sv;
    private int[] svOffset = new int[2];
    private FrameLayout root;
    private EditText hiddenInput;
    private InputMethodManager imm;
    private CtrlBinder ctrl;
    private int lastW, lastH;
    private int lastImeMargin = -1;
    private WindowInsets mLastInsets;      /* 最近一次窗口 insets，底栏显隐后重算 surface 缩进 */
    private int mBarHeight;                /* 额外按键栏高度（px），0 = 不显示 */
    private boolean mBarFloating;          /* 底栏是否覆盖在画面上（不压缩 surface） */
    private boolean attached;
    private boolean finishingByGone;   /* client closed the window / evicted, nothing left to report */
    private boolean deathLinked;       /* daemon death monitoring attached */
    private String taskTitle;          /* last known client title (Recents label) */
    private android.graphics.Bitmap taskIcon;   /* last fetched toplevel icon */

    /* ---- Clipboard bridge (static shared = consistent across windows,
     *      single process) ----
     * lastClipWritten: text we wrote via C_CLIP_WRITE — setPrimaryClip fires
     * the listener too, so compare-and-suppress the echo push (otherwise
     * wl↔Android loops forever).
     * lastClipPushed:  text last pushed to the daemon (repeated focus changes
     * don't re-push). */
    private static String sLastClipWritten;
    private static String sLastClipPushed;
    private ClipboardManager clipMgr;

    /* ---- IME state (pushed by daemon ctrl + written by InputConnection; all on the UI thread) ---- */

    private String surText = "";        /* client surrounding (no preedit) */
    private int surCursor, surAnchor;   /* char indices into surText */
    private int imeHint, imePurpose;    /* zwp_text_input content type */
    private final int[] imeRect = new int[4];   /* cursor rect (surface coords) */
    private boolean imeWanted;          /* input wanted (kept across detach, basis for reopening) */
    private String compText = "";       /* mirror of the preedit we sent (inserted at surCursor) */
    private int compCursor;             /* char cursor inside the preedit */

    /* Consumer hosting hooks (Awl.attachWindow(ctx, win, HostCallbacks)):
     * fetched once per instance below; null = started by the daemon /
     * attached without hooks. Fired on the main thread inside the matching
     * lifecycle method; consumer exceptions are contained (logged, never
     * propagated into the host). */
    private Awl.HostCallbacks hostCbs;
    private Awl.WlWindow hostWin;

    private interface HostFire {
        void fire(Awl.HostCallbacks cbs, Awl.WlWindow win, Activity activity);
    }

    private void fireHost(HostFire f) {
        if (hostCbs != null) {
            try {
                f.fire(hostCbs, hostWin, this);
            } catch (Throwable t) {
                Log.e(TAG, "host lifecycle callback threw", t);
            }
        }
    }

    /** Window events while resumed: a sibling window of this app destroyed
     *  (its Activity paused, ctrl already torn down at detach) → finish that
     *  instance; own window's destroy also arrives over ctrl (C_CLOSE) —
     *  finishById is id-addressed and finish()-guarded, double delivery is
     *  harmless. */
    private final Awl.Callback winEvents = new Awl.Callback() {
        @Override public void onWindowCreated(long wid, String title) { }
        @Override public void onWindowAttached(long wid) { }
        @Override public void onWindowDetached(long wid) { }
        @Override public void onWindowDestroyed(long wid) { finishById(wid); }
    };

    /**
     * Local end of the control channel: reported to the daemon with SURFACE —
     * it is both the death token (app killed → the daemon's binder death
     * detaches automatically) and the endpoint the daemon sends commands to
     * (CLOSE / IME).
     */
    class CtrlBinder extends Binder {
        CtrlBinder() {
            /* Publish the descriptor: the daemon's libbinder_ndk
             * AIBinder_associateClass() queries it (INTERFACE_TRANSACTION)
             * and AIBinder_prepareTransaction() refuses any transaction on a
             * mismatch. */
            attachInterface(null, CTRL_DESC);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION)
                return super.onTransact(code, data, reply, flags);   /* INTERFACE_TRANSACTION / PING / DUMP: no interface token in the parcel */
            try {
                data.enforceInterface(CTRL_DESC);
            } catch (Exception e) {
                return false;   /* descriptor mismatch (not from the daemon) — reject */
            }
            if (code == C_CLOSE) {
                Log.i(TAG, "win " + id + ": CLOSE via ctrl channel");
                finishingByGone = true;
                runOnUiThread(() -> finishAndDropTask());
                return true;
            }
            if (code == C_TITLE) {
                String t = data.readString();
                if (t != null && !t.isEmpty()) {
                    taskTitle = t;
                    runOnUiThread(() -> applyTaskDescription());
                }
                return true;
            }
            if (code == C_IME_SHOW) {
                int hint = data.readInt();
                int purpose = data.readInt();
                runOnUiThread(() -> onImeShow(hint, purpose));
                return true;
            }
            if (code == C_IME_HIDE) {
                runOnUiThread(this::onImeHideCmd);
                return true;
            }
            if (code == C_IME_STATE) {
                int hint = data.readInt(), purpose = data.readInt();
                int curB = data.readInt(), ancB = data.readInt();
                int cx = data.readInt(), cy = data.readInt();
                int cw = data.readInt(), ch = data.readInt();
                int fl = data.readInt();
                String text = data.readString();
                runOnUiThread(() -> onImeState(text, curB, ancB, hint, purpose,
                                              cx, cy, cw, ch, fl));
                return true;
            }
            if (code == C_CLIP_WRITE) {
                String t = data.readString();
                final String text = t == null ? "" : t;
                runOnUiThread(() -> writeClipboard(text));
                return true;
            }
            if (code == C_CAPTURE) {
                final int mode = data.readInt();
                final int rx = data.readInt(), ry = data.readInt();
                final int rw = data.readInt(), rh = data.readInt();
                runOnUiThread(() -> setPointerCaptureMode(mode, rx, ry, rw, rh));
                return true;
            }
            if (code == C_CURSOR) {
                final boolean hidden = data.readInt() != 0;
                runOnUiThread(() -> setPointerHidden(hidden));
                return true;
            }
            if (code == C_KEEPON) {
                final boolean on = data.readInt() != 0;
                runOnUiThread(() -> applyKeepOn(on));
                return true;
            }
            if (code == C_ICON) {
                data.readInt();   /* has flag — always re-fetch (reset ⇒ fetch returns none) */
                applyTaskIconAsync();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private void onImeHideCmd() {
            onImeHide();
        }
    }

    /** Daemon (peer of the long-lived binder connection) death → this window
     *  lost its host, exit */
    private final IBinder.DeathRecipient daemonDeath = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.e(TAG, "win " + id + ": daemon gone (binder death) -> finish");
            finishingByGone = true;   /* DETACH has nowhere to go now */
            /* the wl-side selection died with the daemon — clear the push
             * bookkeeping; once a new daemon is up, the focused window
             * re-pushes the current clipboard */
            sLastClipPushed = null;
            sLastClipWritten = null;
            runOnUiThread(() -> finishAndDropTask());
        }
    };

    /** Leave for good, Recents card included. Every finish of this host
     *  means its window cannot be shown again (client quit, daemon dead,
     *  SURFACE rejected at onCreate/onResume re-attach, no id): a card left
     *  behind would relaunch onto a dead window id and exit at once. The
     *  removal is explicit rather than left to the manifest's
     *  autoRemoveFromRecents — that flag is evaluated at Task.setIntent
     *  time, so a task created before it existed, or re-entered without a
     *  setIntent re-evaluation, still carries autoRemoveRecents=false and
     *  a plain finish() would retain it. finishAndRemoveTask on the task's
     *  root activity removes the task (ActivityClientController:
     *  FINISH_TASK_WITH_ROOT_ACTIVITY → removeTask(REMOVE_FROM_RECENTS)). */
    private void finishAndDropTask() {
        if (!isFinishing()) finishAndRemoveTask();
    }

    public static void finishById(long id) {
        AwlWindowActivity a = LIVE.get(id);
        if (a != null) {
            a.finishingByGone = true;
            a.finishAndDropTask();
            Log.i(TAG, "win " + id + " finished (client gone)");
        }
    }

    /** 无障碍键拦截服务用：当前获得焦点的窗口（没有则 null）。 */
    public static AwlWindowActivity focusedLive() {
        for (AwlWindowActivity a : LIVE.values())
            if (a.hasWindowFocus()) return a;
        return null;
    }

    /** 按 daemon id 找存活的窗口（没有则 null）。 */
    public static AwlWindowActivity findLive(long id) {
        return LIVE.get(id);
    }

    /** 本窗口的 daemon id。 */
    public long windowId() { return id; }

    /** 释放一个已转发的 evdev 键（无障碍服务用）。 */
    public void handleAccessibilityKeyUp(int evdev) {
        if (id >= 0) Awl.sendKey(id, evdev, false);
    }

    /** 无障碍键拦截开关（存在 anland_settings/accessibility_key_intercept）。 */
    public boolean isAccessibilityInterceptEnabled() {
        return getSharedPreferences("anland_settings", MODE_PRIVATE)
                .getBoolean("accessibility_key_intercept", false);
    }

    /** 把一个硬件键翻译成 evdev 并转发到本窗口；返回是否被消费。 */
    public boolean handleAccessibilityKey(KeyEvent event) {
        int evdev = KeyResolver.resolveEvdev(event);
        if (evdev == -1 || id < 0) return false;
        Awl.sendKey(id, evdev, event.getAction() == KeyEvent.ACTION_DOWN);
        return true;
    }

    /** Same, plus a Recents sweep for a host whose PROCESS is already gone
     *  (LMK / force-stop while the window lived on in the daemon): there is
     *  no instance to finish, yet the task — root ActivityRecord, no process
     *  — still sits in Recents and would relaunch onto a dead window id.
     *  The manifest's autoRemoveFromRecents only fires on finish(), so the
     *  task whose base intent carries this window's URI is removed by hand.
     *  For the host APK's WINDOW_GONE receiver: a component broadcast, so
     *  it has a Context and is delivered even with the app process dead. */
    public static void finishById(android.content.Context ctx, long id) {
        finishById(id);
        removeTaskById(ctx, id);
    }

    private static void removeTaskById(android.content.Context ctx, long id) {
        if (ctx == null || id < 0) return;
        android.app.ActivityManager am =
                ctx.getSystemService(android.app.ActivityManager.class);
        if (am == null) return;
        final String uri = "anland://win/" + id;   /* Awl.attachWindow / daemon am start -d */
        try {
            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                android.app.ActivityManager.RecentTaskInfo ti = t.getTaskInfo();
                android.content.Intent base = ti != null ? ti.baseIntent : null;
                if (base == null || base.getData() == null) continue;
                if (!uri.equals(base.getData().toString())) continue;
                t.finishAndRemoveTask();
                Log.i(TAG, "win " + id + " task removed from recents (no live host)");
            }
        } catch (Exception e) {
            Log.w(TAG, "win " + id + ": recents sweep failed", e);
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        /* defensive id switch when documentLaunchMode reuses this instance for the same data URI */
        long nid = intent.getLongExtra("id", id);
        if (nid != id)
            bindWindowId(nid, intent.getStringExtra("title"), null, true);
    }

    /* ---- window binding ----
     * Everything id-dependent. Two entry flavors:
     *   fromRegistry  onCreate/onNewIntent (attachWindow / daemon am-start):
     *                 hooks come from Awl's attach registry (hostEntry)
     *   explicit      subclass late binding (hostWindow): hooks given inline
     * A re-bind to a different window switches cleanly (the onNewIntent
     * defensive path); firstBind additionally arms the daemon-death watch. */
    private void bindWindowId(long newId, String title, Awl.HostCallbacks cbs,
                              boolean fromRegistry) {
        if (newId == id || newId < 0) return;
        boolean firstBind = id < 0;
        if (!firstBind) {   /* switching away from a bound window */
            if (attached) AwlClient.pause(id, host);   /* daemon detaches the old id */
            setPointerCaptureMode(CAPTURE_NONE, 0, 0, 0, 0);   /* the old window's constraint does not carry over */
            LIVE.remove(id);
            attached = false;
        }
        id = newId;
        host = HOST_SEQ.incrementAndGet();
        ctrl = new CtrlBinder();
        /* lastW/H keep the surface size (an Android-window property, not a
         * wayland-window one): if surfaceChanged already fired while awaiting,
         * they are exactly what the catch-up below needs — resetting them
         * would deadlock the late bind (no further surfaceChanged comes for
         * an unchanged size) */
        surText = ""; surCursor = surAnchor = 0;
        compText = ""; compCursor = 0;
        imeWanted = false;
        LIVE.put(id, this);

        if (fromRegistry) {
            Awl.hostArrived(id);
            Awl.HostEntry he = Awl.hostEntry(id);
            hostCbs = he != null ? he.cbs : null;
            hostWin = he != null ? he.win : null;
        } else {
            hostWin = new Awl.WlWindow(id, true, title);
            hostCbs = cbs;
        }

        /* Recents shows the wayland window's real title (the UI itself has no title bar, only the task label) */
        if (title != null && !title.isEmpty()) {
            taskTitle = title;
            applyTaskDescription();
        }

        if (firstBind) {
            /* Long-lived binder death monitoring: daemon gone (module restart / killed) → exit, no dead windows left behind */
            deathLinked = AwlClient.monitorDeath(daemonDeath);
            if (!deathLinked && !AwlClient.available()) {
                Log.e(TAG, "win " + id + ": daemon unreachable -> finish");
                finishAndDropTask();
                return;
            }
        } else {
            Log.i(TAG, "win re-bound to id=" + id);
        }

        /* the surface may already be up (late binding: UI was built while
         * awaiting, surfaceChanged recorded its size) — report it now;
         * hosting then proceeds exactly like the started-with-id path */
        if (!attached && lastW > 0 && sv != null
                && sv.getHolder().getSurface() != null
                && sv.getHolder().getSurface().isValid())
            sendSurface(sv.getHolder(), lastW, lastH);

        fireHost((c2, w2, a2) -> c2.onHostCreate(w2, a2));
    }

    /** Subclass opt-in for LATE BINDING: return true to keep this activity
     *  alive when started WITHOUT a window id — the subclass then starts its
     *  wayland app (onCreate) and calls {@link #hostWindow} when the window
     *  appears (e.g. from {@link Awl.Callback#onWindowCreated}). super cannot
     *  be deferred until then (the platform lifecycle waits for no async
     *  event) — the bind happens inside the library instead, and hosting
     *  proceeds identically afterwards. Default false keeps the legacy
     *  behavior (no id → finish at once). */
    protected boolean onAwaitWindow() { return false; }

    /** Late-bind the hosted window (await mode only; main thread): bind THIS
     *  activity to the window — task/identity = your app — install the
     *  hosting callbacks (null = none) and, if the surface is already up,
     *  report it to the daemon immediately. */
    protected final void hostWindow(long windowId, String title, Awl.HostCallbacks cbs) {
        bindWindowId(windowId, title, cbs, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        /* the manifest theme belongs to THIS class's declaration — a SUBCLASS
         * declared without a theme falls back to the app default (title bar
         * and all). Kill the title programmatically so subclasses inherit
         * the chrome-free look with zero manifest work. */
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Exception e) {
            Log.w(TAG, "FEATURE_NO_TITLE (content already set by a subclass?)", e);
        }
        imm = getSystemService(InputMethodManager.class);
        clipMgr = getSystemService(ClipboardManager.class);

        /* Local, NOT the field: bindWindowId's first line is
         * `if (newId == id) return;` — pre-assigning the field made the bind a
         * no-op for every daemon/attachWindow-started instance (no ctrl, no
         * death token, no LIVE entry, host=0 → SURFACE attached without a
         * ctrl channel, so C_CLOSE / WINDOW_GONE could never finish it; the
         * window stayed on its last frame after the client quit). */
        long startId = getIntent().getLongExtra("id", -1);
        if (startId >= 0) {
            bindWindowId(startId, getIntent().getStringExtra("title"), null, true);
        } else if (onAwaitWindow()) {
            /* awaiting mode: no daemon-death watch yet (armed at first bind);
             * but with the daemon GONE there is nothing to wait for */
            if (!AwlClient.available()) {
                Log.e(TAG, "await: daemon unreachable -> finish");
                finishAndDropTask();
                return;
            }
            Log.i(TAG, "awaiting a wayland window (unbound)");
        } else {
            finishAndDropTask();   /* started without a window id: nothing to host, nothing to resume */
            return;
        }

        sv = new SurfaceView(this);
        sv.getHolder().setFormat(android.graphics.PixelFormat.RGBX_8888);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { }
            @Override public void surfaceChanged(SurfaceHolder holder, int format,
                                                 int width, int height) {
                sv.getLocationInWindow(svOffset);
                if (id < 0) {
                    /* still awaiting: remember the surface, report after bind */
                    Log.i(TAG, "awaiting: surface ready " + width + "x" + height);
                    lastW = width;
                    lastH = height;
                    return;
                }
                Log.i(TAG, "win " + id + " surface " + width + "x" + height);
                if (!attached)
                    sendSurface(holder, width, height);
                else if (width != lastW || height != lastH) {
                    /* window resize (rotation/split-screen/IME inset) → configure the client to follow */
                    AwlClient.resize(id, width, height);
                    lastW = width;
                    lastH = height;
                }
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                /* the daemon already detached at PAUSE; here we only clear the fact flag */
                attached = false;
            }
        });

        initHiddenInput();

        /* freeform windows report the bottom resize bar as a content inset;
         * with decor-fits enabled the DecorView pads the content up and the
         * bar strip exposes the black window background. Take over inset
         * handling so the surface draws under the bar. */
        if (android.os.Build.VERSION.SDK_INT >= 30)
            getWindow().setDecorFitsSystemWindows(false);

        root = new FrameLayout(this);
        root.setFitsSystemWindows(false);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(hiddenInput, new FrameLayout.LayoutParams(1, 1));
        /* IME inset: in inset mode the surface yields (client reflows); in overlay mode the keyboard floats above */
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyImeInset(insets);
            return insets;
        });
        setContentView(root);

        setupFullscreen();   /* immersive */
        addHud();            /* anland5 样式的悬浮控件（SUPER / ENTER / KB）*/
        addExtraKeysBar();   /* anland5 样式的底部额外按键栏 */
        addVirtualKeyboard();/* anland5 样式的浮动虚拟键盘 */
        immersive = new ImmersiveInputController(this);   /* anland5 沉浸式（root 抓 evdev）*/
        applyOrientation();   /* anland5 设置：强制横/竖屏 */
        maybeRequestNotificationPermission();
    }

    /* ---- 设置通知（anland5：窗口打开时常驻，点按回设置）---- */

    private static final String NOTIF_CHANNEL = "anland_channel";
    private static final int NOTIF_ID = 1;

    /** API 33+ 需要运行时授权，否则通知不会显示（不授权也不影响别的）。 */
    private void maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED)
            return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    /** 按设置项显示/隐藏常驻通知。 */
    private void syncSettingsNotification() {
        boolean wanted = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("settings_notification", false);
        if (wanted) showSettingsNotification();
        else hideSettingsNotification();
    }

    private void showSettingsNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(NOTIF_CHANNEL,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);

        Intent it = new Intent("anland.action.OPEN_SETTINGS");
        it.setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_preferences)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        nm.notify(NOTIF_ID, n);
    }

    private void hideSettingsNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    /** 屏幕方向（anland5 的 screen_orientation：default / landscape / portrait）。 */
    private void applyOrientation() {
        String mode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString("screen_orientation", "default");
        switch (mode) {
            case "landscape":
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                break;
            case "portrait":
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                break;
            default:
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                break;
        }
    }

    /* ================= HUD（对齐 anland5 的悬浮控件）=================
     *
     * anland5 的 consumer 在画面上叠一层悬浮控件：右侧中部一个 SUPER 手势球、
     * 右下角 ENTER / KB、左下角设置。anland6 只有窗口本身 —— 触屏设备上既
     * 没有实体回车键，也没有软键盘开关，这两样正是它缺的。
     *
     *   SUPER   点按：发送 Left Meta（evdev 125）；长按：收起/展开整排
     *   ENTER   evdev 0x1c 的 press + release
     *   KB      切换软键盘（imeWanted）
     *
     * 位置按 anland5 的横屏布局：右下 ENTER/KB，右侧中部 SUPER。
     * 半透明、不抢焦点（不含焦点性），浮在客户端画面之上。 */
    private FrameLayout hudRoot;
    private boolean hudVisible = true;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addHud() {
        hudRoot = new FrameLayout(this);
        hudRoot.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        /* 让点击穿过 HUD 的空区到达客户端画面 */
        hudRoot.setClickable(false);

        /* 右下：ENTER（下）与 KB（上），两者用 bottomMargin 错开 */
        hudRoot.addView(hudButton("ENTER", "回车", Gravity.BOTTOM | Gravity.END,
                dp(16), dp(20), v -> sendHudKey(0x1c)));
        hudRoot.addView(hudButton("KB", "软键盘", Gravity.BOTTOM | Gravity.END,
                dp(16), dp(84), v -> toggleHudIme()));

        /* 右侧中部：SUPER —— 点按发 Left Meta，长按收起/展开整排 */
        TextView sup = hudButton("SUPER", "Super：点按发送，长按收起", Gravity.CENTER_VERTICAL | Gravity.END,
                dp(16), 0, null);
        sup.setOnClickListener(v -> sendHudKey(125));
        sup.setOnLongClickListener(v -> { toggleHud(); return true; });
        hudRoot.addView(sup);

        root.addView(hudRoot);
    }

    /* ================= anland5 风格的额外按键栏 / 虚拟键盘 =================
     *
     * 这两样是 anland5 consumer 的招牌输入 UI，anland6 原先只有窗口本身。
     * 它们都通过 libawl 的公开输入 API（Awl.sendKey / Awl.sendText）发事件，
     * 显示与路由仍由本 Activity 独占（事件到达本窗口）。 */

    private static final String PREFS_NAME = "anland_settings";
    /* 0 = 总是显示, 1 = 从不, 2 = 随软键盘 */
    private static final String KEY_EXTRA_KEYS_MODE = "extra_keys_mode";
    private static final String KEY_EXTRA_KEYS_FLOAT = "extra_keys_floating";

    private ExtraKeysBar mExtraKeysBar;
    private VirtualKeyboardView mVirtualKeyboard;
    private ImmersiveInputController immersive;

    private final ExtraKeysBar.Sender mExtraSender = new ExtraKeysBar.Sender() {
        @Override public void key(int action, int evdev) {
            if (id >= 0) Awl.sendKey(id, evdev, action == 0);
        }
        @Override public void text(String s) {
            if (id >= 0) Awl.sendText(id, s);
        }
        @Override public void toggleKeyboard() { toggleHudIme(); }
        @Override public void toggleVirtualKeyboard() { toggleVirtualKeyboard(); }
        @Override public void openSettings() {
            Intent it = new Intent("anland.action.OPEN_SETTINGS");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(it);
            } catch (Exception e) {
                Log.w(TAG, "openSettings: nobody handles " + it.getAction(), e);
            }
        }
    };

    private void addExtraKeysBar() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int mode = prefs.getInt(KEY_EXTRA_KEYS_MODE, 0);
        if (mode == 1) {                        /* 从不 */
            mBarHeight = 0;
            return;
        }
        mExtraKeysBar = new ExtraKeysBar(this, mExtraSender);
        mBarFloating = prefs.getBoolean(KEY_EXTRA_KEYS_FLOAT, false);
        mExtraKeysBar.setFloating(mBarFloating);
        /* 必须给显式高度：按钮是 GridLayout 的 height=0 + FILL，第一遍测量会拿到
         * EXACTLY 0 → 行的实测高度为 0 → WRAP_CONTENT 的 GridLayout 总高就是 0，
         * 底栏会完全不可见（5.x 也是一直用显式高度，见 MainActivity.mBarHeight）。 */
        mBarHeight = Math.round(37.5f * getResources().getDisplayMetrics().density
                * mExtraKeysBar.getRowCount());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, mBarHeight, Gravity.BOTTOM);
        mExtraKeysBar.setLayoutParams(lp);
        root.addView(mExtraKeysBar);
        if (mode == 2) {
            mExtraKeysBar.setVisibility(View.GONE);  /* 随软键盘：onImeShow 再显示 */
        }
        /* 让 HUD 的 ENTER/KB 浮在按键栏之上，不重叠 */
        if (hudRoot != null) hudRoot.setTranslationY(-mBarHeight);
        applyImeInsetSafe();
    }

    private void addVirtualKeyboard() {
        mVirtualKeyboard = new VirtualKeyboardView(this);
        mVirtualKeyboard.setVisibility(View.GONE);
        mVirtualKeyboard.setOnKeyEventListener(new VirtualKeyboardView.OnKeyEventListener() {
            @Override public void onKeyDown(int scanCode) {
                if (id >= 0) Awl.sendKey(id, scanCode, true);
            }
            @Override public void onKeyUp(int scanCode) {
                if (id >= 0) Awl.sendKey(id, scanCode, false);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        mVirtualKeyboard.setLayoutParams(lp);
        root.addView(mVirtualKeyboard);
    }

    private void toggleVirtualKeyboard() {
        if (mVirtualKeyboard == null) return;
        mVirtualKeyboard.setVisibility(
                mVirtualKeyboard.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /** 额外按键栏「随软键盘」模式：IME 显示/隐藏时同步。 */
    private void syncExtraKeysFloating() {
        if (mExtraKeysBar == null) return;
        boolean floating = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_EXTRA_KEYS_FLOAT, false);
        if (floating == mBarFloating) return;
        mBarFloating = floating;
        mExtraKeysBar.setFloating(floating);
        applyImeInsetSafe();   /* 浮动时底栏覆盖画面，不再压缩 surface */
    }

    /** 额外按键栏「随软键盘」模式：IME 显示/隐藏时同步。 */
    private void syncExtraKeysBarWithIme() {
        if (mExtraKeysBar == null) return;
        int mode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_EXTRA_KEYS_MODE, 0);
        if (mode != 2) return;
        mExtraKeysBar.setVisibility(imeWanted ? View.VISIBLE : View.GONE);
        applyImeInsetSafe();   /* 底栏占的高度变了，surface 缩进要跟着变 */
    }

    private TextView hudButton(String text, String desc, int gravity,
                               int marginX, int marginY,
                               View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setContentDescription(desc);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0xCC11111B);
        int pad = dp(10);
        b.setPadding(pad * 2, pad, pad * 2, pad);
        b.setAlpha(0.85f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        lp.leftMargin = marginX;
        lp.rightMargin = marginX;
        lp.topMargin = marginY;
        lp.bottomMargin = marginY;
        b.setLayoutParams(lp);
        if (click != null) b.setOnClickListener(click);
        return b;
    }

    private void sendHudKey(int scanCode) {
        if (id < 0) return;
        AwlClient.input(id, KEY, scanCode, 0, 0, 1, 0, 0);
        AwlClient.input(id, KEY, scanCode, 0, 0, 0, 0, 0);
        Log.i(TAG, "win " + id + ": hud key " + scanCode);
    }

    private void toggleHudIme() {
        if (imeWanted) {
            onImeHide();
        } else {
            imeWanted = true;
            tryShowIme();
        }
    }

    private void toggleHud() {
        hudVisible = !hudVisible;
        if (hudRoot != null) hudRoot.setVisibility(hudVisible ? View.VISIBLE : View.GONE);
    }

    /* Immersive fullscreen: hide status bar + navigation bar, swipe-revealed
     * as transient overlays, extend into the display cutout area. */
    private void setupFullscreen() {
        android.view.WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.hide(android.view.WindowInsets.Type.statusBars()
                  | android.view.WindowInsets.Type.navigationBars());
            ic.setSystemBarsBehavior(
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        getWindow().getAttributes().layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    }

    /** Recents entry: last known client title + last fetched toplevel icon.
     *  TaskDescription is atomic — every label update must re-carry the icon
     *  or it is silently dropped. */
    private void applyTaskDescription() {
        if (taskTitle == null && taskIcon == null) return;
        android.app.ActivityManager.TaskDescription td = taskIcon != null
                ? new android.app.ActivityManager.TaskDescription(taskTitle, taskIcon)
                : new android.app.ActivityManager.TaskDescription(taskTitle);
        setTaskDescription(td);
    }

    /** Pull the daemon's stored toplevel icon (xdg-toplevel-icon-v1 pixels)
     *  off the UI thread, then swap the task description. */
    private void applyTaskIconAsync() {
        final long fid = id;
        new Thread(() -> {
            int[] wh = new int[2];
            byte[] px = AwlClient.icon(fid, wh);
            android.graphics.Bitmap bmp = null;
            if (px != null && wh[0] > 0 && wh[1] > 0) {
                try {
                    bmp = android.graphics.Bitmap.createBitmap(
                            wh[0], wh[1], android.graphics.Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px));
                } catch (Exception e) {
                    Log.w(TAG, "toplevel icon decode failed", e);
                    bmp = null;
                }
            }
            final android.graphics.Bitmap fb = bmp;
            runOnUiThread(() -> { taskIcon = fb; applyTaskDescription(); });
        }, "awl-icon").start();
    }

    /** Report the Surface to the daemon (shared by first attach / resume re-attach).
     *  rc != 0 (service gone / window missing / attach failed / uid rejected)
     *  → exit AND drop the Recents card: a rejected attach is exactly the
     *  "window id no longer exists" case (client quit while this host was
     *  paused or its process dead, daemon restarted, stale card tapped) —
     *  no placeholder instance and no dead card left behind */
    private void sendSurface(SurfaceHolder holder, int w, int h) {
        int rc = AwlClient.surface(id, w, h, holder.getSurface(), ctrl, host);
        attached = rc == 0;
        lastW = w;
        lastH = h;
        if (!attached) {
            Log.e(TAG, "win " + id + " surface binder rc=" + rc + " -> finish + remove task");
            finishAndDropTask();
            return;
        }
        applyTaskIconAsync();   /* the daemon may already hold an icon (re-attach / set before map) */
    }

    /* ---- Clipboard bridge ----
     * Android→wl: read while focused (background reads restricted on
     * Android 10+) → T_CLIPBOARD push; three entry points — listener +
     * focus-gained + onResume (the latter two cover "listener missed the
     * change" cases such as a daemon restart). Plain-text bridge only. */

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
        @Override
        public void onPrimaryClipChanged() {
            pushClipboard();
        }
    };

    /** Read the current clipboard as text; null when absent / not coercible
     *  to text / read restricted. Any item is coerced (coerceToText handles
     *  HTML/URI/Intent items). */
    private String readClipText() {
        if (clipMgr == null) return null;
        try {
            ClipData cd = clipMgr.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            ClipDescription d = cd.getDescription();
            if (d != null && d.hasMimeType("image/*") && !d.hasMimeType("text/*"))
                return null;   /* pure image clip: nothing to bridge */
            CharSequence cs = cd.getItemAt(0).coerceToText(this);
            if (cs == null || cs.length() == 0) return null;
            return cs.toString();
        } catch (SecurityException e) {
            Log.w(TAG, "win " + id + ": clipboard read denied");
            return null;
        }
    }

    private void pushClipboard() {
        if (!hasWindowFocus()) return;   /* background reads restricted; re-pushed on focus-gained */
        String t = readClipText();
        if (t == null) return;
        if (t.length() > 256 * 1024) t = t.substring(0, 256 * 1024);
        if (t.equals(sLastClipWritten) || t.equals(sLastClipPushed)) return;
        sLastClipPushed = t;
        AwlClient.clipboard(id, t);
    }

    /** wl client set_selection → write the Android clipboard (UI thread; sent by daemon ctrl) */
    private void writeClipboard(String t) {
        sLastClipWritten = t;   /* record first: setPrimaryClip fires our own listener */
        if (clipMgr == null) return;
        if (t.isEmpty()) clipMgr.clearPrimaryClip();
        else clipMgr.setPrimaryClip(ClipData.newPlainText("anland", t));
    }

    /* ---- Lifecycle → control-channel reports ---- */

    @Override
    protected void onStart() {
        super.onStart();
        fireHost((cbs, win, act) -> cbs.onHostStart(win, act));
    }

    @Override
    protected void onStop() {
        fireHost((cbs, win, act) -> cbs.onHostStop(win, act));
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupFullscreen();   /* the system may reset immersive mode */
        applyOrientation();  /* 用户在设置里改了方向 */
        syncSettingsNotification();
        syncExtraKeysFloating();   /* 设置里改了「浮动底栏」时立即生效 */
        Awl.registerCallback(winEvents);
        Awl.acquire();   /* process event subscription (sibling windows dying while this one is foreground) */
        if (clipMgr != null)
            clipMgr.addPrimaryClipChangedListener(clipListener);
        /* pause without stop (quick round-trip) → the surface survived and
         * surfaceChanged won't fire again: re-sending SURFACE here is the
         * re-attach. If the surface was rebuilt, surfaceChanged handles it */
        if (!attached && lastW > 0 && sv != null
                && sv.getHolder().getSurface() != null
                && sv.getHolder().getSurface().isValid())
            sendSurface(sv.getHolder(), lastW, lastH);
        fireHost((cbs, win, act) -> cbs.onHostResume(win, act));
        if (immersive != null) immersive.enterAutomatically();   /* anland5：resume 时自动进入 */
        Log.i(TAG, "win " + id + " RESUME");
        /* capture state is daemon-owned: the SURFACE re-attach re-pushes
         * C_CAPTURE (a persistent constraint survives the pause) */
    }

    @Override
    protected void onPause() {
        fireHost((cbs, win, act) -> cbs.onHostPause(win, act));
        if (clipMgr != null)
            clipMgr.removePrimaryClipChangedListener(clipListener);
        setPointerCaptureMode(CAPTURE_NONE, 0, 0, 0, 0);   /* release + local mode reset (the daemon mirror survives; re-pushed on re-attach) */
        if (id >= 0) endPadStream();   /* the touchpad pointer stream may be interrupted by lifecycle: make up leave/button releases */
        /* treat as minimize: daemon full detach (rendering resources freed,
         * wayland window kept alive). Clear attached locally too —
         * onResume/surfaceChanged re-attach from there */
        AwlClient.pause(id, host);
        attached = false;
        Awl.release();
        Awl.unregisterCallback(winEvents);
        if (immersive != null) immersive.stop();   /* 会话不得比前台活得久 */
        hideSettingsNotification();
        Log.i(TAG, "win " + id + " PAUSE");
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (id < 0) return;   /* awaiting: nothing to report focus for */
        AwlClient.focus(id, hasFocus);   /* focus notifies the wayland client (configure ACTIVATED) */
        if (hasFocus) {
            tryShowIme();        /* C_IME_SHOW may arrive before focus does (input state kept across re-attach) */
            pushClipboard();     /* daemon restart / listener missed the change → re-push while focused */
            applyPointerCapture();   /* the system broke the capture silently on focus loss — re-request (mode unchanged) */
        }
        Log.i(TAG, "win " + id + " focus=" + hasFocus);
    }

    /* ---- IME bridge ----
     * hidden-EditText display side: 1x1 invisible, disabled by default;
     * enable+focus+show when needed. The InputConnection below bridges the
     * full table (text → text-input protocol; queries ← state cache). */

    private void initHiddenInput() {
        hiddenInput = new EditText(this) {
            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                super.onCreateInputConnection(outAttrs);
                int sel = editorSelStart();
                outAttrs.initialSelStart = sel;
                outAttrs.initialSelEnd = sel;
                outAttrs.initialCapsMode = TextUtils.getCapsMode(editorText(), sel,
                        TextUtils.CAP_MODE_SENTENCES | TextUtils.CAP_MODE_WORDS
                        | TextUtils.CAP_MODE_CHARACTERS);
                return new WlInputConnection(this);
            }
        };
        hiddenInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        hiddenInput.setCursorVisible(false);
        hiddenInput.setAlpha(0f);
        hiddenInput.setEnabled(false);
        hiddenInput.setFocusable(false);
        hiddenInput.setFocusableInTouchMode(false);
        hiddenInput.setClickable(false);
        hiddenInput.setLongClickable(false);
        hiddenInput.setImeOptions(EditorInfo.IME_ACTION_GO
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        hiddenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_NORMAL);
    }

    /** zwp_text_input content hint/purpose → Android InputType (keyboard layout / segmentation style) */
    private int imeInputType() {
        int t;
        switch (imePurpose) {
            case 2: case 3: case 10: case 11: case 12:   /* digits number date time datetime */
                t = InputType.TYPE_CLASS_NUMBER; break;
            case 9:                                      /* pin */
                t = InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_VARIATION_PASSWORD; break;
            case 4:
                t = InputType.TYPE_CLASS_PHONE; break;
            case 5:
                t = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI; break;
            case 6:
                t = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; break;
            case 7:
                t = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PERSON_NAME; break;
            case 8:                                      /* password */
                t = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD; break;
            default:
                t = InputType.TYPE_CLASS_TEXT; break;
        }
        if ((imeHint & 0x200) != 0) t |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        if ((imeHint & 0x1) != 0) t |= InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE;
        if ((imeHint & 0x2) != 0) t |= InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        if ((imeHint & 0x4) != 0) t |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        if ((imeHint & 0x10) != 0) t |= InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
        if ((imeHint & 0x40) != 0
                && (t & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && imePurpose != 8)
            t = (t & ~InputType.TYPE_MASK_VARIATION)
                | InputType.TYPE_TEXT_VARIATION_PASSWORD;   /* hidden_text */
        return t;
    }

    /** IME display mode: 0 = inset (surface yields), 1 = overlay (floating above) */
    private boolean imeOverlayMode() {
        return getSharedPreferences("awl", MODE_PRIVATE).getInt("ime_mode", 0) != 0;
    }

    private void applyImeInset(WindowInsets insets) {
        mLastInsets = insets;
        int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
        int margin = imeOverlayMode() ? 0 : imeBottom + visibleBarHeight();
        if (margin == lastImeMargin) return;
        lastImeMargin = margin;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sv.getLayoutParams();
        lp.bottomMargin = margin;
        sv.setLayoutParams(lp);   /* surface resize → configure the client to reflow */
    }

    /** 底栏可见且非浮动时占掉的高度（要算进 surface 的底部缩进）。 */
    private int visibleBarHeight() {
        if (mExtraKeysBar == null || mBarHeight <= 0) return 0;
        if (mExtraKeysBar.getVisibility() != View.VISIBLE) return 0;
        return mBarFloating ? 0 : mBarHeight;
    }

    /** 底栏显隐/浮动变化后重算 surface 缩进（还没有 insets 时跳过）。 */
    private void applyImeInsetSafe() {
        if (mLastInsets != null) applyImeInset(mLastInsets);
    }

    private void onImeShow(int hint, int purpose) {
        imeWanted = true;
        if (hint != imeHint || purpose != imePurpose) {
            imeHint = hint;
            imePurpose = purpose;
            hiddenInput.setInputType(imeInputType());
        }
        tryShowIme();
        syncExtraKeysBarWithIme();
    }

    /** Focus may arrive late (surfaceChanged precedes onWindowFocusChanged) → show as soon as ready */
    private void tryShowIme() {
        if (!imeWanted || !hasWindowFocus() || imm == null) {
            Log.i(TAG, "win " + id + ": ime show deferred (wanted=" + imeWanted
                    + " focus=" + hasWindowFocus() + ")");
            return;
        }
        hiddenInput.setEnabled(true);
        hiddenInput.setFocusable(true);
        hiddenInput.setFocusableInTouchMode(true);
        boolean focused = hiddenInput.requestFocus();
        /* explicit (flags=0), not SHOW_IMPLICIT: the wl client asked for the
         * panel; InputMethodService.onShowInputRequested refuses implicit
         * requests while a hard keyboard is attached. */
        boolean shown = imm.showSoftInput(hiddenInput, 0);
        Log.i(TAG, "win " + id + ": ime show requestFocus=" + focused + " showSoftInput=" + shown
                + " type=0x" + Integer.toHexString(hiddenInput.getInputType()));
    }

    private void onImeHide() {
        Log.i(TAG, "win " + id + ": ime hide");
        imeWanted = false;
        if (imm != null)
            imm.hideSoftInputFromWindow(hiddenInput.getWindowToken(), 0);
        if (hiddenInput.isEnabled()) {
            hiddenInput.clearFocus();
            hiddenInput.setFocusable(false);
            hiddenInput.setFocusableInTouchMode(false);
            hiddenInput.setEnabled(false);
        }
        syncExtraKeysBarWithIme();
    }

    /** Editor state pushed by the daemon (the client's set_surrounding/cursor_rect take effect with the commit) */
    private void onImeState(String text, int curB, int ancB, int hint, int purpose,
                            int cx, int cy, int cw, int ch, int flags) {
        boolean typeChanged = hint != imeHint || purpose != imePurpose;
        surText = text == null ? "" : text;
        surCursor = Math.min(surText.length(), byteToChar(surText, curB));
        surAnchor = Math.min(surText.length(), byteToChar(surText, ancB));
        imeHint = hint;
        imePurpose = purpose;
        imeRect[0] = cx; imeRect[1] = cy; imeRect[2] = cw; imeRect[3] = ch;
        if ((flags & STATE_RESET) != 0) {
            compText = "";
            compCursor = 0;
            if (imm != null) imm.restartInput(hiddenInput);
            return;
        }
        if (typeChanged) hiddenInput.setInputType(imeInputType());
        notifyImeState();
    }

    /** Push selection/composing region/cursor anchor (IME candidate window follows the cursor, context stays in sync) */
    private void notifyImeState() {
        if (imm == null) return;
        int sel = editorSelStart();
        int candStart = -1, candEnd = -1;
        int compAt = Math.min(surCursor, surText.length());
        if (!compText.isEmpty()) {
            candStart = compAt;
            candEnd = compAt + compText.length();
        }
        imm.updateSelection(hiddenInput, sel, sel, candStart, candEnd);
        if (imeRect[2] > 0 && imeRect[3] > 0 && sv != null) {
            /* Positional parameters require a local→screen matrix
             * (CursorAnchorInfo.Builder.build throws IllegalArgumentException
             * otherwise). The daemon's rect is in surface-view pixels; the
             * IME wants screen coordinates. */
            int[] loc = new int[2];
            sv.getLocationOnScreen(loc);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setTranslate(loc[0], loc[1]);
            CursorAnchorInfo.Builder b = new CursorAnchorInfo.Builder()
                    .setMatrix(m)
                    .setInsertionMarkerLocation(imeRect[0], imeRect[1],
                                                imeRect[1] + imeRect[3],
                                                imeRect[1] + imeRect[3],
                                                CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION);
            if (!compText.isEmpty()) {
                b.setComposingText(compAt, compText);
                b.setSelectionRange(compCursor, compCursor);
            }
            try {
                imm.updateCursorAnchorInfo(hiddenInput, b.build());
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "win " + id + ": cursor anchor info rejected: " + e.getMessage());
            }
        }
    }

    private void clearComposing() {
        compText = "";
        compCursor = 0;
        notifyImeState();
    }

    /* ---- Virtual editor (IME's view: surText + compText inserted at surCursor) ---- */

    private String editorText() {
        int c = Math.min(surCursor, surText.length());
        if (compText.isEmpty()) return surText;
        return surText.substring(0, c) + compText + surText.substring(c);
    }

    private int editorSelStart() {
        return Math.min(surCursor, surText.length()) + compCursor;
    }

    /** Editor index → client surrounding index (subtract the composing-length offset) */
    private int toSurroundingIndex(int editorIdx) {
        int c = Math.min(surCursor, surText.length());
        if (editorIdx <= c) return editorIdx;
        return Math.max(c, editorIdx - compText.length());
    }

    /* ---- UTF-16 ↔ UTF-8 conversion (protocol byte offsets ↔ Java char indices) ---- */

    private static int utf8Len(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            n += cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
            i += Character.charCount(cp);
        }
        return n;
    }

    /** Byte offset → char index (stops on a code point boundary) */
    private static int byteToChar(String s, int byteOff) {
        if (byteOff <= 0) return 0;
        int chars = 0, bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int b = cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
            if (bytes + b > byteOff) return chars;
            bytes += b;
            int c = Character.charCount(cp);
            chars += c;
            i += c;
        }
        return chars;
    }

    /** Preedit cursor byte offset (newCp is relative to the end of the composing text, 1 = past the end; counted in code points) */
    private static int preeditCursorBytes(String text, int newCp) {
        int total = text.codePointCount(0, text.length());
        int pos = Math.max(0, Math.min(total, total + 1 - newCp));
        int bytes = 0, seen = 0;
        for (int i = 0; i < text.length() && seen < pos; ) {
            int cp = text.codePointAt(i);
            bytes += cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
            seen++;
            i += Character.charCount(cp);
        }
        return bytes;
    }

    /** Preedit cursor char offset (for the local mirror) */
    private static int composingCursorChars(String text, int newCp) {
        int total = text.codePointCount(0, text.length());
        int pos = Math.max(0, Math.min(total, total + 1 - newCp));
        int chars = 0, seen = 0;
        for (int i = 0; i < text.length() && seen < pos; ) {
            int cp = text.codePointAt(i);
            int c = Character.charCount(cp);
            chars += c;
            seen++;
            i += c;
        }
        return chars;
    }

    /* Step n code points backward / forward from idx (surrogate-pair safe) */
    private static int cpBack(String s, int idx, int n) {
        for (int i = 0; i < n && idx > 0; i++)
            idx = snapBack(s, idx - 1);
        return idx;
    }
    private static int cpFwd(String s, int idx, int n) {
        for (int i = 0; i < n && idx < s.length(); i++)
            idx = snap(s, idx + Character.charCount(s.codePointAt(idx)));
        return idx;
    }

    /** Snap an index to a code point boundary (forward: absorb the second half of a surrogate pair) */
    private static int snap(String s, int idx) {
        if (idx <= 0) return 0;
        if (idx >= s.length()) return s.length();
        if (Character.isHighSurrogate(s.charAt(idx - 1))
                && Character.isLowSurrogate(s.charAt(idx)))
            return idx + 1;
        return idx;
    }

    /** Snap an index to a code point boundary (backward: retreat to the first half of a surrogate pair) */
    private static int snapBack(String s, int idx) {
        if (idx <= 0) return 0;
        if (idx >= s.length()) return s.length();
        if (Character.isHighSurrogate(s.charAt(idx - 1))
                && Character.isLowSurrogate(s.charAt(idx)))
            return idx - 1;
        return idx;
    }

    /* chars / code points around the cursor → bytes (converted on the client's surrounding) */
    private int bytesBefore(int units) {
        if (units <= 0) return 0;
        int start = snapBack(surText, Math.max(0, surCursor - units));
        return utf8Len(surText.substring(start, surCursor));
    }
    private int bytesAfter(int units) {
        if (units <= 0) return 0;
        int end = snap(surText, Math.min(surText.length(), surCursor + units));
        return utf8Len(surText.substring(surCursor, end));
    }
    private int bytesBeforeCp(int cps) {
        if (cps <= 0) return 0;
        int i = surCursor, cnt = 0;
        while (i > 0 && cnt < cps) {
            i = snapBack(surText, i - 1);
            cnt++;
        }
        return utf8Len(surText.substring(i, surCursor));
    }
    private int bytesAfterCp(int cps) {
        if (cps <= 0) return 0;
        int i = surCursor, cnt = 0;
        while (i < surText.length() && cnt < cps) {
            i = snap(surText, i + Character.charCount(surText.codePointAt(i)));
            cnt++;
        }
        return utf8Len(surText.substring(surCursor, i));
    }

    /**
     * Full-table InputConnection bridge (Android IME ↔ binder passthrough to
     * the text-input protocol). Output: commitText/setComposingText/
     * deleteSurrounding… → ime ONEWAY; queries: getTextBeforeCursor/
     * getCursorCapsMode/… ← state cache (segmentation/prediction context).
     */
    private class WlInputConnection extends BaseInputConnection {
        WlInputConnection(View target) {
            super(target, false);
        }

        /* --- text output --- */

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            String t = text == null ? "" : text.toString();
            AwlClient.ime(id, AwlClient.IME_COMMIT, 0, 0, t);
            /* keep the virtual editor in step: without this a commit-only
             * session (English typing) leaves surText stale and the next
             * backspace converts to a zero-byte delete (client-side no-op) */
            if (!t.isEmpty()) {
                int c = Math.min(surCursor, surText.length());
                surText = surText.substring(0, c) + t + surText.substring(c);
                surCursor = surAnchor = c + t.length();
            }
            clearComposing();
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            String t = text == null ? "" : text.toString();
            int cb = preeditCursorBytes(t, newCursorPosition);
            AwlClient.ime(id, AwlClient.IME_PREEDIT, cb, cb, t);
            compText = t;
            compCursor = composingCursorChars(t, newCursorPosition);
            notifyImeState();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            if (!compText.isEmpty()) {
                AwlClient.ime(id, AwlClient.IME_PREEDIT, 0, 0, "");
                clearComposing();
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            deleteAround(beforeLength, afterLength, false);
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            deleteAround(beforeLength, afterLength, true);
            return true;
        }

        /* The editor must report the new selection after a successful delete
         * (imm.updateSelection from notifyImeState). LatinIME re-issues a
         * delete that produced no selection update — returning true while the
         * state cache still shows the pre-delete editor makes it delete again,
         * each retry eating another char until the field is empty. */
        private void deleteAround(int beforeChars, int afterChars, boolean codePoints) {
            AwlClient.ime(id, AwlClient.IME_DELETE,
                    codePoints ? bytesBeforeCp(beforeChars) : bytesBefore(beforeChars),
                    codePoints ? bytesAfterCp(afterChars) : bytesAfter(afterChars), "");
            int c = Math.min(surCursor, surText.length());
            int a = codePoints ? cpBack(surText, c, beforeChars)
                               : snapBack(surText, Math.max(0, c - beforeChars));
            int b = codePoints ? cpFwd(surText, c, afterChars)
                               : snap(surText, Math.min(surText.length(), c + afterChars));
            if (b > a) {
                surText = surText.substring(0, a) + surText.substring(b);
                surCursor = surAnchor = a;
                notifyImeState();
            }
        }

        @Override
        public boolean commitCompletion(CompletionInfo text) {
            if (text != null) return commitText(text.getText(), 1);
            return true;
        }

        @Override
        public boolean commitCorrection(CorrectionInfo correctionInfo) {
            /* advisory only: the IME already delivered the corrected text via
             * commitText (LatinIME sends both on autocorrect/auto-capitalize)
             * — re-applying it here would duplicate the word. TextView also
             * just records the CorrectionInfo span, no text change. */
            return true;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            if (!compText.isEmpty()) {
                AwlClient.ime(id, AwlClient.IME_PREEDIT, 0, 0, "");
                compText = "";
                compCursor = 0;
            }
            /* the region becomes preedit (long-press re-segmentation / transform); the deletion is computed around the cursor */
            String et = editorText();
            int a = snap(et, Math.max(0, Math.min(start, end)));
            int b = snap(et, Math.min(et.length(), Math.max(start, end)));
            if (a >= b) return true;
            String region = et.substring(a, b);
            int c = Math.min(surCursor, surText.length());
            AwlClient.ime(id, AwlClient.IME_DELETE,
                    utf8Len(et.substring(Math.min(a, c), Math.min(b, c))),
                    utf8Len(et.substring(Math.max(a, c), Math.max(b, c))), "");
            AwlClient.ime(id, AwlClient.IME_PREEDIT,
                    utf8Len(region), utf8Len(region), region);
            /* the field no longer holds the region (deleted above, resent as
             * preedit) — strip it from the model too, or every query would
             * report it twice (editorText = surText + compText) and the IME,
             * seeing the stale copy after commit, re-applies its correction */
            surText = surText.substring(0, a) + surText.substring(b);
            surCursor = surAnchor = a;
            compText = region;
            compCursor = region.length();
            notifyImeState();
            return true;
        }

        @Override
        public boolean setSelection(int start, int end) {
            /* v1 cursor_position; v3 has no matching event (the client's state push self-heals) */
            String et = editorText();
            int a = toSurroundingIndex(snap(et, Math.max(0, Math.min(start, end))));
            int b = toSurroundingIndex(snap(et, Math.min(et.length(), Math.max(start, end))));
            AwlClient.ime(id, AwlClient.IME_CURSOR, a, b, "");
            surCursor = a;
            surAnchor = b;
            notifyImeState();
            return true;
        }

        @Override
        public boolean replaceText(int start, int end, CharSequence text,
                                   int newCursorPosition, TextAttribute textAttribute) {
            finishComposingText();
            String et = editorText();
            int a = snap(et, Math.max(0, Math.min(start, end)));
            int b = snap(et, Math.min(et.length(), Math.max(start, end)));
            int c = Math.min(surCursor, surText.length());
            if (b > a) {
                AwlClient.ime(id, AwlClient.IME_DELETE,
                        utf8Len(et.substring(Math.min(a, c), Math.min(b, c))),
                        utf8Len(et.substring(Math.max(a, c), Math.max(b, c))), "");
                surCursor = surAnchor = toSurroundingIndex(a);
            }
            return commitText(text, newCursorPosition);
        }

        /* --- context queries (data source for IME segmentation/prediction; answered locally) --- */

        @Override
        public CharSequence getTextBeforeCursor(int length, int flags) {
            String et = editorText();
            int sel = editorSelStart();
            int start = snapBack(et, Math.max(0, sel - Math.max(0, length)));
            return et.subSequence(start, sel);
        }

        @Override
        public CharSequence getTextAfterCursor(int length, int flags) {
            String et = editorText();
            int sel = editorSelStart();
            int end = snap(et, Math.min(et.length(), sel + Math.max(0, length)));
            return et.subSequence(sel, end);
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            if (surCursor == surAnchor || surText.isEmpty()) return null;
            int a = Math.min(surCursor, surAnchor), b = Math.max(surCursor, surAnchor);
            return surText.substring(a, b);
        }

        @Override
        public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
            String et = editorText();
            int sel = editorSelStart();
            int startPos = snapBack(et, Math.max(0, sel - Math.max(0, beforeLength)));
            int endPos = snap(et, Math.min(et.length(), sel + Math.max(0, afterLength)));
            return new SurroundingText(et.substring(startPos, endPos),
                                       sel - startPos, sel - startPos, startPos);
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText t = new ExtractedText();
            t.text = editorText();
            t.startOffset = 0;
            t.selectionStart = editorSelStart();
            t.selectionEnd = editorSelStart();
            return t;
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            return TextUtils.getCapsMode(editorText(), editorSelStart(), reqModes);
        }

        /* --- misc --- */

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) {
            return true;   /* every state push already does updateCursorAnchorInfo */
        }

        @Override
        public void closeConnection() {
            compText = "";
            compCursor = 0;
            super.closeConnection();
        }
    }

    /* ---- Input passthrough: Android dispatch callbacks → binder literal
     *      translation into wayland events ----
     * Routing authority = Android (an event reaching this Activity belongs to
     * this window); the daemon keeps zero routing state.
     * Event type values = the daemon's AWL_IN_* (do not change); BTN_* = evdev codes. */

    private static final int PTR_ENTER = 1, PTR_LEAVE = 2, PTR_MOTION = 3,
            PTR_BUTTON = 4, PTR_AXIS = 5, PTR_REL = 6,
            KEY = 9, TOUCH_DOWN = 11, TOUCH_MOTION = 12, TOUCH_UP = 13,
            TOUCH_CANCEL = 14;

    /* ---- Pointer capture (zwp_pointer_constraints_v1 state sync) ----
     * The daemon only mirrors the client's constraint state (C_CAPTURE:
     * mode + confine rect in view px); activation and limiting are local:
     *   NONE    — normal mode: absolute positions, rel = absolute-position diff
     *   CONFINE — requestPointerCapture + a virtual clamped position
     *   LOCK    — requestPointerCapture, relative-only (cursor frozen)
     * The system silently breaks capture on focus loss; onWindowFocusChanged
     * re-requests it. */
    private int captureMode = CAPTURE_NONE;
    private final int[] capRect = new int[4];   /* confine region, view px (zeros = whole window) */
    private float confinex, confiney;           /* virtual clamped position (CONFINE) */

    /* confine box = capRect ∩ live window (invalid/zero rect = whole window;
     * a degenerate intersection collapses to its lower edge) */
    private float confLoX() { return capRect[2] > 0 ? Math.max(0, capRect[0]) : 0f; }
    private float confLoY() { return capRect[3] > 0 ? Math.max(0, capRect[1]) : 0f; }
    private float confHiX() {
        return capRect[2] > 0 ? Math.max(confLoX(),
                Math.min(lastW - 1f, capRect[0] + capRect[2] - 1f)) : lastW - 1f;
    }
    private float confHiY() {
        return capRect[3] > 0 ? Math.max(confLoY(),
                Math.min(lastH - 1f, capRect[1] + capRect[3] - 1f)) : lastH - 1f;
    }

    /** New constraint state from the daemon (request / set_region / destroy):
     *  adopt the mode + rect, anchor the virtual position, (re)capture. */
    private void setPointerCaptureMode(int mode, int rx, int ry, int rw, int rh) {
        captureMode = mode;
        capRect[0] = rx; capRect[1] = ry; capRect[2] = rw; capRect[3] = rh;
        if (mode == CAPTURE_CONFINE) {
            /* anchor inside the box: the current pointer when known, else the box center */
            float ax = Float.isNaN(lastMouseX) ? (confLoX() + confHiX()) / 2f : lastMouseX;
            float ay = Float.isNaN(lastMouseY) ? (confLoY() + confHiY()) / 2f : lastMouseY;
            confinex = limitRange(confLoX(), confHiX(), ax);
            confiney = limitRange(confLoY(), confHiY(), ay);
        } else if (mode == CAPTURE_LOCK) {
            /* buttons anchor at the frozen position (window center if unknown) */
            if (Float.isNaN(lastMouseX)) lastMouseX = lastW / 2f;
            if (Float.isNaN(lastMouseY)) lastMouseY = lastH / 2f;
        }
        applyPointerCapture();
        Log.i(TAG, "win " + id + ": pointer capture mode=" + mode
                + " rect=" + rx + "," + ry + " " + rw + "x" + rh);
    }

    /** (Re-)request/release the system capture without touching the anchor
     *  (focus regained: capture broke silently, the mode is unchanged). */
    private void applyPointerCapture() {
        android.view.View v = getWindow().getDecorView();
        if (captureMode != CAPTURE_NONE) v.requestPointerCapture();
        else v.releasePointerCapture();
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
        Log.i(TAG, "win " + id + ": pointer capture " + (hasCapture ? "granted" : "lost")
                + " (mode=" + captureMode + ")");   /* log-only: the daemon owns the mode */
    }

    /* ---- Client cursor (wl_pointer.set_cursor) ----
     * While the wl client drives the cursor the daemon composites the
     * client's cursor image itself — the Android system pointer must not be
     * drawn on top of it. TYPE_NULL on the views under the pointer hides it;
     * restored when the daemon reports the client cursor gone. */
    private boolean ptrHidden;

    private void setPointerHidden(boolean hidden) {
        if (ptrHidden == hidden) return;
        ptrHidden = hidden;
        PointerIcon icon = hidden ? PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL) : null;
        if (sv != null) sv.setPointerIcon(icon);
        if (root != null) root.setPointerIcon(icon);
        Log.i(TAG, "win " + id + ": android pointer " + (hidden ? "hidden (client cursor)" : "restored"));
    }

    /* ---- Idle inhibitor (zwp_idle_inhibit_manager_v1, C_KEEPON) ----
     * daemon-owned state (pure mirror sync): the FLAG_KEEP_SCREEN_ON window
     * flag is the whole implementation (honored only while visible = the
     * protocol's visible-surface semantics). */
    private void applyKeepOn(boolean on) {
        if (isFinishing()) return;
        if (on) getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Log.i(TAG, "win " + id + ": keep-screen-on " + (on ? "on (idle inhibitor)" : "off"));
    }

    private static final int BTN_LEFT = 0x110, BTN_RIGHT = 0x111,
            BTN_MIDDLE = 0x112, BTN_FORWARD = 0x115, BTN_BACK = 0x116;

    /* Mouse detection (pure source bits, no tool check) — some devices'
     * mouse hover events report TOOL_TYPE_FINGER; filtering by TOOL_TYPE_MOUSE
     * would drop them. */
    private static boolean isMouse(MotionEvent ev) {
        return (ev.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                && ev.getSource() != InputDevice.SOURCE_TOUCHSCREEN;
    }

    /* ---- Touchpad gestures (classified within the shared pointer stream) ----
     * System gesture classification (MotionEvent.getClassification), dispatched
     * BEFORE the virtual-mouse default so a classified gesture never falls
     * into pointer emulation:
     *   two-finger swipe (3) → scroll (AXIS_GESTURE_SCROLL_*_DISTANCE px)
     *   multi-finger swipe (4) / pinch (5) → touch passthrough
     *   rest (single contact / physical mouse) → virtual mouse */
    private static final int CLS_TWO_FINGER_SWIPE = 3;
    private static final int CLS_MULTI_FINGER_SWIPE = 4;
    private static final int CLS_PINCH = 5;

    private boolean padInWin;      /* pointer enter/leave pairing (protocol requires enter first) */

    private static boolean isTouchpad(MotionEvent ev) {
        return (ev.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                && ev.getSource() != InputDevice.SOURCE_TOUCHSCREEN
                && ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
    }

    /** Pointer stream teardown: release leftover buttons + leave (shared by
     *  classification switches to touch passthrough, gesture end, hover
     *  exit, and lifecycle interruptions). */
    private void endPadStream() {
        if (mouseSavedBS != 0) {
            int diff = mouseSavedBS;
            mouseSavedBS = 0;
            for (int[] b : PAD_BUTTON_MAP)
                if ((diff & b[0]) != 0)
                    AwlClient.input(id, PTR_BUTTON, b[1], lastMouseX, lastMouseY, 0, 0, 0);
        }
        if (padInWin) {
            padInWin = false;
            AwlClient.input(id, PTR_LEAVE, 0, 0, 0, 0, 0, 0);
        }
        lastMouseX = lastMouseY = Float.NaN;   /* no rel diff across a leave */
    }

    private float getEvtRelX(MotionEvent ev, int idx) {
        return ev.getX(idx) - svOffset[0];
    }

    private float getEvtRelY(MotionEvent ev, int idx) {
        return ev.getY(idx) - svOffset[1];
    }

    /** Make up the pointer enter at the mode-valid position (NONE = raw event
     *  position, CONFINE = the virtual clamped position, LOCK = the frozen
     *  anchor, window center when unknown) — protocol requires enter before
     *  motion/button/axis. */
    private void padEnter(MotionEvent ev) {
        padInWin = true;
        switch (captureMode) {
        case CAPTURE_LOCK:
            if (Float.isNaN(lastMouseX)) lastMouseX = lastW / 2f;
            if (Float.isNaN(lastMouseY)) lastMouseY = lastH / 2f;
            break;
        case CAPTURE_CONFINE:
            lastMouseX = confinex;
            lastMouseY = confiney;
            break;
        default:
            lastMouseX = getEvtRelX(ev, 0);
            lastMouseY = getEvtRelY(ev, 0);
            break;
        }
        AwlClient.input(id, PTR_ENTER, 0, lastMouseX, lastMouseY, 0, 0, 0);
    }

    /* ---- Main pointer handling (the virtual mouse for physical-mouse
     *      events AND single-finger touchpad contact) ----
     * dx/dy = this event's endpoint - the last delivered endpoint (diff over
     * the sent event stream) — the telescoping sum ≡ total displacement,
     * independent of how Android batches/coalesces samples.
     * Enter pairing: enters on the first event (a touchpad tap has no
     * preceding hover-enter) and stays after lift — leave happens only on
     * hover-exit / classification switches / teardown.
     * Buttons = buttonState diff (savedBS XOR current) — the touch stream
     * and the generic stream are two input paths sharing one differ. */
    private int mouseSavedBS;
    private float lastMouseX = Float.NaN, lastMouseY = Float.NaN;

    private float limitRange(float a, float b, float val){
        if(val<a) return a;
        if(val>b) return b;
        return val;
    }

    /* Three-mode dispatch (see the capture block above): NONE = absolute +
     * abs-diff rel; LOCK = AXIS_RELATIVE_* only, position frozen at the
     * capture point; CONFINE = AXIS_RELATIVE_* accumulated into the clamped
     * virtual position, sent as absolute motion. lastMouseX/Y always hold
     * the position the client believes the pointer is at. */
    private void handleMouseEvent(MotionEvent ev) {
        float ex = getEvtRelX(ev, 0), ey = getEvtRelY(ev, 0);
        float dx, dy;
        boolean sendabs;
        float x, y;
        switch (captureMode) {
        case CAPTURE_LOCK:
            /* captured: only the relative axes are populated (sum over the
             * batch); no absolute motion — the client cursor stays frozen */
            dx = sumAxis(ev, MotionEvent.AXIS_RELATIVE_X);
            dy = sumAxis(ev, MotionEvent.AXIS_RELATIVE_Y);
            if (Float.isNaN(lastMouseX)) lastMouseX = lastW / 2f;
            if (Float.isNaN(lastMouseY)) lastMouseY = lastH / 2f;
            sendabs = false;
            x = lastMouseX; y = lastMouseY;
            break;
        case CAPTURE_CONFINE:
            dx = sumAxis(ev, MotionEvent.AXIS_RELATIVE_X);
            dy = sumAxis(ev, MotionEvent.AXIS_RELATIVE_Y);
            confinex = limitRange(confLoX(), confHiX(), confinex + dx);
            confiney = limitRange(confLoY(), confHiY(), confiney + dy);
            sendabs = true;
            x = lastMouseX = confinex;
            y = lastMouseY = confiney;
            break;
        default:   /* CAPTURE_NONE — uncaptured AXIS_RELATIVE_* is always 0 */
            dx = Float.isNaN(lastMouseX) ? 0f : ex - lastMouseX;
            dy = Float.isNaN(lastMouseY) ? 0f : ey - lastMouseY;
            sendabs = true;
            x = lastMouseX = ex;
            y = lastMouseY = ey;
            break;
        }
        if (!padInWin)
            padEnter(ev);   /* no prior hover (touchpad tap without hover) → make up the enter */
        if (dx != 0f || dy != 0f)
            AwlClient.input(id, PTR_REL, 0, dx, dy, 0, 0, 0);
        if (sendabs)
            AwlClient.input(id, PTR_MOTION, 0, x, y, 0, 0, 0);
        int bs = ev.getButtonState();
        int diff = mouseSavedBS ^ bs;
        if (diff != 0) {
            for (int[] b : PAD_BUTTON_MAP)
                if ((diff & b[0]) != 0)
                    AwlClient.input(id, PTR_BUTTON, b[1], x, y,
                            (bs & b[0]) != 0 ? 1 : 0, 0, 0);
            mouseSavedBS = bs;
        }
    }

    private static final int[][] PAD_BUTTON_MAP = {   /* {android bit, evdev code} */
        {MotionEvent.BUTTON_PRIMARY,   BTN_LEFT},
        {MotionEvent.BUTTON_SECONDARY, BTN_RIGHT},
        {MotionEvent.BUTTON_TERTIARY,  BTN_MIDDLE},
        {MotionEvent.BUTTON_BACK,      BTN_BACK},
        {MotionEvent.BUTTON_FORWARD,   BTN_FORWARD},
    };

    /** Sum of an axis over the batched historical samples + the current one.
     *  AXIS_GESTURE_SCROLL_*_DISTANCE is per-sample, not accumulated —
     *  reading only the current sample drops distance whenever Android
     *  coalesces MOVEs. */
    private static float sumAxis(MotionEvent ev, int axis) {
        float s = 0f;
        for (int i = 0; i < ev.getHistorySize(); i++)
            s += ev.getHistoricalAxisValue(axis, 0, i);
        return s + ev.getAxisValue(axis);
    }

    /** Two-finger scroll (classification 3): MOVE yields pixel distances;
     *  UP/CANCEL = gesture end → a zero-delta finger axis event (axis_stop =
     *  fling start on the client). The pointer stream is NOT torn down.
     *  Sign: AXIS_GESTURE_SCROLL_*_DISTANCE is a scroll-position delta
     *  (positive = page scrolls down/right) — identical to the wayland axis
     *  convention, forwarded verbatim. Position is NOT passed: the fake
     *  finger the gesture converter synthesizes drifts from the cursor.
     *  The classifier may tag cls=3 from the very first event → make up an
     *  enter before the axis (protocol requires axis to follow enter). */
    private void handleTouchpadScroll(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (padInWin)
                AwlClient.input(id, PTR_AXIS, 1, 0, 0, 0, 0, 0);   /* v=h=0 finger → axis_stop */
            return;
        }
        if (a != MotionEvent.ACTION_MOVE) return;
        if (!padInWin)
            padEnter(ev);
        float v = sumAxis(ev, MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE);
        float h = sumAxis(ev, MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE);
        if (v != 0 || h != 0)
            AwlClient.input(id, PTR_AXIS, 1, v, h, 0, 0, 0);
    }

    /** Touchscreen (touch id = code; coordinates = SurfaceView-local).
     * Pointer-class non-touchscreen (mouse/touchpad, one stream on many
     * devices): dispatch by gesture classification FIRST, virtual mouse as
     * the default — two-finger = scroll, multi-finger/pinch = touch
     * passthrough, else (single contact / physical mouse) =
     * handleMouseEvent. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        /*
         * The HUD buttons float above the SurfaceView, so they get first
         * refusal on every touch. Without this the forwarding below consumed
         * every event unconditionally and the buttons drew but never fired —
         * the tap reached the window yet produced no click and no client input.
         *
         * super.dispatchTouchEvent() only returns true when a child (a HUD
         * TextView) actually handled the event; an empty area returns false
         * and falls through to the client input path unchanged.
         */
        if (super.dispatchTouchEvent(ev))
            return true;
        if (id < 0) return false;   /* awaiting */
        return handleTouchEvent(ev);
    }

    /**
     * 触摸 → 客户端输入。真实触摸与沉浸式模式抓取到的触摸共用这一条路径，
     * 这样触摸板模式、指针灵敏度、手势对两者含义一致。
     */
    boolean handleTouchEvent(MotionEvent ev) {
        lastPointerX = ev.getX();
        lastPointerY = ev.getY();
        if (isMouse(ev)) {
            int cls = ev.getClassification();
            if (cls == CLS_TWO_FINGER_SWIPE) {
                handleTouchpadScroll(ev);
                return true;
            }
            if (cls != CLS_MULTI_FINGER_SWIPE && cls != CLS_PINCH) {
                handleMouseEvent(ev);   /* whole drag/tap: motion + rel diff + button diff */
                return true;
            }
            /* multi-finger swipe / pinch → touch passthrough. The first finger
             * already went through pointer emulation's enter/motion: send its
             * touch down (the client gets a complete touch stream) and then
             * close the pointer stream. */
            if (padInWin) {
                AwlClient.input(id, TOUCH_DOWN, ev.getPointerId(0),
                        getEvtRelX(ev, 0), getEvtRelY(ev, 0), 0, 0, 0);
                endPadStream();
            }
        }
        switch (ev.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            sendTouch(TOUCH_DOWN, ev, ev.getActionIndex());
            return true;
        case MotionEvent.ACTION_POINTER_DOWN:
            sendTouch(TOUCH_DOWN, ev, ev.getActionIndex());
            return true;
        case MotionEvent.ACTION_MOVE:
            for (int i = 0; i < ev.getPointerCount(); i++)
                sendTouch(TOUCH_MOTION, ev, i);
            return true;
        case MotionEvent.ACTION_POINTER_UP:
            sendTouch(TOUCH_UP, ev, ev.getActionIndex());
            return true;
        case MotionEvent.ACTION_UP:
            sendTouch(TOUCH_UP, ev, ev.getActionIndex());
            return true;
        case MotionEvent.ACTION_CANCEL:
            for (int i = 0; i < ev.getPointerCount(); i++)
                AwlClient.input(id, TOUCH_CANCEL, ev.getPointerId(i), 0, 0, 0, 0, 0);
            return true;
        default:
            return super.dispatchTouchEvent(ev);
        }
    }

    private void sendTouch(int type, MotionEvent ev, int idx) {
        AwlClient.input(id, type, ev.getPointerId(idx),
                       getEvtRelX(ev, idx), getEvtRelY(ev, idx), 0, 0, 0);
    }

    /** Mouse/touchpad generic events: hover enter/move/exit + all buttons
     *  + wheel/finger scroll.
     *  Wheel (physical mouse): Android AXIS_VSCROLL is normalized -1.0 (down)
     *  .. 1.0 (up) and AXIS_HSCROLL -1.0 (left) .. 1.0 (right); wayland axis
     *  values live in the motion coordinate space (positive = down / right)
     *  → the vertical notch is negated, the horizontal one forwarded
     *  verbatim (code=0, notches; the daemon does ×10 + axis_discrete).
     *  An ACTION_SCROLL carrying AXIS_GESTURE_SCROLL_*_DISTANCE (touchpad
     *  scroll semantics) is forwarded as raw pixel distances (code=1).
     *  Under pointer capture the system stops hover synthesis; the raw
     *  touchpad stream arrives as ACTION_MOVE + SOURCE_TOUCHPAD with the
     *  relative axes — routed by capture state (NONE keeps ignoring it, the
     *  two streams never double-process). */
    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (id < 0) return super.onGenericMotionEvent(ev);   /* awaiting */
        boolean capturedPad = captureMode != CAPTURE_NONE
                && ev.isFromSource(InputDevice.SOURCE_TOUCHPAD);
        if (!isMouse(ev) && !capturedPad)
            return super.onGenericMotionEvent(ev);
        switch (ev.getActionMasked()) {
        case MotionEvent.ACTION_MOVE:
            /* captured raw touchpad stream (single finger = virtual mouse; two = scroll) */
            if (ev.getClassification() == CLS_TWO_FINGER_SWIPE) {
                handleTouchpadScroll(ev);
                return true;
            }
            if (ev.getClassification() == CLS_MULTI_FINGER_SWIPE
                    || ev.getClassification() == CLS_PINCH)
                return true;   /* captured: no touch-passthrough stream, swallow */
            handleMouseEvent(ev);
            return true;
        case MotionEvent.ACTION_HOVER_ENTER:
            if (padInWin) {   /* pointer already inside (made up by a scroll/tap) → protocol forbids a 2nd enter, treat as motion */
                handleMouseEvent(ev);
                return true;
            }
            padEnter(ev);
            return true;
        case MotionEvent.ACTION_HOVER_MOVE:
            handleMouseEvent(ev);   /* motion + rel diff + button diff (enter already paired by HOVER_ENTER) */
            return true;
        case MotionEvent.ACTION_HOVER_EXIT:
            if (captureMode != CAPTURE_NONE)
                return true;   /* captured: the pointer cannot leave the window (this exit is capture-start churn) */
            endPadStream();   /* leave + release leftovers + reset the rel anchor */
            return true;
        case MotionEvent.ACTION_SCROLL: {
            if (!padInWin)   /* same as the scroll-classification path: enter before the axis */
                padEnter(ev);
            float fv = sumAxis(ev, MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE);
            float fh = sumAxis(ev, MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE);
            if (fv != 0 || fh != 0) {   /* touchpad pixel distances: finger source, no position */
                AwlClient.input(id, PTR_AXIS, 1, fv, fh, 0, 0, 0);
                return true;
            }
            float v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (v != 0 || h != 0) {
                /* wheel notches; v1/v2 = pointer position for the synthesized
                 * surface-coord motion in the axis frame: LOCK = 0,0 (no
                 * motion — the client cursor is frozen), CONFINE = the
                 * clamped virtual position, NONE = raw */
                float px = 0f, py = 0f;
                if (captureMode == CAPTURE_CONFINE) { px = confinex; py = confiney; }
                else if (captureMode == CAPTURE_NONE) { px = getEvtRelX(ev, 0); py = getEvtRelY(ev, 0); }
                AwlClient.input(id, PTR_AXIS, 0, -v, h, px, py, 0);
            }
            return true;
        }
        case MotionEvent.ACTION_BUTTON_PRESS:
        case MotionEvent.ACTION_BUTTON_RELEASE:
            handleMouseEvent(ev);   /* ensure-enter + savedBS differ (dedups with the touch stream) */
            return true;
        default:
            return super.onGenericMotionEvent(ev);
        }
    }

    /** Keyboard: evdev scan code forwarded verbatim (matches the embedded
     *  keymap), meta as-is (the daemon decodes the Android meta bits).
     *  Volume keys stay with the system. Android's synthetic key repeats
     *  are swallowed (repeat is the client's own job under wayland;
     *  forwarding them would re-press a held key).
     *  Soft-keyboard/IME synthesized keys have scanCode=0 → map the keycode
     *  to an evdev code.
     *
     *  Backspace while the IME is open is delivered as an instant
     *  press+release tap: LatinIME acts on the DEL ACTION_UP and consumes
     *  it, so a forwarded press would never see its release — the client
     *  then holds the key down and its own key repeat drains the field
     *  (Android apps delete on ACTION_DOWN and never depend on the UP;
     *  wayland receivers key off the release instead). Repeats each become
     *  one more tap, so held-key auto-repeat still works. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        /* 沉浸式切换键优先：绑定的那颗键在会话内不得再传到桌面/安卓。 */
        if (immersive != null && immersive.handleKey(ev))
            return true;
        if (id < 0) return super.dispatchKeyEvent(ev);   /* awaiting */
        int kc = ev.getKeyCode();
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE)
            return super.dispatchKeyEvent(ev);
        if (kc == KeyEvent.KEYCODE_DEL && imeWanted) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                int bs = ev.getScanCode() != 0 ? ev.getScanCode() : fallbackSc(kc);
                if (bs > 0) {
                    AwlClient.input(id, KEY, bs, 0, 0, 1, 0, ev.getMetaState());
                    AwlClient.input(id, KEY, bs, 0, 0, 0, 0, 0);
                }
            }
            return true;   /* UP swallowed — already released with the tap */
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() > 0)
            return true;   /* synthetic repeat — swallow (see above) */
        int sc = ev.getScanCode();
        if (sc == 0) sc = fallbackSc(kc);
        if (sc > 0) {
            AwlClient.input(id, KEY, sc, 0, 0,
                           ev.getAction() == KeyEvent.ACTION_DOWN ? 1 : 0, 0,
                           ev.getMetaState());
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /** IME-synthesized key (scanCode=0) → evdev code (matches the embedded keymap) */
    private static int fallbackSc(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_ENTER:
        case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0x1c;
        case KeyEvent.KEYCODE_DEL:          return 0x0e;   /* Android DEL = backspace */
        case KeyEvent.KEYCODE_FORWARD_DEL:  return 0x6f;
        case KeyEvent.KEYCODE_TAB:          return 0x0f;
        case KeyEvent.KEYCODE_ESCAPE:       return 0x01;
        case KeyEvent.KEYCODE_SPACE:        return 0x39;
        case KeyEvent.KEYCODE_DPAD_UP:      return 0x67;
        case KeyEvent.KEYCODE_DPAD_DOWN:    return 0x6c;
        case KeyEvent.KEYCODE_DPAD_LEFT:    return 0x69;
        case KeyEvent.KEYCODE_DPAD_RIGHT:   return 0x6a;
        case KeyEvent.KEYCODE_PAGE_UP:      return 0x68;
        case KeyEvent.KEYCODE_PAGE_DOWN:    return 0x6d;
        case KeyEvent.KEYCODE_MOVE_HOME:    return 0x66;
        case KeyEvent.KEYCODE_MOVE_END:     return 0x6b;
        default:                            return 0;
        }
    }

    @Override
    protected void onDestroy() {
        fireHost((cbs, win, act) -> cbs.onHostDestroy(win, act));
        if (isFinishing() && id >= 0) Awl.hostGone(id);   /* keep the entry across re-creation */
        LIVE.remove(id);
        if (deathLinked) {
            AwlClient.unmonitorDeath(daemonDeath);
            deathLinked = false;
        }
        /* No detach report: swiping away / killing the background always
         * minimizes and keeps the window alive (onPause already sent PAUSE;
         * process death is backstopped by the daemon-side binder death).
         * Closing = Awl.closeWindow (daemon T_CLOSE) → client closes the
         * window → C_CLOSE → finish */
        attached = false;
        OplusRefreshRateLease.release(this);   /* 进程级引用计数，别把锁留给下一个窗口 */
        super.onDestroy();
    }

    /* ================= ImmersiveMode.Host =================
     *
     * 沉浸式模式（anland5 移植）：root helper 独占抓取触摸/键盘/指针，把事件
     * 回放回桌面。每个回调都接到 libawl 的输入路径上，语义与 5.x 一致 ——
     * 抓取到的触摸走真实的触摸路径，键盘/鼠标走 sendInput。 */

    /** 最近一次指针位置，供按钮/滚动事件携带坐标。 */
    private float lastPointerX, lastPointerY;

    @Override public Context context() { return this; }

    @Override public int outputWidth() { return Math.max(1, sv.getWidth()); }
    @Override public int outputHeight() { return Math.max(1, sv.getHeight()); }

    /** 沉浸式映射出来的是窗口坐标，原点即 SurfaceView 在窗口里的位置。 */
    @Override public float outputOriginX() { return svOffset[0]; }
    @Override public float outputOriginY() { return svOffset[1]; }

    @Override public float displayRefreshHz() {
        android.view.Display d = getDisplay();
        return d != null ? d.getRefreshRate() : 0f;
    }

    @Override public int displayRotation() {
        android.view.Display d = getDisplay();
        return d != null ? d.getRotation() : android.view.Surface.ROTATION_0;
    }

    /** 抓取到的触摸屏帧（已是视图像素）：与真实触摸共用同一条转发路径。 */
    @Override public void onGrabbedTouch(MotionEvent ev) {
        if (id >= 0) handleTouchEvent(ev);
    }

    @Override public void onTouchscreenGrabChanged(boolean grabbed) {
        /* Android 的 contact id 与 helper 的 slot 是两条流：换主人前结束旧的。 */
        endPadStream();
    }

    /** 被抓取的物理触摸板：识别出的运动即光标，点击是唯一的按键来源。 */
    @Override public Touchpad newGrabbedPad() {
        Touchpad pad = new Touchpad(this, new ImmersiveTouchpadOutput(), true);
        applyImmersiveTouchpadPrefs(pad);
        pad.setOutputSize(outputWidth(), outputHeight());
        return pad;
    }

    /** 被抓取的物理触摸板套用用户的触摸板设置（与 5.x 同一组键，存在 anland_settings）。 */
    private void applyImmersiveTouchpadPrefs(Touchpad pad) {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        pad.setAccelStrength(Math.max(0.5f, Math.min(10.0f, p.getFloat("mouse_speed", 1.0f))));
        pad.setScrollSpeed(p.getFloat("scroll_speed", Touchpad.DEFAULT_SCROLL_SPEED));
        pad.setScrollReversed(p.getBoolean("scroll_reverse", false));
        pad.setGestureThresholds(
                p.getFloat("touchpad_scroll_threshold", Touchpad.DEFAULT_SCROLL_THRESHOLD_FACTOR),
                p.getFloat("touchpad_move_threshold", Touchpad.DEFAULT_MOVE_THRESHOLD_FACTOR));
        pad.setGestureScale(p.getFloat("touchpad_gesture_scale", Touchpad.DEFAULT_GESTURE_SCALE));
        pad.setMultiFingerGesturesDisabled(p.getBoolean("disable_multi_finger_gestures", false));
    }

    @Override public void movePointerRelative(float dx, float dy) {
        if (id >= 0) Awl.sendInput(id, Awl.PTR_REL, 0, dx, dy, 0f, 0f, 0);
    }

    @Override public void sendKey(int action, int evdev) {
        if (id >= 0) Awl.sendKey(id, evdev, action == 0);
    }

    @Override public void sendMouseButton(int button, boolean pressed) {
        if (id >= 0)
            Awl.sendInput(id, Awl.PTR_BUTTON, button, lastPointerX, lastPointerY,
                    pressed ? 1f : 0f, 0f, 0);
    }

    @Override public void sendMouseScroll(int axis, float value) {
        if (id < 0) return;
        if (axis == 0) Awl.sendInput(id, Awl.PTR_AXIS, 1, value, 0f, 0f, 0f, 0);
        else          Awl.sendInput(id, Awl.PTR_AXIS, 1, 0f, value, 0f, 0f, 0);
    }

    @Override public void onImmersiveChanged(boolean active) {
        if (immersive != null) immersive.onSessionActiveChanged(active);
        if (!active) {
            setPointerCaptureMode(CAPTURE_NONE, 0, 0, 0, 0);
            /* 刷新率锁是进程级引用计数：本窗口退出会话就还自己那份。 */
            OplusRefreshRateLease.release(this);
            return;
        }
        /* 指针在整个会话期间被 evdev 层抓走，安卓的 capture 失效；用户
         * 「捕获外部指针」的设置保持不变，会话结束后自动恢复。 */
        if (hasWindowFocus()
                && getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(OplusRefreshRateLease.KEY_ENABLED, false))
            OplusRefreshRateLease.acquire(this);
    }

    private final class ImmersiveTouchpadOutput implements Touchpad.Output {
        @Override public void onMotion(float dx, float dy) { movePointerRelative(dx, dy); }
        @Override public void onScroll(int axis, float value) { sendMouseScroll(axis, value); }
        @Override public void onButton(int button, boolean pressed) { sendMouseButton(button, pressed); }

        @Override public void onTouch(int action, int pointerId, float x, float y) {
            if (id < 0) return;
            int type;
            switch (action) {
                case Touchpad.TOUCH_DOWN:  type = Awl.TOUCH_DOWN;   break;
                case Touchpad.TOUCH_MOVE:  type = Awl.TOUCH_MOTION; break;
                case Touchpad.TOUCH_UP:    type = Awl.TOUCH_UP;     break;
                default:                   type = Awl.TOUCH_CANCEL; break;
            }
            Awl.sendInput(id, type, pointerId, x, y, 0f, 0f, 0);
        }

        @Override public void onTouchFrame() { /* 帧由 SYN_REPORT 天然分隔，无需额外标记 */ }

        @Override public float cursorX() { return lastPointerX; }
        @Override public float cursorY() { return lastPointerY; }
    }
}
