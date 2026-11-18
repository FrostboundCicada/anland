package com.anlandnext.awl.immersive;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 沉浸式输入来源。
 *
 * 5.x 有三个来源，其中两个依赖 Gold 键盘重映射模块（一个独立的 Magisk 模块）。
 * 6.x 不引入 Gold，只保留「直接抓物理 evdev 节点」这一条路径 —— 它由 root
 * helper 独占抓取触摸/键盘/指针，再把事件流回应用。
 */
enum ImmersiveInputSource {
    DIRECT_EVENT_NODES("direct_event_nodes");

    /** 是否抓取物理节点。 */
    boolean takesDevices() {
        return this == DIRECT_EVENT_NODES;
    }

    /** 是否只读某个外部模块的输出节点（6.x 没有这种来源）。 */
    boolean listensToGoldKeyboard() {
        return false;
    }

    /** 该来源当前是否可用。直接来源永远可用（root helper 缺失时由会话报错）。 */
    boolean isAvailable(Context context) {
        return true;
    }

    String helperArgument() {
        return "source=physical";
    }

    /**
     * 是否允许「未绑定按键」也能进入。
     *
     * <p>直接来源必须绑键：helper 是靠这颗键结束会话的，没有它就没有按键级的
     * 退出手段（只能靠电源键、心跳和杀进程）。5.x 也是这么要求的。
     */
    boolean allowsUnboundToggle() {
        return false;
    }

    static final String KEY_PREFERENCE = "immersive_input_source";

    /** 与 {@link #ORDER} 和字符串数组平行的顺序。 */
    static final ImmersiveInputSource[] ORDER = { DIRECT_EVENT_NODES };

    int menuIndex() {
        for (int i = 0; i < ORDER.length; i++)
            if (ORDER[i] == this) return i;
        return 0;
    }

    final String preferenceValue;

    ImmersiveInputSource(String preferenceValue) {
        this.preferenceValue = preferenceValue;
    }

    /** 无法识别的值（缺失/为空/未来版本写入）一律退回直接来源。 */
    static ImmersiveInputSource fromPreference(String value) {
        for (ImmersiveInputSource source : values())
            if (source.preferenceValue.equals(value))
                return source;
        return DIRECT_EVENT_NODES;
    }

    static ImmersiveInputSource read(SharedPreferences preferences) {
        return fromPreference(preferences == null ? null
                : preferences.getString(KEY_PREFERENCE, null));
    }

    // ---- 直接来源的节点选择 -------------------------------------

    /** 直接来源是否自动选节点（而不是用保存的列表）。 */
    static final String KEY_AUTO = "immersive_input_auto";

    /** 保存的节点列表（逗号分隔，旧语法）。 */
    static final String KEY_NODES = "immersive_input_nodes";

    /** 缺失即自动：老安装保持既有行为。 */
    static boolean isAutomatic(SharedPreferences preferences) {
        return preferences == null || preferences.getBoolean(KEY_AUTO, true);
    }

    /** 原样返回保存的列表，不做校验 —— 由选择器决定能否兑现。 */
    static Set<String> savedNodes(SharedPreferences preferences) {
        Set<String> nodes = new LinkedHashSet<>();
        if (preferences == null)
            return nodes;
        String raw = preferences.getString(KEY_NODES, "");
        if (raw == null)
            return nodes;
        for (String part : raw.split(",")) {
            String node = part.trim();
            if (!node.isEmpty())
                nodes.add(node);
        }
        return nodes;
    }
}
