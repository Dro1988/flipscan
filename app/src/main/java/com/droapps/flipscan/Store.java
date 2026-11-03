package com.droapps.flipscan;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Settings, price cache, and the haul list — all local SharedPreferences. */
public class Store {

    private static final String PREFS = "flipscan";
    public static final String DEFAULT_BACKEND = "https://flipscan-api.onrender.com";

    private final SharedPreferences sp;

    public Store(Context c) {
        sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ settings

    public double feePct()      { return sp.getFloat("fee_pct", 13.25f); }
    public double feeFixed()    { return sp.getFloat("fee_fixed", 0.40f); }
    public double shipDefault() { return sp.getFloat("ship_default", 6.00f); }
    public String backendUrl()  { return sp.getString("backend_url", DEFAULT_BACKEND); }
    public String lastStore()   { return sp.getString("last_store", "Walmart"); }

    public void saveSettings(double feePct, double feeFixed, double ship, String backend) {
        sp.edit().putFloat("fee_pct", (float) feePct)
                .putFloat("fee_fixed", (float) feeFixed)
                .putFloat("ship_default", (float) ship)
                .putString("backend_url", backend).apply();
    }

    public void setLastStore(String s) { sp.edit().putString("last_store", s).apply(); }

    // ------------------------------------------------------------ price cache

    /** Cached lookup result JSON string for a key, or null if stale/missing. */
    public String cacheGet(String key, long maxAgeMs) {
        try {
            JSONObject all = new JSONObject(sp.getString("cache", "{}"));
            if (!all.has(key)) return null;
            JSONObject e = all.getJSONObject(key);
            if (System.currentTimeMillis() - e.getLong("ts") > maxAgeMs) return null;
            return e.getString("data");
        } catch (Exception e) {
            return null;
        }
    }

    public void cachePut(String key, String data) {
        try {
            JSONObject all = new JSONObject(sp.getString("cache", "{}"));
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("data", data);
            all.put(key, e);
            sp.edit().putString("cache", all.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ haul list

    public JSONArray haul() {
        try {
            return new JSONArray(sp.getString("haul", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public void saveHaul(JSONArray haul) {
        sp.edit().putString("haul", haul.toString()).apply();
    }

    /** Insert or replace (by "id") a haul entry. Returns the entry id. */
    public long upsertHaul(JSONObject entry) {
        try {
            long id = entry.optLong("id", 0);
            if (id == 0) {
                id = System.currentTimeMillis();
                entry.put("id", id);
            }
            JSONArray h = haul();
            boolean replaced = false;
            for (int i = 0; i < h.length(); i++) {
                if (h.getJSONObject(i).optLong("id") == id) {
                    h.put(i, entry);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) h.put(entry);
            saveHaul(h);
            return id;
        } catch (Exception e) {
            return -1;
        }
    }
}
