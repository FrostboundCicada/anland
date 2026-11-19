/* awl_im.c — zwp_input_method_v2（容器内输入法，如 fcitx5）
 *
 * 为什么需要：终端类应用不实现 zwp_text_input_v3，只吃 wl_keyboard，而中文
 * 没有 evdev 码 —— 按键降级表达不了。让容器内的 fcitx5 接管才能打中文：
 *
 *   应用 ──text_input_v3──▶ daemon ◀──input_method_v2── fcitx5
 *                            │
 *                       按键改投（grab_keyboard）
 *
 * 设计要点（失败安全）：
 *  - input_method 对象只在 fcitx5 真正 activate 时才影响输入；fcitx5 没运行
 *    → 对象存在但永不 activate → 行为零变化。
 *  - 有 text_input 的应用走原来的直通路径（awl_ime_text 的 delivered），
 *    不依赖本模块。
 *  - grab_keyboard 期间按键改投给输入法；没有 grab 时一切照旧。
 *
 * 锁：g_srv.rwl(rd) 保护对象表（与 awl_ime.c 一致）；发送在目标的 ev_lock 下。
 */
#define _GNU_SOURCE   /* bionic: memfd_create */
#include "awl_internal.h"

#include <string.h>
#include <unistd.h>
#include <sys/mman.h>

#include "keymap_evdev.h"
#include "input-method-unstable-v2-server-protocol.h"

/* ---- 文本接收方：由 awl_ime.c 实现（当前聚焦的 text_input） ---- */
extern int awl_ime_im_commit(const char* text);            /* 1 = 已送达 */
extern int awl_ime_im_preedit(const char* text, int32_t cb, int32_t ce);
extern int awl_ime_im_delete(uint32_t before, uint32_t after);
/* 当前 text_input 的 surrounding_text / content_type（激活时发给输入法） */
extern int awl_ime_im_state_unlocked(const char** surrounding, uint32_t* hint, uint32_t* purpose);
extern void awl_ime_im_activate(void);   /* text_input 获得焦点 → 通知本模块 */
extern void awl_ime_im_deactivate(void);

struct awl_im_obj {
    struct wl_list link;
    struct wl_resource* res;      /* zwp_input_method_v2 */
    struct wl_resource* grab;     /* zwp_input_method_keyboard_grab_v2，可空 */
    struct wl_resource* popup;    /* zwp_input_popup_surface_v2，可空 */
    int active;
    uint32_t serial;              /* 已发 done 数（commit 的 serial 要对上） */

    /* double-buffered 待提交状态 */
    char* pend_commit;            /* commit_string */
    char* pend_preedit;           /* set_preedit_string */
    int32_t pend_cb, pend_ce;
    uint32_t pend_before, pend_after;
    int has_commit, has_preedit, has_delete;
};

static struct wl_list g_ims;
static struct wl_resource* g_grab;    /* 当前键盘抓取（全局单 seat） */
static struct wl_display* g_dpy;      /* keymap memfd 用 */

/* ---------------- 工具 ---------------- */

static void im_send_done(struct awl_im_obj* o) {
    if (!o || !o->res) return;
    zwp_input_method_v2_send_done(o->res);   /* 该修订版 done 无 serial */
    o->serial++;
    wl_client_flush(wl_resource_get_client(o->res));
}

static void im_grab_keymap(struct awl_im_obj* o, uint32_t format) {
    size_t len = strlen(k_keymap_evdev);
    int fd = memfd_create("awl-im-keymap", MFD_CLOEXEC);
    if (fd < 0) return;
    if (write(fd, k_keymap_evdev, len) != (ssize_t)len) { close(fd); return; }
    lseek(fd, 0, SEEK_SET);
    zwp_input_method_keyboard_grab_v2_send_keymap(o->grab, format, fd, (uint32_t)len);
    close(fd);
    zwp_input_method_keyboard_grab_v2_send_repeat_info(o->grab, 25, 600);
}

/* ---------------- 事件（daemon → 输入法） ---------------- */

static void im_activate(struct awl_im_obj* o) {
    if (!o->res) return;
    o->active = 1;
    zwp_input_method_v2_send_activate(o->res);
    /* 激活后必须跟上 text_input 的当前状态（若它支持） */
    const char* surrounding = NULL; uint32_t hint = 0, purpose = 0;
    if (awl_ime_im_state_unlocked(&surrounding, &hint, &purpose) && surrounding)
        zwp_input_method_v2_send_surrounding_text(o->res, surrounding, 0, 0);
    zwp_input_method_v2_send_content_type(o->res, hint, purpose);
    im_send_done(o);
}

static void im_deactivate(struct awl_im_obj* o) {
    if (!o->res) return;
    o->active = 0;
    zwp_input_method_v2_send_deactivate(o->res);
    im_send_done(o);
}

/* text_input 把状态变化推给输入法（由 awl_ime.c 在状态变化时调用） */
void awl_im_notify_state(void) {
    pthread_rwlock_rdlock(&g_srv.rwl);
    struct awl_im_obj* o;
    wl_list_for_each(o, &g_ims, link) {
        if (!o->active || !o->res) continue;
        const char* surrounding = NULL; uint32_t hint = 0, purpose = 0;
        if (awl_ime_im_state_unlocked(&surrounding, &hint, &purpose) && surrounding)
            zwp_input_method_v2_send_surrounding_text(o->res, surrounding, 0, 0);
        zwp_input_method_v2_send_content_type(o->res, hint, purpose);
        zwp_input_method_v2_send_text_change_cause(o->res, 0 /* ZWP_INPUT_METHOD_V2_CHANGE_CAUSE_INPUT_METHOD */);
        im_send_done(o);
    }
    pthread_rwlock_unlock(&g_srv.rwl);
}

static void popup_destroy(struct wl_client* c, struct wl_resource* res) {
    (void)c;
    struct awl_im_obj* o = wl_resource_get_user_data(res);
    if (o && o->popup == res) o->popup = NULL;
    wl_resource_set_destructor(res, NULL);
}
static const struct zwp_input_popup_surface_v2_interface popup_iface = {
    .destroy = popup_destroy,
};
static void grab_release(struct wl_client* c, struct wl_resource* res) {
    (void)c;
    struct awl_im_obj* o = wl_resource_get_user_data(res);
    if (o && o->grab == res) { o->grab = NULL; g_grab = NULL; }
    wl_resource_set_destructor(res, NULL);
    LOGI("input-method win: keyboard released (按键回到应用)");
}
static const struct zwp_input_method_keyboard_grab_v2_interface grab_iface = {
    .release = grab_release,
};

/* ---------------- 请求（输入法 → daemon） ---------------- */

static void im_commit_string(struct wl_client* c, struct wl_resource* res, const char* text) {
    (void)c;
    struct awl_im_obj* o = wl_resource_get_user_data(res);
    if (!o) return;
    free(o->pend_commit);
    o->pend_commit = text ? strdup(text) : NULL;
    o->has_commit = 1;
}

static void im_set_preedit(struct wl_client* c, struct wl_resource* res,
                           const char* text, int32_t cursor_begin, int32_t cursor_end) {
    (void)c;
    struct awl_im_obj* o = wl_resource_get_user_data(res);
    if (!o) return;
    free(o->pend_preedit);
    o->pend_preedit = text ? strdup(text) : NULL;
    o->pend_cb = cursor_begin;
    o->pend_ce = cursor_end;
    o->has_preedit = 1;
}

static void im_delete_surrounding(struct wl_client* c, struct wl_resource* res,
                                  uint32_t before, uint32_t after) {
    (void)c;
    struct awl_im_obj* o = wl_resource_get_user_data(res);
    if (!o) return;
    o->pend_before = before;
    o->pend_after = after;
    o->has_delete = 1;
}

/* commit(serial)：按协议顺序应用 —— 删周围 → 插 commit → 插 preedit */
static void im_commit(struct wl_client* c, struct wl_resource* res, uint32_t serial) {
    (void)c; (void)serial;
    struct awl_im_obj* o = wl_resource_get_user_data(res);
    if (!o) return;

    /* 注意：awl_ime_im_* 各自取 rwl(rd)，这里不能再包一层 ——
     * 递归读锁在有写者等待时会死锁。 */
    if (o->has_delete) {
        awl_ime_im_delete(o->pend_before, o->pend_after);
        o->has_delete = 0;
        o->pend_before = o->pend_after = 0;
    }
    if (o->has_commit) {
        int sent = 0;
        if (o->pend_commit) sent = awl_ime_im_commit(o->pend_commit);
        if (!sent && o->pend_commit) {
            /* 没有可用 text_input（终端类应用）→ 剪贴板 + 粘贴键。非 ASCII 才需要，
             * ASCII 的按键降级由 awl_ime_text 那边处理。 */
            int non_ascii = 0;
            for (const unsigned char* q = (const unsigned char*)o->pend_commit; *q; q++)
                if (*q >= 0x80) { non_ascii = 1; break; }
            if (non_ascii) {
                LOGI("im_commit: 无 text_input，非 ASCII → 剪贴板+Ctrl+Shift+V");
                awl_datadev_android_clip(o->pend_commit);
                awl_input_send_paste(awl_ime_focus_win());
            }
        }
        free(o->pend_commit); o->pend_commit = NULL;
        o->has_commit = 0;
    }
    if (o->has_preedit) {
        awl_ime_im_preedit(o->pend_preedit ? o->pend_preedit : "", o->pend_cb, o->pend_ce);
        free(o->pend_preedit); o->pend_preedit = NULL;
        o->has_preedit = 0;
    }

    im_send_done(o);
}

static void im_get_popup(struct wl_client* c, struct wl_resource* res,
                         uint32_t id, struct wl_resource* surface) {
    struct awl_im_obj* o = wl_resource_get_user_data(res);
    if (!o) { wl_resource_post_no_memory(res); return; }
    struct wl_resource* p = wl_resource_create(
            c, &zwp_input_popup_surface_v2_interface, 1, id);
    if (!p) { wl_resource_post_no_memory(res); return; }
    wl_resource_set_implementation(p, &popup_iface, o, NULL);
    if (o->popup) wl_resource_destroy(o->popup);
    o->popup = p;
    /* 候选框位置：第一版不合成，只回报 text_input_rectangle（0,0,0,0） */
    zwp_input_popup_surface_v2_send_text_input_rectangle(p, 0, 0, 0, 0);
}

static void im_grab_keyboard(struct wl_client* c, struct wl_resource* res, uint32_t id) {
    struct awl_im_obj* o = wl_resource_get_user_data(res);
    if (!o) { wl_resource_post_no_memory(res); return; }
    struct wl_resource* g = wl_resource_create(
            c, &zwp_input_method_keyboard_grab_v2_interface, 1, id);
    if (!g) { wl_resource_post_no_memory(res); return; }
    wl_resource_set_implementation(g, &grab_iface, o, NULL);
    if (o->grab) wl_resource_destroy(o->grab);
    o->grab = g;
    g_grab = g;
    im_grab_keymap(o, 1 /* WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1 */);
    LOGI("input-method win: keyboard grabbed (按键改投给输入法)");
}

static void im_destroy(struct wl_client* c, struct wl_resource* res) {
    (void)c;
    struct awl_im_obj* o = wl_resource_get_user_data(res);
    if (!o) return;
    if (g_grab && wl_resource_get_user_data(g_grab) == o) g_grab = NULL;
    wl_list_remove(&o->link);
    free(o->pend_commit); free(o->pend_preedit);
    free(o);
    wl_resource_set_destructor(res, NULL);
}

static const struct zwp_input_method_v2_interface im_iface = {
    .commit_string = im_commit_string,
    .set_preedit_string = im_set_preedit,
    .delete_surrounding_text = im_delete_surrounding,
    .commit = im_commit,
    .get_input_popup_surface = im_get_popup,
    .grab_keyboard = im_grab_keyboard,
    .destroy = im_destroy,
};

/* ---------------- popup / grab ---------------- */



/* ---------------- manager ---------------- */

static void mgr_get_input_method(struct wl_client* c, struct wl_resource* res,
                                 struct wl_resource* seat, uint32_t id) {
    (void)seat;
    struct wl_resource* r = wl_resource_create(
            c, &zwp_input_method_v2_interface, 1, id);
    if (!r) { wl_resource_post_no_memory(res); return; }
    struct awl_im_obj* o = calloc(1, sizeof(*o));
    if (!o) { wl_resource_destroy(r); wl_resource_post_no_memory(res); return; }
    o->res = r;
    pthread_rwlock_wrlock(&g_srv.rwl);
    wl_list_insert(g_ims.prev, &o->link);
    pthread_rwlock_unlock(&g_srv.rwl);
    wl_resource_set_implementation(r, &im_iface, o, NULL);
    LOGI("input-method: client bound");
}

static void mgr_destroy(struct wl_client* c, struct wl_resource* res) {
    (void)c; (void)res;
}
static const struct zwp_input_method_manager_v2_interface mgr_iface = {
    .get_input_method = mgr_get_input_method,
    .destroy = mgr_destroy,
};

static void mgr_bind(struct wl_client* client, void* data, uint32_t version, uint32_t id) {
    (void)data;
    struct wl_resource* res = wl_resource_create(
            client, &zwp_input_method_manager_v2_interface, version, id);
    if (!res) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(res, &mgr_iface, NULL, NULL);
}

/* text_input 获得焦点 → 让所有 input_method 进入 active（发 activate + 状态） */
void awl_im_activate(void) {
    pthread_rwlock_rdlock(&g_srv.rwl);
    struct awl_im_obj* o;
    wl_list_for_each(o, &g_ims, link)
        if (o->res) im_activate(o);
    pthread_rwlock_unlock(&g_srv.rwl);
}

/* text_input 失焦/消失 → deactivate */
void awl_im_deactivate(void) {
    pthread_rwlock_rdlock(&g_srv.rwl);
    struct awl_im_obj* o;
    wl_list_for_each(o, &g_ims, link)
        if (o->res) im_deactivate(o);
    pthread_rwlock_unlock(&g_srv.rwl);
}

void awl_im_setup(void) {
    wl_list_init(&g_ims);
    g_grab = NULL;
    g_dpy = g_srv.display;
    if (!wl_global_create(g_srv.display, &zwp_input_method_manager_v2_interface,
                          1, NULL, mgr_bind))
        LOGE("zwp_input_method_manager_v2 global create failed");
}

/* ---------------- 按键改投（由 awl_input.c 调用） ---------------- */

/* 按键是否该改投给输入法（grab 期间）。
 *
 * 检查 + 发送都在 rwl(rd) 下完成：grab 资源由客户端请求创建/销毁（dispatch
 * 线程），而按键来自 binder 线程 —— 先查后发之间 grab 可能已被 release，
 * 那就成了 use-after-free。tr_key 的调用方都不持 rwl，取读锁是安全的。
 * 返回 1 = 已改投给输入法（调用方不应再发给应用）。 */
int awl_im_forward_key(uint32_t code, uint32_t state, uint32_t meta) {
    pthread_rwlock_rdlock(&g_srv.rwl);
    if (!g_grab) { pthread_rwlock_unlock(&g_srv.rwl); return 0; }
    /* 输入法侧的 modifier 状态独立维护（应用侧的状态由 awl_input.c 管） */
    static uint32_t im_dep = 0, im_lck = 0;
    uint32_t dep = 0, lck = 0;
    meta_to_masks(meta, &dep, &lck);
    int changed = (dep != im_dep || lck != im_lck);
    im_dep = dep; im_lck = lck;

    struct wl_client* c = wl_resource_get_client(g_grab);
    if (changed)
        zwp_input_method_keyboard_grab_v2_send_modifiers(
                g_grab, wl_display_next_serial(g_dpy), dep, 0, lck, 0);
    zwp_input_method_keyboard_grab_v2_send_key(
            g_grab, wl_display_next_serial(g_dpy), awl_now_ms(), code, state);
    wl_client_flush(c);
    pthread_rwlock_unlock(&g_srv.rwl);
    return 1;
}

