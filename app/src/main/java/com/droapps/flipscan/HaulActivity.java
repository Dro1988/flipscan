package com.droapps.flipscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Every scan saved on the phone, with the running bought-profit total. */
public class HaulActivity extends Activity {

    private Store store;
    private LinearLayout list;
    private TextView totalTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 16));

        root.addView(Ui.text(this, "Haul list", 26, Ui.TEXT_HI, true),
                Ui.lp(this, -1, -2, 0, 0, 0, 4));
        totalTv = Ui.text(this, "", 16, Ui.ACCENT, true);
        root.addView(totalTv, Ui.lp(this, -1, -2, 0, 0, 0, 10));
        TextView hint = Ui.text(this,
                "Tap an item to mark it bought / not bought. Long-press to delete.",
                12, Ui.TEXT_LO, false);
        root.addView(hint, Ui.lp(this, -1, -2, 0, 0, 0, 10));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        Button clear = Ui.button(this, "Clear whole haul", Ui.CARD, Ui.BAD, 14);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Delete every saved scan?")
                .setPositiveButton("Delete all", (d, w) -> {
                    store.saveHaul(new JSONArray());
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show());
        root.addView(clear, Ui.lp(this, -1, -2, 0, 14, 0, 0));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        Ui.safeArea(sv);
        render();
    }

    private void render() {
        list.removeAllViews();
        JSONArray h = store.haul();
        double boughtProfit = 0;
        int boughtCount = 0;
        // newest first
        for (int i = h.length() - 1; i >= 0; i--) {
            JSONObject e;
            try { e = h.getJSONObject(i); } catch (Exception ex) { continue; }
            boolean bought = e.optBoolean("bought");
            if (bought) {
                boughtCount++;
                boughtProfit += e.optDouble("profit", 0);
            }
            final long id = e.optLong("id");
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            String title = e.optString("title");
            if (title.isEmpty()) title = "UPC " + e.optString("upc");
            TextView t = Ui.text(this, (bought ? "✓ " : "") + title, 15, Ui.TEXT_HI, true);
            t.setMaxLines(2);
            inner.addView(t);
            String verdict = e.optString("verdict");
            int vColor = "BUY".equals(verdict) ? Ui.ACCENT
                    : "SKIP".equals(verdict) ? Ui.BAD : Ui.WARN;
            TextView meta = Ui.text(this,
                    e.optString("date") + " · " + e.optString("store")
                    + "\nShelf " + Ui.money(e.optDouble("shelf"))
                    + " → sells " + Ui.money(e.optDouble("resale"))
                    + " · net " + Ui.money(e.optDouble("profit"))
                    + " · " + verdict + " (" + e.optString("dataKind") + ")",
                    13, Ui.TEXT_LO, false);
            inner.addView(meta, Ui.lp(this, -1, -2, 0, 3, 0, 0));
            TextView badge = Ui.text(this, verdict, 13, vColor, true);
            inner.addView(badge, Ui.lp(this, -1, -2, 0, 2, 0, 0));
            android.view.View card = Ui.card(this, inner);
            card.setOnClickListener(v -> { toggleBought(id); });
            card.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setMessage("Delete this scan?")
                        .setPositiveButton("Delete", (d, w) -> delete(id))
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
            list.addView(card, Ui.lp(this, -1, -2, 0, 0, 0, 10));
        }
        if (h.length() == 0) {
            list.addView(Ui.text(this, "No scans yet. Go hit a clearance aisle.",
                    15, Ui.TEXT_LO, false));
        }
        totalTv.setText("Bought: " + boughtCount + " items · est. profit "
                + Ui.money(boughtProfit) + " · " + h.length() + " scans total");
    }

    private void toggleBought(long id) {
        try {
            JSONArray h = store.haul();
            for (int i = 0; i < h.length(); i++) {
                JSONObject e = h.getJSONObject(i);
                if (e.optLong("id") == id) {
                    e.put("bought", !e.optBoolean("bought"));
                    break;
                }
            }
            store.saveHaul(h);
        } catch (Exception ignored) {}
        render();
    }

    private void delete(long id) {
        try {
            JSONArray h = store.haul();
            JSONArray keep = new JSONArray();
            for (int i = 0; i < h.length(); i++) {
                JSONObject e = h.getJSONObject(i);
                if (e.optLong("id") != id) keep.put(e);
            }
            store.saveHaul(keep);
        } catch (Exception ignored) {}
        render();
    }
}
