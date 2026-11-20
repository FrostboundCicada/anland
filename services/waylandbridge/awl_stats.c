/* awl_stats.c — pipeline counters behind a compile-time + env gate.
 *
 * The counters are compiled in only with -DAWL_STATS (see the AWL_STATS
 * CMake option); without it this file is empty and awl_stats.h's inline
 * stubs are used, so the production binary carries no instrumentation. With
 * it, the dump thread only exists when AWL_STATS=1 is set in the daemon's
 * environment, and it prints a delta summary every AWL_STATS_INTERVAL
 * seconds so two runs can be compared without a debugger attached. */
#include "awl_stats.h"

#ifdef AWL_STATS

#include <android/log.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define AWL_TAG "anland-stats"
#include "awl_log.h"

static _Atomic uint64_t g_cnt[AWL_ST_MAX];
static int g_enabled = -1;   /* -1 = not probed yet */

static const char* const k_names[AWL_ST_MAX] = {
    [AWL_ST_SC_PASS]       = "sc_pass",
    [AWL_ST_SC_APPLY]      = "sc_apply",
    [AWL_ST_SC_LAYERS]     = "sc_layers",
    [AWL_ST_SC_PASS_NS]    = "sc_pass_ms",
    [AWL_ST_SYNC_CALLS]    = "sync_calls",
    [AWL_ST_SYNC_WALKED]   = "sync_walked",
    [AWL_ST_SHM_UPLOADS]   = "shm_uploads",
    [AWL_ST_SHM_UPLOAD_PX] = "shm_upload_px",
    [AWL_ST_SHM_DMG_PX]    = "shm_dmg_px",
    [AWL_ST_COMMIT]        = "commits",
    [AWL_ST_DIRTY]         = "dirty",
    [AWL_ST_COMMIT_ATTACH] = "c_attach",
    [AWL_ST_COMMIT_INPLACE] = "c_inplace",
    [AWL_ST_ATTACH_SAMEBUF] = "a_same",
    [AWL_ST_ATTACH_NEWBUF]  = "a_new",
    [AWL_ST_DMG_RECT]      = "d_rect",
    [AWL_ST_DMG_NONE]      = "d_none",
    [AWL_ST_DMG_AREA]      = "d_area",
    [AWL_ST_SHM_FULL]      = "shm_full",
    [AWL_ST_UPLOAD_NS]     = "upload_ms",
    [AWL_ST_PASS_NOOP]     = "p_noop",
    [AWL_ST_PASS_APPLY]    = "p_apply",
    [AWL_ST_TXN_CREATE]    = "txn_create",
    [AWL_ST_DMG_SURFACE]   = "d_surf",
    [AWL_ST_DMG_BUFFER]    = "d_buf",
    [AWL_ST_DMG_W]         = "d_w",
    [AWL_ST_DMG_H]         = "d_h",
    [AWL_ST_BUF_W]         = "buf_w",
    [AWL_ST_BUF_H]         = "buf_h",
    [AWL_ST_SCALE_MISMATCH] = "scale_mm",
    [AWL_ST_SURF_W]         = "surf_w",
    [AWL_ST_SURF_H]         = "surf_h",
    [AWL_ST_VP_DST]         = "vp_dst",
    [AWL_ST_T_SNAPSHOT]     = "t_snap_ms",
    [AWL_ST_T_QUEUE]        = "t_queue_ms",
    [AWL_ST_T_UPLOAD]       = "t_upload_ms",
    [AWL_ST_T_LATCH]        = "t_latch_ms",
    [AWL_ST_T_APPLY]        = "t_apply_ms",
    [AWL_ST_PASS_GEOM_ONLY] = "p_geom",
    [AWL_ST_GEO_CHANGED]    = "geo_chg",
};

int awl_stats_enabled(void) {
    if (g_enabled < 0) {
        const char* v = getenv("AWL_STATS");
        g_enabled = (v && v[0] == '1') ? 1 : 0;
    }
    return g_enabled;
}

void awl_stats_add(int id, uint64_t n) {
    if (id < 0 || id >= AWL_ST_MAX) return;
    atomic_fetch_add_explicit(&g_cnt[id], n, memory_order_relaxed);
}

void awl_stats_add_elapsed(int id, const struct timespec* start) {
    if (id < 0 || id >= AWL_ST_MAX) return;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    atomic_fetch_add_explicit(&g_cnt[id],
        (uint64_t)((now.tv_sec - start->tv_sec) * 1000000000LL +
                   (now.tv_nsec - start->tv_nsec)),
        memory_order_relaxed);
}

uint64_t awl_stats_get(int id) {
    if (id < 0 || id >= AWL_ST_MAX) return 0;
    return atomic_load_explicit(&g_cnt[id], memory_order_relaxed);
}

void awl_stats_dump(const char* tag) {
    if (!awl_stats_enabled()) return;
    static uint64_t last[AWL_ST_MAX];
    char buf[1024];
    int n = 0;
    for (int i = 0; i < AWL_ST_MAX; i++) {
        uint64_t cur = atomic_load_explicit(&g_cnt[i], memory_order_relaxed);
        n += snprintf(buf + n, sizeof(buf) - n, "%s=%llu ",
                       k_names[i], (unsigned long long)(cur - last[i]));
        last[i] = cur;
    }
    LOGI("[%s] %s", tag ? tag : "stats", buf);
}

static void* dump_thread(void* arg) {
    (void)arg;
    const char* iv = getenv("AWL_STATS_INTERVAL");
    int secs = iv ? atoi(iv) : 5;
    if (secs <= 0) secs = 5;
    for (;;) {
        sleep((unsigned)secs);
        awl_stats_dump("delta");
    }
    return NULL;
}

void awl_stats_start(void) {
    if (!awl_stats_enabled()) return;
    const char* iv = getenv("AWL_STATS_INTERVAL");
    int secs = iv ? atoi(iv) : 5;
    if (secs <= 0) secs = 5;
    pthread_t th;
    if (pthread_create(&th, NULL, dump_thread, NULL) == 0) pthread_detach(th);
    LOGI("pipeline stats enabled (interval %ds)", secs);
}

#endif /* AWL_STATS */
