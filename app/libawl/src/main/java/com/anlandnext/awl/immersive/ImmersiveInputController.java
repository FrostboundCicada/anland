package com.anlandnext.awl.immersive;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.widget.Toast;

import com.anlandnext.awl.R;

import java.util.Set;

/**
 * 沉浸式输入的拥有者：当前来源、是否在跑，以及「同一时刻只有一个会话」。
 *
 * <p>5.x 的控制器还负责把 Gold 键盘切到 Anland 配置文件、缓存 Gold 占用的
 * 节点、并据此算出排除集。6.x 不引入 Gold 键盘重映射模块，这些整体去掉，
 * 只保留「直接抓物理 evdev 节点」这一条路径；节点选择仍走
 * {@link ImmersiveDirectSelection}（occupied 传 null）。
 */
public final class ImmersiveInputController {
    private static final String PREFS_NAME = "anland_settings";
    static final String KEY_AUTO_ENTER = "immersive_auto_enter";

    private final Context ctx;
    private final ImmersiveMode direct;
    private ImmersiveInputSource runningSource = ImmersiveInputSource.DIRECT_EVENT_NODES;

    public ImmersiveInputController(ImmersiveMode.Host host) {
        this.ctx = host.context();
        this.direct = new ImmersiveMode(host);
    }

    /** 会话起止回调；6.x 没有外部键盘配置要同步，留空。 */
    public void onSessionActiveChanged(boolean active) { }

    /** 是否需要在 resume 时自动进入（仅「直接」来源）。 */
    static boolean shouldAutoEnter(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_AUTO_ENTER, false);
    }

    /**
     * resume 时自动进入（对齐 5.x）。
     *
     * <p>只在设置了「自动进入」且来源可用时静默启动：这每次 resume 都会跑，
     * 弹 toast 只会变成噪音。
     */
    public void enterAutomatically() {
        SharedPreferences prefs = prefs();
        if (!prefs.getBoolean(ImmersiveMode.KEY_ENABLED, false))
            return;
        if (!shouldAutoEnter(prefs))
            return;
        ImmersiveInputSource current = ImmersiveInputSource.read(prefs);
        if (current != ImmersiveInputSource.DIRECT_EVENT_NODES)
            return;
        if (!current.isAvailable(ctx))
            return;
        if (isActive())
            return;
        runningSource = current;
        direct.startWith(null, null, current);
    }

    ImmersiveInputSource source() {
        return ImmersiveInputSource.read(prefs());
    }

    boolean isActive() {
        return direct.isActive();
    }

    // ---- 按键路由 -------------------------------------------------------

    /**
     * 消费绑定的沉浸式按键。
     *
     * @return true 表示这就是那个切换键，事件不得继续下传
     */
    public boolean handleKey(KeyEvent event) {
        ImmersiveInputSource current = source();
        if (current != runningSource) {
            // 来源在会话中被改掉：先结束正在跑的那个。
            stop();
            runningSource = current;
        }

        if (!direct.isBoundToggle(event))
            return false;
        if (direct.consumeSuppressedToggle(event))
            return true;

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (isActive())
                stopSession();
            else
                startSession(current);
        }
        return true;
    }

    private void startSession(ImmersiveInputSource source) {
        if (!source.isAvailable(ctx)) {
            toast(R.string.immersive_source_unavailable);
            return;
        }
        boolean started = startDirectNow(source);
        if (!started)
            return;
        toast(ctx.getString(R.string.immersive_entering_mode, sourceLabel(source)));
    }

    /** 来源的名字，与设置里列出的措辞一致。 */
    private String sourceLabel(ImmersiveInputSource source) {
        String[] names = ctx.getResources().getStringArray(R.array.immersive_source_options);
        int index = source.menuIndex();
        return index < names.length ? names[index] : source.preferenceValue;
    }

    private void stopSession() {
        // 让切换键自己的 release 也被吞掉，别让结束会话的那次按键再传到桌面。
        direct.requestStop();
    }

    /**
     * 用当前（缓存的）选择立即启动。
     *
     * <p>这条路不能等：切换是一个按键，犹豫的会话比「不知道排除谁」更糟 ——
     * 不知道的代价只是某个抓取失败然后跳过。
     */
    private boolean startDirectNow(ImmersiveInputSource source) {
        SharedPreferences prefs = prefs();
        boolean automatic = ImmersiveInputSource.isAutomatic(prefs);
        Set<String> saved = ImmersiveInputSource.savedNodes(prefs);

        ImmersiveDirectSelection.Result selection = ImmersiveDirectSelection.resolve(
                automatic, saved, null, InputGrab.MAX_SELECTED_NODES);

        if (selection.invalid) {
            toast(R.string.immersive_selection_invalid);
            return false;
        }
        if (selection.allFiltered) {
            toast(R.string.immersive_selection_filtered);
            return false;
        }
        return direct.startWith(selection.selectedNodes, selection.excludedNodes, source);
    }

    /**
     * 结束当前会话。焦点丢失、pause、destroy、surface 丢失、关屏、来源切换
     * 都调它：会话绝不能比前台活得久。
     */
    public void stop() {
        direct.stop();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void toast(int stringRes) {
        Toast.makeText(ctx, stringRes, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
    }
}
