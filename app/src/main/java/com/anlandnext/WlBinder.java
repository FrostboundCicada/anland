package com.anlandnext;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Daemon config channel of the binder service "anland.host" — the ONLY
 * thing the host APK still talks raw binder for. Window management (list /
 * events / attach / close / spawning) goes through the libawl library, the
 * same API third-party consumer apps use; window hosting runs in
 * libawl's AwlWindowActivity.
 * Reflects into ServiceManager.getService (hidden API, light-grey — usable
 * at targetSdk 29).
 */
public final class WlBinder {
    private static final String TAG = "anland-binder";
    /** Descriptor from the daemon's AIBinder_Class_define — the NDK wrapper's checkInterface requires transactions to start with it */
    private static final String DESCRIPTOR = "anland.IHost";
    public static final int T_CFG_GET = 12;   /* (key) → val: read a daemon config (#31) */
    public static final int T_CFG_SET = 13;   /* (key,val) → ok: apply + persist in daemon (#31) */
    public static final int T_APPS_LIST = 19; /* (container) → count + records: container app catalogue */
    public static final int T_APPS_RUN = 20;  /* (container,appid) → ok: launch one container app */

    /** One entry of the container application catalogue. */
    public static final class AppEntry {
        public final String id;
        public final String name;
        public final boolean terminal;
        public final boolean noDisplay;

        AppEntry(String id, String name, boolean terminal, boolean noDisplay) {
            this.id = id;
            this.name = name;
            this.terminal = terminal;
            this.noDisplay = noDisplay;
        }
    }

    private static IBinder s;

    private static IBinder get() {
        if (s != null && s.pingBinder()) return s;
        s = null;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod("getService", String.class);
            IBinder b = (IBinder) m.invoke(null, "anland.host");
            if (b == null) Log.w(TAG, "getService(anland.host) = null");
            else if (b.pingBinder()) s = b;
            else Log.w(TAG, "anland.host pingBinder=false");
        } catch (Exception e) {
            Log.e(TAG, "getService failed", e);
        }
        return s != null ? s : null;
    }

    public static boolean available() { return get() != null; }

    /* ---- Daemon config read/write (#31: the daemon is the numeric source
     *      of truth, the APK keeps no copy. Field order matches daemon
     *      AWL_T_CFG_GET/SET: key:string16 [val:i32]) ---- */

    /** Read a daemon config (e.g. "zoom"); returns -1 when the daemon is
     *  unavailable or the key is unknown */
    public static int configGet(String key) {
        IBinder b = get();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(key);
            b.transact(T_CFG_GET, d, r, 0);
            return r.dataSize() >= 4 ? r.readInt() : -1;
        } catch (Exception e) {
            Log.e(TAG, "CFG_GET failed", e);
            s = null;
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** Write a daemon config (apply + persist to config.json); 0 = success */
    public static int configSet(String key, int val) {
        IBinder b = get();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(key);
            d.writeInt(val);
            b.transact(T_CFG_SET, d, r, 0);
            return r.dataSize() >= 4 ? r.readInt() : -1;
        } catch (Exception e) {
            Log.e(TAG, "CFG_SET failed", e);
            s = null;
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** Container application catalogue; empty when the daemon or the container
     *  is unavailable (pass "" to use the daemon's configured container). */
    public static java.util.List<AppEntry> appsList(String container) {
        java.util.List<AppEntry> out = new java.util.ArrayList<>();
        IBinder b = get();
        if (b == null) return out;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(container == null ? "" : container);
            b.transact(T_APPS_LIST, d, r, 0);
            if (r.dataSize() < 4) return out;
            r.setDataPosition(0);
            int n = r.readInt();
            if (n <= 0) return out;
            for (int i = 0; i < n; i++) {
                String id = r.readString();
                String name = r.readString();
                boolean terminal = r.readInt() != 0;
                boolean noDisplay = r.readInt() != 0;
                if (id != null && !id.isEmpty()) out.add(new AppEntry(id, name, terminal, noDisplay));
            }
        } catch (Exception e) {
            Log.e(TAG, "APPS_LIST failed", e);
            s = null;
        } finally {
            d.recycle();
            r.recycle();
        }
        return out;
    }

    /** Launch a container application by id; 0 = success. */
    public static int appsRun(String container, String appid) {
        IBinder b = get();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(container == null ? "" : container);
            d.writeString(appid);
            b.transact(T_APPS_RUN, d, r, 0);
            return r.dataSize() >= 4 ? r.readInt() : -1;
        } catch (Exception e) {
            Log.e(TAG, "APPS_RUN failed", e);
            s = null;
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private WlBinder() { }
}
