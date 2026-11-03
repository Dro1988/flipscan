package com.droapps.flipscan;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Last-resort page fetch through a real (off-screen) WebView. eBay's bot
 * wall keys on TLS/client fingerprints; a genuine Chromium WebView on the
 * phone stands a much better chance than a raw HTTP client. Used only when
 * the plain-HTTP paths were blocked. Strictly bounded: one page, ~30s.
 */
public class WebFetch {

    public interface Callback {
        void onHtml(String html);
        void onFail(String why);
    }

    public static void fetch(Activity act, String url, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        WebView wv = new WebView(act);
        // zero-size, never attached visibly; still renders + runs JS
        act.addContentView(wv, new ViewGroup.LayoutParams(1, 1));
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        wv.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; SM-S938U) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");
        final boolean[] done = {false};
        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            try { wv.stopLoading(); wv.destroy(); } catch (Exception ignored) {}
        };
        main.postDelayed(() -> {
            if (!done[0]) { finish.run(); cb.onFail("webview timeout"); }
        }, 30000);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                // give JS-driven content a beat to settle, then grab the DOM
                main.postDelayed(() -> {
                    if (done[0]) return;
                    view.evaluateJavascript(
                            "document.documentElement.outerHTML",
                            (ValueCallback<String>) value -> {
                                if (done[0]) return;
                                finish.run();
                                if (value == null || value.length() < 50) {
                                    cb.onFail("empty page");
                                    return;
                                }
                                cb.onHtml(unescapeJs(value));
                            });
                }, 2500);
            }
            @Override
            public void onReceivedError(WebView view,
                    android.webkit.WebResourceRequest req,
                    android.webkit.WebResourceError err) {
                if (req.isForMainFrame() && !done[0]) {
                    finish.run();
                    cb.onFail("webview error: " + err.getDescription());
                }
            }
        });
        wv.loadUrl(url);
    }

    /** evaluateJavascript returns a JSON-encoded string. */
    private static String unescapeJs(String v) {
        try {
            return new org.json.JSONTokener(v).nextValue().toString();
        } catch (Exception e) {
            return v;
        }
    }
}
