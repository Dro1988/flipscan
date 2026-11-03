package com.droapps.flipscan;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.util.Locale;

public class SettingsActivity extends Activity {

    private EditText feePctEt, feeFixedEt, shipEt, backendEt;
    private Store store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 18));

        root.addView(Ui.text(this, "Settings", 26, Ui.TEXT_HI, true),
                Ui.lp(this, -1, -2, 0, 0, 0, 16));

        feePctEt = field(root, "eBay fee % (default 13.25)",
                String.format(Locale.US, "%.2f", store.feePct()));
        feeFixedEt = field(root, "eBay per-order fee $ (default 0.40)",
                String.format(Locale.US, "%.2f", store.feeFixed()));
        shipEt = field(root, "Default shipping estimate $ (default 6.00)",
                String.format(Locale.US, "%.2f", store.shipDefault()));
        backendEt = textField(root, "Price server URL", store.backendUrl());

        Button save = Ui.button(this, "Save", Ui.ACCENT, 0xFF0B0E11, 18);
        save.setOnClickListener(v -> {
            try {
                double pct = Double.parseDouble(feePctEt.getText().toString());
                double fix = Double.parseDouble(feeFixedEt.getText().toString());
                double ship = Double.parseDouble(shipEt.getText().toString());
                String backend = backendEt.getText().toString().trim();
                if (backend.isEmpty()) backend = Store.DEFAULT_BACKEND;
                store.saveSettings(pct, fix, ship, backend);
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                finish();
            } catch (Exception e) {
                Toast.makeText(this, "Check the numbers and try again",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(save, Ui.lp(this, -1, -2, 0, 10, 0, 0));

        Button reset = Ui.button(this, "Reset price server to default", Ui.CARD, Ui.TEXT_LO, 13);
        reset.setOnClickListener(v -> backendEt.setText(Store.DEFAULT_BACKEND));
        root.addView(reset, Ui.lp(this, -1, -2, 0, 10, 0, 0));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private EditText field(LinearLayout root, String label, String value) {
        root.addView(Ui.text(this, label, 14, Ui.TEXT_LO, false),
                Ui.lp(this, -1, -2, 0, 8, 0, 4));
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(value);
        et.setTextColor(Ui.TEXT_HI);
        et.setTextSize(17);
        et.setBackgroundColor(Ui.CARD);
        et.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        root.addView(et, Ui.lp(this, -1, -2, 0, 0, 0, 6));
        return et;
    }

    private EditText textField(LinearLayout root, String label, String value) {
        EditText et = field(root, label, value);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return et;
    }
}
