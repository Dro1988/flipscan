package com.droapps.flipscan;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The money screen: what it sells for, the full fee/shipping math, and a
 * big BUY / MAYBE / SKIP verdict against the shelf price he types in.
 */
public class VerdictActivity extends Activity {

    private static final int REQ_SCAN = 77;
    private static final String[] STORES = {"Walmart", "Target", "Dollar General", "Other"};

    private Store store;
    private String upc = "";
    private String titleQuery = "";
    private JSONObject result;          // lookup result (may be upgraded by web fetch)
    private long haulId = -1;
    private boolean bought = false;
    private String storeName = "Walmart";
    private String shelfStr = "";
    private boolean shelfPrefilled = false;

    private TextView statusTv, titleTv, metaTv, basisTv, basisSubTv;
    private TextView breakdownTv, profitTv, verdictTv, verdictSubTv, shelfTv;
    private TextView marketTv;
    private View marketCard;
    private ImageView photoIv;
    private EditText shipEt;
    private LinearLayout storeRow;
    private Button boughtBtn;
    private LinearLayout padGrid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        storeName = store.lastStore();
        upc = getIntent().getStringExtra("upc");
        titleQuery = getIntent().getStringExtra("title");
        if (upc == null) upc = "";
        if (titleQuery == null) titleQuery = "";

        buildUi();
        statusTv.setText("Looking up prices… (first lookup can take ~30s while the price server wakes)");
        new Lookup(this).run(upc.isEmpty() ? null : upc,
                titleQuery.isEmpty() ? null : titleQuery,
                this::onLookupDone);
    }

    // ------------------------------------------------------------ UI build

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18));

        // header: photo + title
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        photoIv = new ImageView(this);
        photoIv.setBackgroundColor(Ui.CARD);
        head.addView(photoIv, new LinearLayout.LayoutParams(
                Ui.dp(this, 84), Ui.dp(this, 84)));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(Ui.dp(this, 12), 0, 0, 0);
        titleTv = Ui.text(this, upc.isEmpty() ? titleQuery : "UPC " + upc, 19, Ui.TEXT_HI, true);
        metaTv = Ui.text(this, "", 13, Ui.TEXT_LO, false);
        ht.addView(titleTv);
        ht.addView(metaTv, Ui.lp(this, -1, -2, 0, 4, 0, 0));
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(head, Ui.lp(this, -1, -2, 0, 0, 0, 12));

        statusTv = Ui.text(this, "", 14, Ui.WARN, false);
        root.addView(statusTv, Ui.lp(this, -1, -2, 0, 0, 0, 10));

        // basis (resale price) card
        LinearLayout basisInner = new LinearLayout(this);
        basisInner.setOrientation(LinearLayout.VERTICAL);
        basisTv = Ui.center(Ui.text(this, "—", 40, Ui.TEXT_HI, true));
        basisSubTv = Ui.center(Ui.text(this, "", 13, Ui.TEXT_LO, false));
        basisInner.addView(basisTv);
        basisInner.addView(basisSubTv);
        root.addView(Ui.card(this, basisInner), Ui.lp(this, -1, -2, 0, 0, 0, 12));

        // market card: per-store current prices + trend, source-labeled
        LinearLayout marketInner = new LinearLayout(this);
        marketInner.setOrientation(LinearLayout.VERTICAL);
        marketInner.addView(Ui.text(this, "PRICES AROUND THE WEB", 13, Ui.TEXT_LO, true));
        marketTv = Ui.text(this, "", 15, Ui.TEXT_HI, false);
        marketTv.setLineSpacing(0, 1.3f);
        marketInner.addView(marketTv, Ui.lp(this, -1, -2, 0, 6, 0, 0));
        marketCard = Ui.card(this, marketInner);
        marketCard.setVisibility(View.GONE);
        root.addView(marketCard, Ui.lp(this, -1, -2, 0, 0, 0, 12));

        // shelf price + numpad
        TextView shelfLbl = Ui.text(this, "SHELF PRICE (what the store wants)", 13, Ui.TEXT_LO, true);
        root.addView(shelfLbl);
        shelfTv = Ui.center(Ui.text(this, "$0.00", 34, Ui.ACCENT, true));
        root.addView(shelfTv, Ui.lp(this, -1, -2, 0, 2, 0, 6));
        padGrid = buildPad();
        root.addView(padGrid, Ui.lp(this, -1, -2, 0, 0, 0, 12));

        // shipping row
        LinearLayout shipRow = new LinearLayout(this);
        shipRow.setOrientation(LinearLayout.HORIZONTAL);
        shipRow.setGravity(Gravity.CENTER_VERTICAL);
        shipRow.addView(Ui.text(this, "Shipping you'll pay:  ", 15, Ui.TEXT_LO, false));
        shipEt = new EditText(this);
        shipEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        shipEt.setText(String.format(Locale.US, "%.2f", store.shipDefault()));
        shipEt.setTextColor(Ui.TEXT_HI);
        shipEt.setTextSize(17);
        shipEt.setBackgroundColor(Ui.CARD);
        shipEt.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        shipRow.addView(shipEt, new LinearLayout.LayoutParams(Ui.dp(this, 110), -2));
        root.addView(shipRow, Ui.lp(this, -1, -2, 0, 0, 0, 12));
        shipEt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { recompute(); }
            public void afterTextChanged(Editable s) {}
        });

        // store picker
        root.addView(Ui.text(this, "STORE", 13, Ui.TEXT_LO, true),
                Ui.lp(this, -1, -2, 0, 0, 0, 6));
        storeRow = new LinearLayout(this);
        storeRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(storeRow, Ui.lp(this, -1, -2, 0, 0, 0, 12));
        refreshStoreChips();

        // breakdown + profit + verdict
        LinearLayout mathInner = new LinearLayout(this);
        mathInner.setOrientation(LinearLayout.VERTICAL);
        breakdownTv = Ui.text(this, "", 16, Ui.TEXT_HI, false);
        breakdownTv.setLineSpacing(0, 1.25f);
        profitTv = Ui.text(this, "", 22, Ui.TEXT_HI, true);
        mathInner.addView(breakdownTv);
        mathInner.addView(profitTv, Ui.lp(this, -1, -2, 0, 8, 0, 0));
        root.addView(Ui.card(this, mathInner), Ui.lp(this, -1, -2, 0, 0, 0, 12));

        verdictTv = Ui.center(Ui.text(this, "…", 44, Ui.TEXT_LO, true));
        verdictSubTv = Ui.center(Ui.text(this, "", 14, Ui.TEXT_LO, false));
        root.addView(verdictTv);
        root.addView(verdictSubTv, Ui.lp(this, -1, -2, 0, 2, 0, 16));

        boughtBtn = Ui.button(this, "Mark as BOUGHT", Ui.CARD, Ui.TEXT_HI, 18);
        boughtBtn.setOnClickListener(v -> {
            bought = !bought;
            refreshBoughtBtn();
            saveHaul();
        });
        root.addView(boughtBtn, Ui.lp(this, -1, -2, 0, 0, 0, 10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button next = Ui.button(this, "Save + scan next", Ui.ACCENT, 0xFF0B0E11, 17);
        next.setOnClickListener(v -> {
            saveHaul();
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
            } else {
                finish();
            }
        });
        actions.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = Ui.button(this, "Done", Ui.CARD, Ui.TEXT_HI, 17);
        done.setOnClickListener(v -> { saveHaul(); finish(); });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, -2, 1);
        dlp.leftMargin = Ui.dp(this, 10);
        actions.addView(done, dlp);
        root.addView(actions);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private LinearLayout buildPad() {
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫"};
        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        for (String k : keys) {
            Button b = Ui.button(this, k, Ui.CARD, Ui.TEXT_HI, 22);
            b.setOnClickListener(v -> padPress(k));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0;
            p.height = Ui.dp(this, 56);
            p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            p.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
            g.addView(b, p);
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(g, new LinearLayout.LayoutParams(-1, -2));
        Button clear = Ui.button(this, "Clear price", Ui.CARD, Ui.TEXT_LO, 13);
        clear.setOnClickListener(v -> { shelfStr = ""; shelfPrefilled = false; refreshShelf(); });
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(clear, Ui.lp(this, -1, -2, 0, 4, 0, 0));
        return wrap;
    }

    private void padPress(String k) {
        shelfPrefilled = false;
        if (k.equals("⌫")) {
            if (!shelfStr.isEmpty()) shelfStr = shelfStr.substring(0, shelfStr.length() - 1);
        } else if (k.equals(".")) {
            if (!shelfStr.contains(".")) shelfStr += shelfStr.isEmpty() ? "0." : ".";
        } else {
            int dot = shelfStr.indexOf('.');
            if (dot >= 0 && shelfStr.length() - dot > 2) return;  // 2 decimals max
            if (shelfStr.replace(".", "").length() >= 7) return;
            shelfStr += k;
        }
        refreshShelf();
    }

    private void refreshShelf() {
        shelfTv.setText(Ui.money(shelfPrice()));
        recompute();
    }

    private double shelfPrice() {
        try { return shelfStr.isEmpty() ? 0 : Double.parseDouble(shelfStr); }
        catch (Exception e) { return 0; }
    }

    private void refreshStoreChips() {
        storeRow.removeAllViews();
        for (String s : STORES) {
            boolean sel = s.equals(storeName);
            Button b = Ui.button(this, s, sel ? Ui.ACCENT : Ui.CARD,
                    sel ? 0xFF0B0E11 : Ui.TEXT_HI, 14);
            b.setOnClickListener(v -> {
                storeName = s;
                store.setLastStore(s);
                refreshStoreChips();
                saveHaul();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            p.rightMargin = Ui.dp(this, 6);
            storeRow.addView(b, p);
        }
    }

    private void refreshBoughtBtn() {
        boughtBtn.setText(bought ? "✓ BOUGHT (tap to undo)" : "Mark as BOUGHT");
        boughtBtn.setBackgroundColor(0); // reset; recolor below via new drawable
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(bought ? Ui.ACCENT : Ui.CARD);
        bg.setCornerRadius(Ui.dp(this, 12));
        boughtBtn.setBackground(bg);
        boughtBtn.setTextColor(bought ? 0xFF0B0E11 : Ui.TEXT_HI);
    }

    // ------------------------------------------------------------ data flow

    private void onLookupDone(JSONObject r) {
        result = r;
        renderIdentity();
        if (!"sold".equals(r.optString("dataKind"))) {
            // last resort: WebView fetch of the eBay sold page (real Chromium
            // fingerprint — often passes where plain HTTP is bot-walled)
            statusTv.setText("Direct price sources came up short — trying one more route…");
            String q = !upc.isEmpty() ? upc : titleQuery;
            String url = "https://www.ebay.com/sch/i.html?_nkw="
                    + urlEnc(q) + "&LH_Sold=1&LH_Complete=1&_ipg=120&_sop=13";
            WebFetch.fetch(this, url, new WebFetch.Callback() {
                public void onHtml(String html) {
                    try {
                        if (Lookup.looksBlocked(html)) { onWebFail(); return; }
                        List<Lookup.Item> items = Lookup.parseEbay(html, true);
                        if (items == null || items.isEmpty()) { onWebFail(); return; }
                        JSONObject sold = statsJson(items);
                        result.put("sold", sold);
                        result.put("dataKind", "sold");
                        result.put("soldViaWebView", true);
                        cacheResult();
                        renderIdentity();
                        Toast.makeText(VerdictActivity.this,
                                "Got sold prices", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { onWebFail(); }
                }
                public void onFail(String why) { onWebFail(); }
            });
        }
        recompute();
        saveHaul();
    }

    private void onWebFail() {
        renderIdentity();
        recompute();
        saveHaul();
    }

    private JSONObject statsJson(List<Lookup.Item> items) throws Exception {
        java.util.List<Double> prices = new java.util.ArrayList<>();
        for (Lookup.Item it : items) prices.add(it.price);
        java.util.Collections.sort(prices);
        JSONObject s = new JSONObject();
        s.put("count", prices.size());
        s.put("median", Lookup.median(prices));
        s.put("min", prices.get(0));
        s.put("max", prices.get(prices.size() - 1));
        s.put("source", "eBay sold listings (in-app browser)");
        return s;
    }

    private void cacheResult() {
        try {
            String key = !upc.isEmpty() ? "upc:" + Lookup.normalizeUpc(upc)
                    : "title:" + titleQuery.toLowerCase();
            store.cachePut(key, result.toString());
        } catch (Exception ignored) {}
    }

    private void renderIdentity() {
        if (result == null) return;
        String title = result.optString("title");
        if (!title.isEmpty()) titleTv.setText(title);
        StringBuilder meta = new StringBuilder();
        if (!result.optString("brand").isEmpty()) meta.append(result.optString("brand")).append(" · ");
        if (!upc.isEmpty()) meta.append("UPC ").append(upc);
        if (!result.optString("identitySource").isEmpty())
            meta.append(" · ID: ").append(result.optString("identitySource"));
        if (result.optBoolean("fromCache")) meta.append(" · cached");
        metaTv.setText(meta.toString());

        String img = result.optString("image");
        if (!img.isEmpty()) loadPhoto(img);

        // basis display + shelf prefill from asking prices (per spec)
        JSONObject sold = result.optJSONObject("sold");
        String kind = result.optString("dataKind");
        if (sold != null && sold.optInt("count") > 0) {
            basisTv.setText(Ui.money(sold.optDouble("median")));
            basisSubTv.setText("Typical resale (median of " + sold.optInt("count")
                    + " sold · range " + Ui.money(sold.optDouble("min")) + "–"
                    + Ui.money(sold.optDouble("max")) + ")\n" + sold.optString("source")
                    + (result.optBoolean("soldViaTitle") ? " · matched by title" : ""));
            statusTv.setText("");
        } else if ("active".equals(kind)) {
            basisTv.setText(Ui.money(result.optDouble("activeLowest")));
            basisSubTv.setText("Lowest current asking price on eBay ("
                    + result.optInt("activeCount") + " active) — NO sold data found."
                    + noteSuffix());
            statusTv.setText("⚠ No sold-price data — verdict is capped at MAYBE.");
            statusTv.setTextColor(Ui.WARN);
        } else if ("asking".equals(kind)) {
            JSONObject a = result.optJSONObject("asking");
            basisTv.setText(Ui.money(a.optDouble("median")));
            basisSubTv.setText("Typical asking price online (median of " + a.optInt("count")
                    + " offers · " + a.optString("source") + ") — NO sold data found."
                    + noteSuffix());
            statusTv.setText("⚠ No sold-price data — verdict is capped at MAYBE.");
            statusTv.setTextColor(Ui.WARN);
        } else {
            basisTv.setText("—");
            basisSubTv.setText("No price data found for this item." + noteSuffix());
            statusTv.setText("No data — can't price this one.");
            statusTv.setTextColor(Ui.BAD);
        }

        renderMarket();

        if (!shelfPrefilled && shelfStr.isEmpty()) {
            JSONObject a = result.optJSONObject("asking");
            if (a != null && a.optDouble("min") > 0) {
                shelfStr = String.format(Locale.US, "%.2f", a.optDouble("min"));
                shelfPrefilled = true;
                shelfTv.setText(Ui.money(shelfPrice()));
            }
        }
    }

    /** Per-store current prices + trend, each line naming its source. */
    private void renderMarket() {
        if (result == null || marketTv == null) return;
        StringBuilder sb = new StringBuilder();
        org.json.JSONArray sp = result.optJSONArray("storePrices");
        if (sp != null) {
            for (int i = 0; i < sp.length() && i < 8; i++) {
                org.json.JSONObject o = sp.optJSONObject(i);
                if (o == null) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append(o.optString("store"));
                String city = o.optString("city");
                if (!city.isEmpty()) sb.append(" (").append(city).append(")");
                sb.append(" — ").append(Ui.money(o.optDouble("price")));
                sb.append("  · ").append(o.optString("source"));
                String when = o.optString("date");
                if (when.isEmpty()) when = o.optString("updated");
                if (!when.isEmpty()) sb.append(" · ").append(when);
            }
        }
        JSONObject t = result.optJSONObject("trend");
        if (t != null && !t.optString("signal").isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            String sig = t.optString("signal");
            String arrow = "rising".equals(sig) ? "↑" : "falling".equals(sig) ? "↓" : "→";
            sb.append("Trend ").append(arrow).append(" ").append(sig).append(": ")
              .append(t.optString("detail"));
        }
        if (sb.length() == 0) {
            marketCard.setVisibility(View.GONE);
        } else {
            marketTv.setText(sb.toString());
            marketCard.setVisibility(View.VISIBLE);
        }
    }

    private String noteSuffix() {
        String n = result == null ? "" : result.optString("ebayNote");
        return n.isEmpty() ? "" : "\n(eBay: " + n + ")";
    }

    private void loadPhoto(String url) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                InputStream in = c.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                if (bmp != null) runOnUiThread(() -> photoIv.setImageBitmap(bmp));
            } catch (Exception ignored) {}
        }).start();
    }

    // ------------------------------------------------------------ math

    private Double basisPrice() {
        if (result == null) return null;
        JSONObject sold = result.optJSONObject("sold");
        if (sold != null && sold.optInt("count") > 0) return sold.optDouble("median");
        if (result.has("activeLowest")) return result.optDouble("activeLowest");
        JSONObject a = result.optJSONObject("asking");
        if (a != null) return a.optDouble("median");
        return null;
    }

    private double shipCost() {
        try { return Double.parseDouble(shipEt.getText().toString()); }
        catch (Exception e) { return store.shipDefault(); }
    }

    private void recompute() {
        if (result == null) return;
        Double basis = basisPrice();
        double shelf = shelfPrice();
        double ship = shipCost();
        if (basis == null) {
            breakdownTv.setText("No resale price to work from.");
            profitTv.setText("");
            verdictTv.setText("NO DATA");
            verdictTv.setTextColor(Ui.TEXT_LO);
            verdictSubTv.setText("Couldn't find this item selling anywhere we can see.");
            return;
        }
        double fees = basis * store.feePct() / 100.0 + store.feeFixed();
        double profit = basis - fees - ship - shelf;
        breakdownTv.setText(
                "Sells for:            " + Ui.money(basis) + "\n"
                + "eBay fees:          − " + Ui.money(fees) + "  ("
                + String.format(Locale.US, "%.2f%%", store.feePct())
                + " + " + Ui.money(store.feeFixed()) + ")\n"
                + "Shipping:           − " + Ui.money(ship) + "\n"
                + "Your shelf price: − " + Ui.money(shelf));
        profitTv.setText("= NET PROFIT  " + Ui.money(profit));
        profitTv.setTextColor(profit > 0 ? Ui.ACCENT : Ui.BAD);

        boolean soldBasis = "sold".equals(result.optString("dataKind"));
        if (shelf <= 0) {
            verdictTv.setText("ENTER PRICE");
            verdictTv.setTextColor(Ui.TEXT_LO);
            verdictSubTv.setText("Type the shelf price on the pad above.");
        } else if (profit <= 0) {
            verdictTv.setText("SKIP");
            verdictTv.setTextColor(Ui.BAD);
            verdictSubTv.setText("You'd lose " + Ui.money(-profit) + " on this one.");
        } else if (soldBasis && profit >= 5 && profit >= 0.25 * basis) {
            verdictTv.setText("BUY");
            verdictTv.setTextColor(Ui.ACCENT);
            verdictSubTv.setText("Solid margin on confirmed sold prices.");
        } else {
            verdictTv.setText("MAYBE");
            verdictTv.setTextColor(Ui.WARN);
            verdictSubTv.setText(soldBasis
                    ? "Profit is thin — only if it moves fast."
                    : "Profitable on asking prices, but sold prices aren't confirmed.");
        }
    }

    private String verdictWord() {
        return verdictTv.getText().toString();
    }

    // ------------------------------------------------------------ saving

    private void saveHaul() {
        if (result == null) return;
        try {
            Double basis = basisPrice();
            double shelf = shelfPrice();
            double ship = shipCost();
            double fees = basis == null ? 0 : basis * store.feePct() / 100.0 + store.feeFixed();
            double profit = basis == null ? 0 : basis - fees - ship - shelf;
            JSONObject e = new JSONObject();
            if (haulId > 0) e.put("id", haulId);
            e.put("ts", System.currentTimeMillis());
            e.put("date", new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)
                    .format(new Date()));
            e.put("upc", upc);
            e.put("title", result.optString("title"));
            e.put("store", storeName);
            e.put("shelf", shelf);
            e.put("resale", basis == null ? 0 : basis);
            e.put("fees", fees);
            e.put("ship", ship);
            e.put("profit", profit);
            e.put("verdict", verdictWord());
            e.put("dataKind", result.optString("dataKind"));
            e.put("bought", bought);
            haulId = store.upsertHaul(e);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveHaul();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SCAN && res == RESULT_OK && data != null) {
            String next = data.getStringExtra(ScanActivity.EXTRA_UPC);
            if (next != null && !next.isEmpty()) {
                Intent i = new Intent(this, VerdictActivity.class);
                i.putExtra("upc", next);
                startActivity(i);
                finish();
            }
        }
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
