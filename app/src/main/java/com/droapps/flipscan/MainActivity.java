package com.droapps.flipscan;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final int REQ_SCAN = 41;
    private static final int REQ_CAMERA_PERM = 42;
    private TextView statsLine;
    private Store store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        warmBackend();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 20), Ui.dp(this, 20));

        TextView title = Ui.text(this, "FlipScan", 34, Ui.ACCENT, true);
        root.addView(title);
        TextView sub = Ui.text(this,
                "Scan it. See what it sells for. Buy it or skip it.",
                15, Ui.TEXT_LO, false);
        root.addView(sub, Ui.lp(this, -1, -2, 0, 4, 0, 20));

        Button scan = Ui.button(this, "📷  SCAN BARCODE", Ui.ACCENT, 0xFF0B0E11, 22);
        scan.setOnClickListener(v -> startScan());
        root.addView(scan, Ui.lp(this, -1, Ui.dp(this, 72), 0, 0, 0, 18));

        TextView orLbl = Ui.center(Ui.text(this, "— or type it —", 13, Ui.TEXT_LO, false));
        root.addView(orLbl, Ui.lp(this, -1, -2, 0, 2, 0, 8));

        EditText entry = new EditText(this);
        entry.setHint("UPC number, or a product name (damaged barcode)");
        entry.setHintTextColor(Ui.TEXT_LO);
        entry.setTextColor(Ui.TEXT_HI);
        entry.setTextSize(17);
        entry.setSingleLine(true);
        entry.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        entry.setBackgroundColor(Ui.CARD);
        entry.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        root.addView(entry, Ui.lp(this, -1, -2, 0, 0, 0, 10));

        Button go = Ui.button(this, "Look it up", Ui.CARD, Ui.TEXT_HI, 17);
        go.setOnClickListener(v -> {
            String q = entry.getText().toString().trim();
            if (q.isEmpty()) {
                Toast.makeText(this, "Type a UPC or product name first",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            openVerdict(q);
        });
        root.addView(go, Ui.lp(this, -1, -2, 0, 0, 0, 22));
        entry.setOnEditorActionListener((v, id, ev) -> { go.performClick(); return true; });

        Button haul = Ui.button(this, "🧾  Haul list", Ui.CARD, Ui.TEXT_HI, 17);
        haul.setOnClickListener(v ->
                startActivity(new Intent(this, HaulActivity.class)));
        root.addView(haul, Ui.lp(this, -1, -2, 0, 0, 0, 10));

        Button settings = Ui.button(this, "⚙  Settings (fees, shipping)", Ui.CARD, Ui.TEXT_HI, 17);
        settings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(settings, Ui.lp(this, -1, -2, 0, 0, 0, 22));

        statsLine = Ui.text(this, "", 14, Ui.TEXT_LO, false);
        root.addView(statsLine);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStats();
    }

    private void refreshStats() {
        try {
            JSONArray h = store.haul();
            int bought = 0;
            double profit = 0;
            for (int i = 0; i < h.length(); i++) {
                JSONObject e = h.getJSONObject(i);
                if (e.optBoolean("bought")) {
                    bought++;
                    profit += e.optDouble("profit", 0);
                }
            }
            statsLine.setText(h.length() + " scans saved · " + bought
                    + " bought · est. profit on bought: " + Ui.money(profit));
        } catch (Exception ignored) {}
    }

    private void startScan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA},
                    REQ_CAMERA_PERM);
            return;
        }
        startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_CAMERA_PERM) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                startScan();
            } else {
                Toast.makeText(this, "Camera permission is needed to scan",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SCAN && res == RESULT_OK && data != null) {
            String upc = data.getStringExtra(ScanActivity.EXTRA_UPC);
            if (upc != null && !upc.isEmpty()) openVerdict(upc);
        }
    }

    private void openVerdict(String q) {
        Intent i = new Intent(this, VerdictActivity.class);
        String stripped = q.replaceAll("[\\s-]", "");
        if (stripped.matches("[0-9]{8,14}")) {
            i.putExtra("upc", stripped);
        } else {
            i.putExtra("title", q);
        }
        startActivity(i);
    }

    /** Wake the free-tier price server so a scan doesn't wait on cold start. */
    private void warmBackend() {
        new Thread(() -> {
            try {
                Lookup.httpGet(new Store(this).backendUrl().replaceAll("/+$", "")
                        + "/health", 30000);
            } catch (Exception ignored) {}
        }).start();
    }
}
