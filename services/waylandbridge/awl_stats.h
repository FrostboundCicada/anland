/* awl_stats.h — pipeline counters (diagnostics).
 *
 * OFF by default: every counter is a compile-time no-op, so the production
 * binary carries no instrumentation at all (the same shape as the AWL_LOG_DEBUG
 * option in CMakeLists.txt). Build with -DAWL_STATS=ON to measure the frame
 * path; the daemon then dumps a summary line every AWL_STATS_INTERVAL seconds
 * (default 5) to logcat (tag "anland-stats") and only when the environment
 * variable AWL_STATS=1 is set.
 *
 * What the counters answer: how many commits/frames reach the compositor, how
 * many pixels are uploaded per second (the wl_shm upload is the dominant
 * per-frame cost of a software-rasterizer client), how many of those uploads
 * are full-buffer vs damage-rect, how many SurfaceControl transactions are
 * applied, and how much time the vsync pass spends. The damage-coordinate
 * counters (d_surf vs d_buf) show which coordinate system a client uses —
 * the difference that made the surface-vs-buffer damage bug visible.
 *
 * C and C++ translation units both include this header: the counters live in
 * awl_stats.c behind plain accessor functions (no atomics in the interface,
 * so the header stays valid in both languages). The extern "C" guard keeps the
 * C++ callers linked against the C definitions. */
#ifndef AWL_STATS_H
#define AWL_STATS_H

#include <stdint.h>

/* The counter ids exist in every build (the stubs below ignore them), so
 * the measurement points compile unchanged with and without AWL_STATS. */
enum awl_stat_id {
    AWL_ST_SC_PASS = 0,      /* sc_render_window calls (one per window per vsync) */
    AWL_ST_SC_APPLY,         /* SurfaceControl transactions actually applied */
    AWL_ST_SC_LAYERS,        /* layers examined across all passes */
    AWL_ST_SC_PASS_NS,       /* nanoseconds spent inside the render passes */
    AWL_ST_SYNC_CALLS,       /* awl_sc_sync calls (one per window_dirty) */
    AWL_ST_SYNC_WALKED,      /* layer entries walked by the sync's tree snapshot */
    AWL_ST_SHM_UPLOADS,      /* glTexSubImage2D calls (shm → texture) */
    AWL_ST_SHM_UPLOAD_PX,    /* pixels uploaded */
    AWL_ST_SHM_DMG_PX,       /* pixels covered by the damage rect */
    AWL_ST_COMMIT,           /* wl_surface.commit calls */
    AWL_ST_DIRTY,            /* window_dirty callbacks (client commit → render request) */
    AWL_ST_COMMIT_ATTACH,    /* commits that carried an attach */
    AWL_ST_COMMIT_INPLACE,   /* commits without an attach (in-place redraw) */
    AWL_ST_ATTACH_SAMEBUF,   /* attaches of the SAME wl_buffer as the previous attach */
    AWL_ST_ATTACH_NEWBUF,    /* attaches of a different wl_buffer */
    AWL_ST_DMG_RECT,         /* commits that reported a damage rect */
    AWL_ST_DMG_NONE,         /* attaches without any damage report */
    AWL_ST_DMG_AREA,         /* sum of the reported damage rect areas (px) */
    AWL_ST_SHM_FULL,         /* shm uploads that covered the whole buffer */
    AWL_ST_UPLOAD_NS,        /* nanoseconds inside the glTexSubImage2D upload */
    AWL_ST_PASS_NOOP,        /* passes that applied nothing */
    AWL_ST_PASS_APPLY,       /* passes that applied a transaction */
    AWL_ST_TXN_CREATE,       /* ASurfaceTransaction_create calls */
    AWL_ST_DMG_SURFACE,      /* wl_surface.damage calls (surface coords) */
    AWL_ST_DMG_BUFFER,       /* wl_surface.damage_buffer calls (buffer coords) */
    AWL_ST_DMG_W,            /* sum of the reported damage widths */
    AWL_ST_DMG_H,            /* sum of the reported damage heights */
    AWL_ST_BUF_W,            /* the buffer width of the last shm frame */
    AWL_ST_BUF_H,            /* the buffer height of the last shm frame */
    AWL_ST_SCALE_MISMATCH,   /* frames whose buffer is larger than the damage area */
    AWL_ST_SURF_W,           /* the surface logical width of the last shm frame */
    AWL_ST_SURF_H,           /* the surface logical height of the last shm frame */
    AWL_ST_VP_DST,           /* frames with a viewport destination set */
    AWL_ST_MAX
};

#ifdef AWL_STATS


#ifdef __cplusplus
extern "C" {
#endif


void     awl_stats_add(int id, uint64_t n);
uint64_t awl_stats_get(int id);
void     awl_stats_dump(const char* tag);
int      awl_stats_enabled(void);
void     awl_stats_start(void);

#ifdef __cplusplus
}
#endif

#else   /* !AWL_STATS — the production build: no instrumentation at all */

static inline void awl_stats_add(int id, uint64_t n) { (void)id; (void)n; }
static inline uint64_t awl_stats_get(int id) { (void)id; return 0; }
static inline void awl_stats_dump(const char* tag) { (void)tag; }
static inline int awl_stats_enabled(void) { return 0; }
static inline void awl_stats_start(void) { }

#endif /* AWL_STATS */

#endif /* AWL_STATS_H */
