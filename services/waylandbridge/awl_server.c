/* awl_server.c — display/global objects/server thread (v2: window-driven, no
 * full-screen compositing) */
#define _GNU_SOURCE   /* bionic: pipe2 needs __USE_GNU */
#include "awl_internal.h"

#include <android/log.h>
#include <errno.h>
#include <netinet/in.h>
#include <string.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

struct awl_server g_srv;

/* ---------------- wl_output (main screen) ----------------
 * One virtual output. Its mode is NOT a fixed display size: the bound
 * resources are kept in a list and awl_output_grow re-announces the mode
 * whenever an Android window larger than the current mode attaches. Reason
 * (2026-09-15 Xwayland "unclickable"): rootless Xwayland sizes its X screen
 * from the wl_output bounding box (hw/xwayland/xwayland-output.c
 * update_screen_size) and the X server clamps the pointer to the screen
 * (mi/mipointer.c limits = pScreen->width/height) while touch is normalised
 * by the screen size (xwayland-input.c xwl_touch_send_event). Every X
 * toplevel here is resized to its Android window, so any window bigger than
 * the output had unreachable regions — with the query_display fallback
 * (1280x720 when `wm size` is denied in the awl_daemon domain) that was most
 * of a 3392x2400 window. The mode only ever grows (per axis) — no client
 * sees a shrink — and Xwayland turns it into a RandR change. */

struct awl_output_res {
    struct wl_resource* res;
    struct wl_list link;   /* g_outputs (topology: g_srv.rwl) */
};
static struct wl_list g_outputs;

/* caller holds rwl (rd or wr): resource fields are read, no topology change */
static void output_send_state(struct wl_resource* res) {
    int dpi = g_srv.info.dpi > 0 ? g_srv.info.dpi : 420;
    wl_output_send_geometry(res, 0, 0,
                            (int32_t)(g_srv.info.width * 25.4f / dpi),
                            (int32_t)(g_srv.info.height * 25.4f / dpi),
                            0, "anland", "virtual", 0);
    wl_output_send_mode(res, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED,
                        (int32_t)g_srv.info.width, (int32_t)g_srv.info.height,
                        g_srv.info.refresh_hz * 1000);
    if (wl_resource_get_version(res) >= WL_OUTPUT_SCALE_SINCE_VERSION)
        wl_output_send_scale(res, (uint32_t)g_srv.info.scale);
    if (wl_resource_get_version(res) >= WL_OUTPUT_DONE_SINCE_VERSION)
        wl_output_send_done(res);
}

static void output_res_destroy(struct wl_resource* res) {
    struct awl_output_res* o = wl_resource_get_user_data(res);
    if (!o) return;
    pthread_rwlock_wrlock(&g_srv.rwl);
    wl_list_remove(&o->link);
    pthread_rwlock_unlock(&g_srv.rwl);
    free(o);
}

static void output_release(struct wl_client* client, struct wl_resource* res) {
    wl_resource_destroy(res);
}
static const struct wl_output_interface output_iface = {
    .release = output_release,   /* v3 */
};

static void output_bind(struct wl_client* client, void* data,
                        uint32_t version, uint32_t id) {
    struct wl_resource* res = wl_resource_create(
            client, &wl_output_interface, version < 3 ? version : 3, id);
    if (!res) { wl_client_post_no_memory(client); return; }
    struct awl_output_res* o = calloc(1, sizeof(*o));
    if (!o) { wl_resource_destroy(res); wl_client_post_no_memory(client); return; }
    o->res = res;
    wl_resource_set_implementation(res, &output_iface, o, output_res_destroy);
    pthread_rwlock_wrlock(&g_srv.rwl);
    wl_list_insert(g_outputs.prev, &o->link);
    output_send_state(res);   /* info is read under the same lock grow writes it */
    pthread_rwlock_unlock(&g_srv.rwl);
}

/* Live window sizes.  The mode has to cover the largest window (rootless
 * Xwayland sizes its X screen from the output and clamps pointer/touch to
 * it), but a mode that only ever ratchets is wrong too: one transient
 * portrait window (1264x2780 on the way to 2780x1264) pinned the X screen at
 * 2780x2780 for the rest of the session, and every fullscreen client then
 * read a 1:1 screen.  Keying by window id makes a resize replace that
 * window's own entry instead of ratcheting the maximum, so the mode falls
 * back as soon as the largest window shrinks or closes. */
#define AWL_OUTPUT_WINDOWS 64
static struct { uint64_t id; uint32_t w, h; } g_out_wins[AWL_OUTPUT_WINDOWS];
static int g_out_nwin;
static uint32_t g_out_floor_w, g_out_floor_h;   /* boot mode: the minimum */

static void output_publish_locked(uint32_t w, uint32_t h);

/* Growth is not trustworthy on its own.  Xwayland attaches a portrait buffer
 * (1264x2780 here) while it negotiates a landscape client, so the demand
 * jumps 1280x720 -> 1280x2780 -> 2780x1264 within a few frames.  Publishing
 * the intermediate mode is what makes every client that reads wl_output at
 * startup see a square screen: RandR keeps the excursion for as long as it
 * lasts, and Xwayland re-lays out its root window each time.  Hold every
 * mode change for AWL_OUTPUT_SETTLE_MS and drop it when a newer demand
 * replaces it: the portrait and the landscape demand of a single window are
 * 117 ms apart, so deferring growth alone still lets the square through
 * (the square is larger, the landscape that follows is a shrink).  A 250 ms
 * delay is imperceptible and costs nothing — no client needs a mode switch
 * before it has finished negotiating its own size. */
#define AWL_OUTPUT_SETTLE_MS 250
static pthread_mutex_t g_settle_mlock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_settle_cond  = PTHREAD_COND_INITIALIZER;
static uint32_t g_settle_w, g_settle_h;   /* pending growth */
static unsigned g_settle_gen;             /* bumped on every new demand */
static int g_settle_armed;
static int g_settle_thread_up;

static void* output_settle_thread(void* arg) {
    (void)arg;
    for (;;) {
        pthread_mutex_lock(&g_settle_mlock);
        while (!g_settle_armed)
            pthread_cond_wait(&g_settle_cond, &g_settle_mlock);
        unsigned gen = g_settle_gen;
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        ts.tv_sec  += AWL_OUTPUT_SETTLE_MS / 1000;
        ts.tv_nsec += (long)(AWL_OUTPUT_SETTLE_MS % 1000) * 1000000L;
        if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
        int expired = 0;
        while (g_settle_armed && g_settle_gen == gen) {
            if (pthread_cond_timedwait(&g_settle_cond, &g_settle_mlock, &ts)
                    == ETIMEDOUT) { expired = 1; break; }
        }
        if (!expired || !g_settle_armed || g_settle_gen != gen) {
            pthread_mutex_unlock(&g_settle_mlock);
            continue;
        }
        uint32_t w = g_settle_w, h = g_settle_h;
        g_settle_armed = 0;
        pthread_mutex_unlock(&g_settle_mlock);
        pthread_rwlock_wrlock(&g_srv.rwl);
        output_publish_locked(w, h);
        pthread_rwlock_unlock(&g_srv.rwl);
    }
    return NULL;
}

/* caller holds rwl (wr) */
static void output_publish_locked(uint32_t w, uint32_t h) {
    if (w == g_srv.info.width && h == g_srv.info.height) return;
    g_srv.info.width = w;
    g_srv.info.height = h;
    struct awl_output_res* o;
    wl_list_for_each(o, &g_outputs, link) {
        output_send_state(o->res);
        wl_client_flush(wl_resource_get_client(o->res));
    }
    LOGI("output mode %ux%u", w, h);
}

/* caller holds rwl (wr) */
static void output_request_locked(uint32_t w, uint32_t h) {
    if (w == (uint32_t)g_srv.info.width && h == (uint32_t)g_srv.info.height) return;
    pthread_mutex_lock(&g_settle_mlock);
    if (!g_settle_thread_up) {
        g_settle_thread_up = 1;
        pthread_t t;
        if (pthread_create(&t, NULL, output_settle_thread, NULL) == 0)
            pthread_detach(t);
        else
            g_settle_thread_up = 0;
    }
    g_settle_w = w;
    g_settle_h = h;
    g_settle_gen++;
    g_settle_armed = g_settle_thread_up;
    pthread_cond_broadcast(&g_settle_cond);
    pthread_mutex_unlock(&g_settle_mlock);
    /* no timer thread (spawn failed): publish straight through */
    if (!g_settle_thread_up) output_publish_locked(w, h);
}

/* caller holds rwl (wr) */
static void output_recompute_locked(void) {
    uint32_t w = g_out_floor_w, h = g_out_floor_h;
    int i;
    for (i = 0; i < g_out_nwin; i++) {
        if (g_out_wins[i].w > w) w = g_out_wins[i].w;
        if (g_out_wins[i].h > h) h = g_out_wins[i].h;
    }
    output_request_locked(w, h);
}

/* Any thread (binder SURFACE/RESIZE), once per window and again on every
 * resize: record w×h as this window's demand and re-announce the per-axis
 * maximum over all live windows (floored at the boot mode). */
void awl_output_note(uint64_t id, uint32_t w, uint32_t h) {
    if (!g_srv.running) return;
    pthread_rwlock_wrlock(&g_srv.rwl);
    int i;
    for (i = 0; i < g_out_nwin; i++)
        if (g_out_wins[i].id == id) break;
    if (i == g_out_nwin) {
        if (g_out_nwin == AWL_OUTPUT_WINDOWS) {
            /* table full: keep the old grow-only rule so coverage is never
             * lost (a mode that is too small breaks pointer reach, a mode
             * that is too large only costs aspect) */
            uint32_t nw = w > g_srv.info.width ? w : (uint32_t)g_srv.info.width;
            uint32_t nh = h > g_srv.info.height ? h : (uint32_t)g_srv.info.height;
            output_request_locked(nw, nh);
            pthread_rwlock_unlock(&g_srv.rwl);
            return;
        }
        g_out_wins[i].id = id;
        g_out_nwin++;
    }
    g_out_wins[i].w = w;
    g_out_wins[i].h = h;
    output_recompute_locked();
    pthread_rwlock_unlock(&g_srv.rwl);
}

/* Window gone: drop its demand so a closed large window stops holding the
 * mode up.  Unknown id (popups, already-forgotten) is a no-op. */
void awl_output_forget(uint64_t id) {
    if (!g_srv.running) return;
    pthread_rwlock_wrlock(&g_srv.rwl);
    int i;
    for (i = 0; i < g_out_nwin; i++) {
        if (g_out_wins[i].id != id) continue;
        g_out_wins[i] = g_out_wins[--g_out_nwin];
        output_recompute_locked();
        break;
    }
    pthread_rwlock_unlock(&g_srv.rwl);
}

/* Grow-only convenience (see awl.h): never shrinks by itself — the next
 * awl_output_note/awl_output_forget recomputes from the table. */
void awl_output_grow(uint32_t w, uint32_t h) {
    if (!g_srv.running) return;
    pthread_rwlock_wrlock(&g_srv.rwl);
    uint32_t nw = w > g_srv.info.width ? w : (uint32_t)g_srv.info.width;
    uint32_t nh = h > g_srv.info.height ? h : (uint32_t)g_srv.info.height;
    output_publish_locked(nw, nh);
    pthread_rwlock_unlock(&g_srv.rwl);
}

/* ---------------- Client lifecycle ---------------- */

struct awl_client_li {
    struct wl_listener li;
};

static void client_destroyed(struct wl_listener* li, void* data) {
    struct awl_client_li* c = wl_container_of(li, c, li);
    /* the per-resource destroy listeners have already fired window_destroyed */
    LOGI("client gone");
    free(c);
}

static void client_connected(struct wl_listener* li, void* data) {
    struct wl_client* client = data;
    pid_t pid = -1;
    uid_t uid = -1;
    wl_client_get_credentials(client, &pid, &uid, NULL);
    LOGI("client connected pid=%d uid=%d", (int)pid, (int)uid);
    struct awl_client_li* c = calloc(1, sizeof(*c));
    if (c) {
        c->li.notify = client_destroyed;
        wl_client_add_destroy_listener(client, &c->li);
    }
}

static struct wl_listener g_client_created_li = { .notify = client_connected };

/* ---------------- Per-client dedicated event thread ----------------
 * On map (first buffer commit) the client's fd source is migrated into its
 * own loop + a sub-thread is spawned: from then on all of that client's
 * requests (chrome multi-window = 1 connection) are dispatched exclusively
 * by the sub-thread, and disconnect teardown happens there too — zero
 * contention between clients, the main event thread only accepts.
 * "No hand-back" semantics: the fd is 1:1 with the client connection
 * (window closed ≠ connection closed), it stays on the sub-thread until
 * disconnect. */

struct awl_client_ctx {
    struct wl_client* client;
    struct wl_event_loop* loop;
    pthread_t thread;
    int quit_pipe[2];                 /* to wake epoll (permanent source) */
    struct wl_event_source* quit_src;
    struct wl_listener destroy_li;    /* client disconnected → thread exits itself */
    struct wl_list link;              /* g_srv.clients */
    volatile int stop;                /* exit flag (set by quit callback/shutdown) */
    volatile int client_gone;         /* client already dead (shutdown path skips destroy) */
    volatile int listed;              /* still in g_srv.clients (guards double removal) */
};

static int client_quit_cb(int fd, uint32_t mask, void* data) {
    char buf[8];
    if (mask & WL_EVENT_READABLE)
        while (read(fd, buf, sizeof buf) > 0) {}
    ((struct awl_client_ctx*)data)->stop = 1;
    return 0;
}

/* Client disconnected (starting at wl_client_destroy, running on its
 * sub-thread): mark + unlink only — all teardown happens on the thread's
 * exit path */
static void client_gone_li(struct wl_listener* li, void* data) {
    struct awl_client_ctx* ctx =
        wl_container_of(li, ctx, destroy_li);
    ctx->stop = 1;
    ctx->client_gone = 1;
    char c = 1;
    (void)!write(ctx->quit_pipe[1], &c, 1);   /* wake a possibly blocked epoll */
    pthread_rwlock_wrlock(&g_srv.rwl);
    if (ctx->listed) {   /* after the shutdown splice listed=0 (guards double removal) */
        ctx->listed = 0;
        wl_list_remove(&ctx->link);
    }
    pthread_rwlock_unlock(&g_srv.rwl);
    LOGI("client ctx: connection gone, sub loop exiting");
}

/* The sub-thread owns its own teardown: after leaving the loop it destroys
 * the client (if still alive — this thread is its dispatch thread and the
 * loop is stopped, no concurrency) → removes the permanent source/pipe/loop
 * → frees ctx. The shutdown path only joins the snapshotted pthread_t and
 * never touches ctx again (no double teardown). */
static void* client_loop_thread(void* arg) {
    struct awl_client_ctx* ctx = arg;
    while (!ctx->stop && wl_event_loop_dispatch(ctx->loop, -1) == 0) {
        /* Mirror wl_display_run's flush: events libwayland itself queues
         * during this dispatch (wl_callback.done of wl_display.sync,
         * wl_display.delete_id) have no explicit wl_client_flush of their
         * own, and wl_display_flush_clients on the main loop deliberately
         * skips migrated clients — without this a client doing a roundtrip
         * right after its first commit stalls until some direct-send path
         * (render presented / input) happens to flush (surfaced by
         * test/host/cursor_test, which has no renderer). */
        if (!ctx->client_gone) wl_client_flush(ctx->client);
    }
    if (!ctx->client_gone)
        wl_client_destroy(ctx->client);
    wl_event_source_remove(ctx->quit_src);
    close(ctx->quit_pipe[0]);
    close(ctx->quit_pipe[1]);
    wl_event_loop_destroy(ctx->loop);
    free(ctx);
    return NULL;
}

/* Idle source armed by a migration, runs on the main loop right before its
 * next epoll (wl_display_run: flush_clients → dispatch → idle): the batch
 * that carried the mapping commit usually also carries a wl_display.sync
 * (client roundtrip) which is still dispatched on the main thread after the
 * fd source moved — wl_display_flush_clients deliberately skips migrated
 * clients (their event mask belongs to the sub loop), so the queued done /
 * delete_id would never leave and the client would wait forever while the
 * sub loop waits for input (test/host/cursor_test hang). Flushing does not
 * touch the event mask (connection mutex only) → safe from this thread.
 * ctx entries are unlinked under rwl.wr at the start of client destruction
 * → every listed client is alive while we hold rd. */
static void flush_migrated_idle(void* data) {
    pthread_rwlock_rdlock(&g_srv.rwl);
    struct awl_client_ctx* ctx;
    wl_list_for_each(ctx, &g_srv.clients, link)
        wl_client_flush(ctx->client);
    pthread_rwlock_unlock(&g_srv.rwl);
}

/* Called on map (the dispatch thread at that moment = the old loop thread,
 * satisfying the migration thread contract) */
void awl_client_maybe_migrate(struct wl_client* client) {
    if (!client) return;

    pthread_rwlock_wrlock(&g_srv.rwl);   /* vs shutdown splice / later maps */
    if (!g_srv.running || wl_client_get_user_data(client)) {
        pthread_rwlock_unlock(&g_srv.rwl);   /* already migrated / shutting down */
        return;
    }

    struct awl_client_ctx* ctx = calloc(1, sizeof(*ctx));
    if (!ctx) goto fail;
    ctx->client = client;
    ctx->listed = 1;

    ctx->loop = wl_event_loop_create();
    if (!ctx->loop) goto fail_loop;
    if (pipe2(ctx->quit_pipe, O_CLOEXEC | O_NONBLOCK) != 0) goto fail_pipe;
    ctx->quit_src = wl_event_loop_add_fd(ctx->loop, ctx->quit_pipe[0],
                                         WL_EVENT_READABLE, client_quit_cb, ctx);
    if (!ctx->quit_src) goto fail_quit;

    if (wl_client_set_event_loop(client, ctx->loop) != 0)   /* fd source migration */
        goto fail_migrate;

    ctx->destroy_li.notify = client_gone_li;
    wl_client_add_destroy_listener(client, &ctx->destroy_li);
    wl_client_set_user_data(client, ctx, NULL);   /* migrated marker */

    wl_list_insert(g_srv.clients.prev, &ctx->link);
    if (pthread_create(&ctx->thread, NULL, client_loop_thread, ctx) != 0)
        goto fail_thread;   /* already linked, just unlink */

    pthread_rwlock_unlock(&g_srv.rwl);
    wl_event_loop_add_idle(g_srv.loop, flush_migrated_idle, NULL);   /* see flush_migrated_idle */
    LOGI("client migrated to dedicated loop (tid=%llu)",
            (unsigned long long)ctx->thread);
    return;

fail_thread:
    wl_list_remove(&ctx->link);
    wl_list_remove(&ctx->destroy_li.link);   /* unlink the listener before free (no dangling) */
    wl_client_set_user_data(client, NULL, NULL);
    wl_client_set_event_loop(client, g_srv.loop);   /* migrate back to the main loop (this thread) */
fail_migrate:
    wl_event_source_remove(ctx->quit_src);
fail_quit:
    close(ctx->quit_pipe[0]);
    close(ctx->quit_pipe[1]);
fail_pipe:
    if (ctx->loop) wl_event_loop_destroy(ctx->loop);
fail_loop:
    free(ctx);
fail:
    pthread_rwlock_unlock(&g_srv.rwl);
    LOGI("client migrate failed (staying on the main event thread)");
}

/* listen fd accept → wl_client_create (mirrors libwayland socket.c) */
static int socket_accept(int fd, uint32_t mask, void* data) {
    if (!(mask & WL_EVENT_READABLE)) return 0;
    int cfd = accept(fd, NULL, NULL);
    if (cfd < 0) {
        LOGE("accept: %s", strerror(errno));
        return 0;
    }
    struct wl_client* client = wl_client_create(g_srv.display, cfd);
    if (!client) {
        close(cfd);
        LOGE("wl_client_create failed");
    }
    return 0;
}

/* ---------------- Binder-injected clients (#36) ----------------
 * A binder-thread hands in one end of an app-created socketpair
 * (awl_server_add_client). wl_client_create manipulates the display's main
 * event loop (add_fd on its source list) — that is loop-thread-only, so the
 * fd travels over this pipe and the event loop itself does the create.
 * The peer creds read by wl_client_create were fixed at socketpair creation
 * in the app = the wayland client's real uid/pid (see awl.h). */

static int g_inject_pipe[2] = {-1, -1};
static struct wl_event_source* g_inject_src = NULL;

static int inject_cb(int fd, uint32_t mask, void* data) {
    if (!(mask & WL_EVENT_READABLE)) return 0;
    char buf[256];
    ssize_t n;
    while ((n = read(fd, buf, sizeof buf)) > 0) {
        for (ssize_t i = 0; i + (ssize_t)sizeof(int) <= n; i += (ssize_t)sizeof(int)) {
            int cfd;
            memcpy(&cfd, buf + i, sizeof cfd);
            struct wl_client* client = wl_client_create(g_srv.display, cfd);
            if (!client) {
                close(cfd);
                LOGE("binder-injected client: wl_client_create failed");
            }
        }
    }
    return 0;
}

/* Any thread (binder). 0 = the event loop thread now owns the fd, <0 = the
 * caller keeps it and closes it. */
int awl_server_add_client(int fd) {
    if (!g_srv.running || !g_inject_src || g_inject_pipe[1] < 0) {
        errno = ENOTCONN;
        return -1;
    }
    char b[sizeof(int)];
    memcpy(b, &fd, sizeof b);
    if (write(g_inject_pipe[1], b, sizeof b) != (ssize_t)sizeof b)
        return -1;
    return 0;
}

/* (The wake marshalling pipe is gone — with the libwayland awl patches every
 * thread sends directly:
 *   input           → binder thread sends directly (awl_input_dispatch)
 *   render requests → awl_renderer_request_render (built-in request
 *                     coalescing, thread-safe)
 *   frame_done      → render thread sends directly (awl_surface_presented)
 *   resize/activated/close → binder thread sends directly (awl_window_*)
 * The event thread is down to pure protocol + accept.) */

/* ---------------- Server thread ---------------- */

static void* server_thread(void* arg) {
    LOGI("event loop running");
    wl_display_run(g_srv.display);
    LOGI("event loop exiting");
    return NULL;
}

int awl_server_start(int listen_fd, const awl_display_info_t* info,
                     const awl_window_callbacks_t* cbs) {
    if (g_srv.running) return 0;

    memset(&g_srv, 0, sizeof(g_srv));
    pthread_rwlock_init(&g_srv.rwl, NULL);
    wl_list_init(&g_srv.surfaces);
    wl_list_init(&g_srv.clients);
    wl_list_init(&g_outputs);
    g_srv.next_surface_id = 1;
    g_srv.info = *info;
    if (g_srv.info.scale <= 0) g_srv.info.scale = 1;
    if (g_srv.info.refresh_hz <= 0) g_srv.info.refresh_hz = 60;
    g_out_floor_w = g_srv.info.width;
    g_out_floor_h = g_srv.info.height;
    g_srv.cbs = *cbs;

    g_srv.display = wl_display_create();
    g_srv.loop = wl_display_get_event_loop(g_srv.display);
    wl_display_init_shm(g_srv.display);
    wl_display_add_client_created_listener(g_srv.display, &g_client_created_li);

    awl_surface_setup();      /* wl_compositor / wl_surface / wl_region */
    awl_dmabuf_setup();       /* zwp_linux_dmabuf_v1 */
    awl_xdg_setup();          /* xdg_wm_base */
    awl_subsurface_setup();   /* wl_subcompositor (chrome bubbles / GTK4 popovers) */
    awl_input_setup();        /* wl_seat (input object table + passthrough translation) */
    awl_datadev_setup();      /* wl_data_device_manager v3 (selection+DnD) */
    awl_ime_setup();          /* zwp_text_input v1+v3 (Android IME bridge) */
    awl_im_setup();           /* zwp_input_method_v2 (container IME, e.g. fcitx5) */
    awl_viewport_setup();     /* wp_viewporter + fractional-scale (#31 zoom) */
    awl_xwayland_setup();     /* xwayland_shell_v1 (Xwayland rootless, #32) */
    awl_idle_setup();         /* zwp_idle_inhibit_manager_v1 (keep-screen-on, C_KEEPON) */
    awl_icon_setup();         /* xdg_toplevel_icon_manager_v1 (per-window icons, C_ICON) */
    awl_esync_setup();        /* zwp_linux_explicit_synchronization_v1 (acquire/release fences) */

    if (!wl_global_create(g_srv.display, &wl_output_interface, 3,
                          NULL, output_bind))
        LOGE("wl_output global create failed");

    if (listen_fd >= 0)
        wl_event_loop_add_fd(g_srv.loop, listen_fd, WL_EVENT_READABLE,
                             socket_accept, NULL);
    else
        LOGI("no listen socket — binder-injected clients only");

    /* binder-fd handoff pipe (awl_server_add_client): CLOEXEC so am/exec
     * children never inherit it, NONBLOCK so a binder thread can never
     * stall on it (4 bytes per fd vs 64K pipe capacity — never full) */
    if (pipe2(g_inject_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        LOGE("inject pipe2: %s", strerror(errno));
        wl_display_destroy(g_srv.display);
        return -1;
    }
    g_inject_src = wl_event_loop_add_fd(g_srv.loop, g_inject_pipe[0],
                                        WL_EVENT_READABLE, inject_cb, NULL);
    if (!g_inject_src) {
        LOGE("inject source add failed");
        close(g_inject_pipe[0]);
        close(g_inject_pipe[1]);
        g_inject_pipe[0] = g_inject_pipe[1] = -1;
        wl_display_destroy(g_srv.display);
        return -1;
    }

    g_srv.running = 1;
    if (pthread_create(&g_srv.thread, NULL, server_thread, NULL) != 0) {
        LOGE("pthread_create failed");
        wl_display_destroy(g_srv.display);
        g_srv.running = 0;
        return -1;
    }
    return 0;
}

void awl_server_stop(void) {
    if (!g_srv.running) return;
    g_srv.running = 0;

    /* 1. Snapshot the sub-thread handles and wake them (wr: vs map
     *    migration). From here this function never touches ctx — the
     *    threads own their teardown (destroy client + tear down loop +
     *    free). */
    pthread_t tids[128];
    size_t n = 0, overflow = 0;
    pthread_rwlock_wrlock(&g_srv.rwl);
    struct awl_client_ctx* ctx;
    struct awl_client_ctx* tmp;
    wl_list_for_each_safe(ctx, tmp, &g_srv.clients, link) {
        if (n < sizeof(tids) / sizeof(tids[0])) tids[n++] = ctx->thread;
        else overflow++;
        ctx->listed = 0;
        char c = 1;
        (void)!write(ctx->quit_pipe[1], &c, 1);
    }
    wl_list_init(&g_srv.clients);
    pthread_rwlock_unlock(&g_srv.rwl);
    if (overflow)
        LOGI("stop: %zu sub-threads exceed the snapshot capacity, they exit with the process", overflow);

    /* 2. Main event thread exits (accept / unmigrated client dispatch) */
    wl_display_terminate(g_srv.display);
    pthread_join(g_srv.thread, NULL);

    /* inject pipe down (event thread joined → no concurrent callback;
     * libwayland's source remove does not close the fd — close both ends
     * ourselves, before wl_display_destroy could allocate over them) */
    if (g_inject_src) { wl_event_source_remove(g_inject_src); g_inject_src = NULL; }
    if (g_inject_pipe[0] >= 0) close(g_inject_pipe[0]);
    if (g_inject_pipe[1] >= 0) close(g_inject_pipe[1]);
    g_inject_pipe[0] = g_inject_pipe[1] = -1;

    /* 3. Join all sub-threads (each finishes its client destruction and teardown) */
    for (size_t i = 0; i < n; i++) pthread_join(tids[i], NULL);

    /* 4. Unmigrated clients (connections that never mapped) destroyed in
     *    one sweep — main thread already stopped, no concurrent dispatch */
    wl_display_destroy_clients(g_srv.display);

    wl_display_destroy(g_srv.display);
    pthread_rwlock_destroy(&g_srv.rwl);
    memset(&g_srv, 0, sizeof(g_srv));
    LOGI("server stopped");
}

int awl_server_is_running(void) {
    return g_srv.running;
}
