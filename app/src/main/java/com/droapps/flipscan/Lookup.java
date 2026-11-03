package com.droapps.flipscan;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Price lookup pipeline. Everything is labeled by source; nothing is
 * fabricated — a source that fails simply contributes nothing and the
 * result records what happened.
 *
 * Order for a UPC:
 *   1. on-device cache (< 6h)
 *   2. identity: UPCitemdb lookup -> Open Food Facts fallback
 *   3. eBay SOLD stats: FlipScan backend -> direct-from-phone fetch
 *   4. eBay ACTIVE lowest: same two paths
 *   5. if sold-by-UPC came back a clean zero and we know the title,
 *      a labeled title search for sold stats
 *   6. asking prices from UPCitemdb offers are the last-resort basis
 */
public class Lookup {

    public interface Callback { void onDone(JSONObject result); }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S938U) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final long CACHE_MS = 6 * 3600 * 1000L;

    private final Store store;

    public Lookup(Context c) { store = new Store(c); }

    public void run(String upc, String titleQuery, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            JSONObject r;
            try {
                r = (upc != null && !upc.isEmpty())
                        ? lookupUpc(normalizeUpc(upc))
                        : lookupTitle(titleQuery == null ? "" : titleQuery.trim());
            } catch (Exception e) {
                r = new JSONObject();
                try { r.put("dataKind", "none"); r.put("error", "" + e); }
                catch (Exception ignored) {}
            }
            JSONObject out = r;
            main.post(() -> cb.onDone(out));
        }).start();
    }

    /** EAN-13 with a leading 0 is a UPC-A in disguise. */
    static String normalizeUpc(String raw) {
        String d = raw.replaceAll("[^0-9]", "");
        if (d.length() == 13 && d.startsWith("0")) return d.substring(1);
        return d;
    }

    // ------------------------------------------------------------ UPC flow

    private JSONObject lookupUpc(String upc) throws Exception {
        String key = "upc:" + upc;
        String cached = store.cacheGet(key, CACHE_MS);
        if (cached != null) {
            JSONObject r = new JSONObject(cached);
            r.put("fromCache", true);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("mode", "upc");
        r.put("upc", upc);

        JSONObject identity = identityByUpc(upc);
        if (identity != null) {
            r.put("title", identity.optString("title"));
            r.put("brand", identity.optString("brand"));
            r.put("image", identity.optString("image"));
            r.put("identitySource", identity.optString("source"));
            if (identity.has("asking")) r.put("asking", identity.getJSONObject("asking"));
        } else {
            r.put("title", "");
            r.put("identitySource", "");
        }

        fillEbay(r, upc, upc);
        finishKind(r);
        store.cachePut(key, r.toString());
        return r;
    }

    // ------------------------------------------------------------ title flow

    private JSONObject lookupTitle(String q) throws Exception {
        JSONObject r = new JSONObject();
        r.put("mode", "title");
        r.put("upc", "");
        r.put("titleQuery", q);
        String key = "title:" + q.toLowerCase();
        String cached = store.cacheGet(key, CACHE_MS);
        if (cached != null) {
            JSONObject c = new JSONObject(cached);
            c.put("fromCache", true);
            return c;
        }
        r.put("title", q);
        r.put("identitySource", "your typed search");
        fillEbay(r, null, q);
        // identity upgrade: use the first sold item's title as the product name
        if (r.has("soldItems")) {
            JSONArray items = r.getJSONArray("soldItems");
            if (items.length() > 0) {
                r.put("title", items.getJSONObject(0).optString("title"));
                r.put("identitySource", "eBay listings");
            }
        }
        finishKind(r);
        store.cachePut(key, r.toString());
        return r;
    }

    // ------------------------------------------------------------ identity

    private JSONObject identityByUpc(String upc) {
        // UPCitemdb (keyless trial endpoint)
        try {
            String body = httpGet("https://api.upcitemdb.com/prod/trial/lookup?upc="
                    + URLEncoder.encode(upc, "UTF-8"), 15000);
            JSONObject d = new JSONObject(body);
            if ("OK".equals(d.optString("code")) && d.optInt("total") > 0) {
                JSONObject it = d.getJSONArray("items").getJSONObject(0);
                JSONObject out = new JSONObject();
                out.put("title", it.optString("title"));
                out.put("brand", it.optString("brand"));
                JSONArray imgs = it.optJSONArray("images");
                out.put("image", imgs != null && imgs.length() > 0 ? imgs.getString(0) : "");
                out.put("source", "UPCitemdb");
                // asking prices from offers (USD only)
                JSONArray offers = it.optJSONArray("offers");
                if (offers != null) {
                    List<Double> usd = new ArrayList<>();
                    for (int i = 0; i < offers.length(); i++) {
                        JSONObject o = offers.getJSONObject(i);
                        String cur = o.optString("currency", "");
                        double p = o.optDouble("price", 0);
                        if (p > 0 && (cur.isEmpty() || "USD".equals(cur))) usd.add(p);
                    }
                    if (!usd.isEmpty()) {
                        Collections.sort(usd);
                        JSONObject a = new JSONObject();
                        a.put("min", usd.get(0));
                        a.put("median", median(usd));
                        a.put("count", usd.size());
                        a.put("source", "UPCitemdb offers");
                        out.put("asking", a);
                    }
                }
                return out;
            }
        } catch (Exception ignored) {}
        // Open Food Facts fallback (great for grocery, thin elsewhere)
        try {
            String ean = upc.length() == 12 ? "0" + upc : upc;
            String body = httpGet("https://world.openfoodfacts.org/api/v2/product/"
                    + ean + ".json", 12000);
            JSONObject d = new JSONObject(body);
            if (d.optInt("status") == 1) {
                JSONObject p = d.getJSONObject("product");
                JSONObject out = new JSONObject();
                out.put("title", p.optString("product_name"));
                out.put("brand", p.optString("brands"));
                String img = p.optString("image_front_url");
                if (img.isEmpty()) img = p.optString("image_url");
                out.put("image", img);
                out.put("source", "Open Food Facts");
                return out;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ------------------------------------------------------------ eBay

    /** Fills sold/active fields using backend first, phone-direct second. */
    private void fillEbay(JSONObject r, String upc, String ebayQuery) throws Exception {
        String backend = store.backendUrl().replaceAll("/+$", "");
        String qParam = upc != null ? "upc=" + URLEncoder.encode(upc, "UTF-8")
                                    : "q=" + URLEncoder.encode(ebayQuery, "UTF-8");

        JSONObject sold = null;
        String note = "";

        // --- sold via backend
        try {
            String body = httpGet(backend + "/ebay/sold?" + qParam, 50000);
            JSONObject d = new JSONObject(body);
            if (d.optBoolean("ok")) {
                sold = statsFromBackend(d, "FlipScan server · eBay sold listings");
            } else if (d.optBoolean("blocked")) {
                note = "server blocked by eBay";
            }
        } catch (Exception e) {
            note = "server unreachable (" + shortErr(e) + ")";
        }

        // --- sold direct from phone
        if (sold == null) {
            try {
                List<Item> items = directEbay(ebayQuery, true);
                if (items != null) {
                    sold = statsFromItems(items, "eBay sold listings (direct)");
                    r.put("soldItems", itemsJson(items));
                }
            } catch (BlockedException b) {
                note += (note.isEmpty() ? "" : "; ") + "phone blocked by eBay";
            } catch (Exception e) {
                note += (note.isEmpty() ? "" : "; ") + "direct failed (" + shortErr(e) + ")";
            }
        }

        // --- title retry: clean zero on a UPC search, identity known
        if (sold != null && sold.optInt("count") == 0 && upc != null
                && !r.optString("title").isEmpty()) {
            String tq = titleWords(r.optString("brand"), r.optString("title"));
            if (!tq.isEmpty()) {
                JSONObject tSold = null;
                try {
                    String body = httpGet(backend + "/ebay/sold?q="
                            + URLEncoder.encode(tq, "UTF-8"), 50000);
                    JSONObject d = new JSONObject(body);
                    if (d.optBoolean("ok")) tSold = statsFromBackend(d, "eBay sold · title search");
                } catch (Exception ignored) {}
                if (tSold == null) {
                    try {
                        List<Item> items = directEbay(tq, true);
                        if (items != null) {
                            tSold = statsFromItems(items, "eBay sold · title search (direct)");
                            r.put("soldItems", itemsJson(items));
                        }
                    } catch (Exception ignored) {}
                }
                if (tSold != null && tSold.optInt("count") > 0) {
                    sold = tSold;
                    r.put("soldViaTitle", true);
                }
            }
        }

        if (sold != null) r.put("sold", sold);
        if (!note.isEmpty()) r.put("ebayNote", note);

        // --- active lowest (only if no sold basis; keeps lookups fast)
        if (sold == null || sold.optInt("count") == 0) {
            Double low = null;
            int activeCount = 0;
            try {
                String body = httpGet(backend + "/ebay/active?" + qParam, 50000);
                JSONObject d = new JSONObject(body);
                if (d.optBoolean("ok")) {
                    JSONArray items = d.optJSONArray("items");
                    if (items != null) {
                        activeCount = items.length();
                        for (int i = 0; i < items.length(); i++) {
                            double p = items.getJSONObject(i).optDouble("price", -1);
                            if (p > 0 && (low == null || p < low)) low = p;
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (low == null) {
                try {
                    List<Item> items = directEbay(ebayQuery, false);
                    if (items != null && !items.isEmpty()) {
                        activeCount = items.size();
                        for (Item it : items) if (low == null || it.price < low) low = it.price;
                    }
                } catch (Exception ignored) {}
            }
            if (low != null) {
                r.put("activeLowest", low);
                r.put("activeCount", activeCount);
            }
        }
    }

    private void finishKind(JSONObject r) throws Exception {
        JSONObject sold = r.optJSONObject("sold");
        if (sold != null && sold.optInt("count") > 0) r.put("dataKind", "sold");
        else if (r.has("activeLowest")) r.put("dataKind", "active");
        else if (r.has("asking")) r.put("dataKind", "asking");
        else r.put("dataKind", "none");
    }

    // ------------------------------------------------------------ eBay direct

    static class Item { String title; double price; String date; }
    static class BlockedException extends Exception {}

    private static final Pattern ITEM_SPLIT = Pattern.compile("class=\"s-item[ \"]");
    private static final Pattern TITLE_RE =
            Pattern.compile("class=\"s-item__title\"[^>]*>(.*?)</", Pattern.DOTALL);
    private static final Pattern PRICE_SPAN =
            Pattern.compile("s-item__price[^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern PRICE_RE =
            Pattern.compile("\\$\\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})|[0-9]+(?:\\.[0-9]{2})?)");
    private static final Pattern DATE_RE =
            Pattern.compile("Sold\\s+([A-Z][a-z]{2}\\s+\\d{1,2},\\s+\\d{4})");

    /** Returns null on HTTP error, throws BlockedException on bot wall. */
    static List<Item> directEbay(String query, boolean sold) throws Exception {
        String url = "https://www.ebay.com/sch/i.html?_nkw="
                + URLEncoder.encode(query, "UTF-8")
                + (sold ? "&LH_Sold=1&LH_Complete=1&_ipg=120&_sop=13"
                        : "&_ipg=120&_sop=15");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        int code = c.getResponseCode();
        String body = readAll(c, code);
        if (code == 401 || code == 403 || code == 429 || looksBlocked(body)) {
            throw new BlockedException();
        }
        if (code != 200) return null;
        return parseEbay(body, sold);
    }

    static boolean looksBlocked(String body) {
        if (body == null) return false;
        String low = body.toLowerCase();
        return low.contains("pardon our interruption")
                || low.contains("security measure")
                || (low.contains("captcha") && !low.contains("s-item"));
    }

    static List<Item> parseEbay(String html, boolean wantDate) {
        List<Item> out = new ArrayList<>();
        String[] parts = ITEM_SPLIT.split(html);
        for (int i = 1; i < parts.length; i++) {
            String block = parts[i];
            Matcher tm = TITLE_RE.matcher(block);
            String title = "";
            if (tm.find()) title = stripTags(tm.group(1)).trim();
            if (title.isEmpty() || title.toLowerCase().startsWith("shop on ebay")) continue;
            double price = -1;
            Matcher pm = PRICE_SPAN.matcher(block);
            String seg = pm.find() ? pm.group(1) : block.substring(0, Math.min(4000, block.length()));
            Matcher nm = PRICE_RE.matcher(seg);
            if (nm.find()) {
                try { price = Double.parseDouble(nm.group(1).replace(",", "")); }
                catch (Exception ignored) {}
            }
            if (price <= 0) continue;
            Item it = new Item();
            it.title = title.length() > 120 ? title.substring(0, 120) : title;
            it.price = price;
            if (wantDate) {
                Matcher dm = DATE_RE.matcher(block);
                it.date = dm.find() ? dm.group(1) : null;
            }
            out.add(it);
        }
        return out;
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    // ------------------------------------------------------------ stats

    static double median(List<Double> sorted) {
        int n = sorted.size();
        if (n == 0) return 0;
        return n % 2 == 1 ? sorted.get(n / 2)
                          : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private JSONObject statsFromBackend(JSONObject d, String sourceLabel) throws Exception {
        JSONObject s = new JSONObject();
        s.put("count", d.optInt("count"));
        s.put("median", d.optDouble("median"));
        s.put("min", d.optDouble("min"));
        s.put("max", d.optDouble("max"));
        s.put("source", sourceLabel);
        if (d.has("items")) {
            JSONArray items = d.getJSONArray("items");
            JSONArray slim = new JSONArray();
            for (int i = 0; i < items.length() && i < 30; i++) {
                JSONObject it = items.getJSONObject(i);
                JSONObject o = new JSONObject();
                o.put("title", it.optString("title"));
                o.put("price", it.optDouble("price"));
                o.put("date", it.optString("date"));
                slim.put(o);
            }
            s.put("sampleItems", slim);
        }
        return s;
    }

    private JSONObject statsFromItems(List<Item> items, String sourceLabel) throws Exception {
        List<Double> prices = new ArrayList<>();
        for (Item it : items) prices.add(it.price);
        Collections.sort(prices);
        JSONObject s = new JSONObject();
        s.put("count", prices.size());
        if (!prices.isEmpty()) {
            s.put("median", median(prices));
            s.put("min", prices.get(0));
            s.put("max", prices.get(prices.size() - 1));
        }
        s.put("source", sourceLabel);
        return s;
    }

    private JSONArray itemsJson(List<Item> items) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < items.size() && i < 30; i++) {
            JSONObject o = new JSONObject();
            o.put("title", items.get(i).title);
            o.put("price", items.get(i).price);
            o.put("date", items.get(i).date);
            a.put(o);
        }
        return a;
    }

    /** "Brand word1 word2 word3 word4" — a tight title query for eBay. */
    static String titleWords(String brand, String title) {
        String t = (title == null ? "" : title).replaceAll("[^A-Za-z0-9 ]", " ").trim();
        String[] words = t.split("\\s+");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String w : words) {
            if (w.length() < 2) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
            if (++n >= 5) break;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ HTTP

    static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(Math.min(timeoutMs, 15000));
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json,text/html,*/*");
        int code = c.getResponseCode();
        String body = readAll(c, code);
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return body;
    }

    private static String readAll(HttpURLConnection c, int code) throws Exception {
        java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
        br.close();
        return sb.toString();
    }

    private static String shortErr(Exception e) {
        String m = "" + e;
        return m.length() > 40 ? m.substring(0, 40) : m;
    }
}
