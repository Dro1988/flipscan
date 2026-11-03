#!/usr/bin/env python3
"""FlipScan data backend (stdlib only).

Endpoints:
  GET /health
  GET /probe                      -> egress test vs eBay (status + markers)
  GET /ebay/sold?upc=<digits>     -> sold-listing stats (LH_Sold, last ~90d page)
  GET /ebay/active?upc=<digits>   -> lowest current active listings
  Both accept &q=<title words> instead of upc for a title search (labeled).
  GET /lookup?upc=<digits>        -> aggregated market view for one UPC:
        identity (Open Food Facts, UPCitemdb when quota allows),
        per-store current asking prices (UPCitemdb offers + Open Prices
        crowd price records), eBay active range, eBay sold stats, and a
        trend signal computed ONLY from dated data actually returned.

Nothing here fabricates data: every price carries its source name, a
source that fails is reported as failed/missing, and numbers are never
invented to fill a gap.
"""
import json
import os
import re
import statistics
import time
import urllib.request
import urllib.error
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UA = ("Mozilla/5.0 (Linux; Android 14; SM-S938U) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")

CACHE = {}          # key -> (ts, payload)
CACHE_TTL = 6 * 3600
LOOKUP_CACHE_TTL = 12 * 3600

BLOCK_MARKERS = ("pardon our interruption", "security measure",
                 "captcha", "robot check", "access denied")


def fetch(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    })
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            return e.code, e.read().decode("utf-8", "replace")
        except Exception:
            return e.code, ""
    except Exception as e:
        return 0, "FETCH_ERROR: %s" % e


def fetch_json(url, timeout=20):
    req = urllib.request.Request(url, headers={
        "User-Agent": "FlipScan/1.1 (price lookup; contact via droapps)",
        "Accept": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            return e.code, None
    except Exception:
        return 0, None


def is_blocked(status, body):
    if status in (401, 403, 429):
        return True
    low = body.lower()
    return any(m in low for m in BLOCK_MARKERS)


PRICE_RE = re.compile(r"\$\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{2})|[0-9]+(?:\.[0-9]{2})?)")
DATE_RE = re.compile(r"Sold\s+([A-Z][a-z]{2}\s+\d{1,2},\s+\d{4})")
TITLE_RE = re.compile(r'class="s-item__title"[^>]*>(.*?)</', re.S)
ITEM_SPLIT_RE = re.compile(r'class="s-item[ "]')


def parse_items(html, want_date):
    """Parse eBay search-result HTML into [{title, price, date?}].

    The first s-item block is usually a 'Shop on eBay' placeholder and is
    skipped (no price / that title). Range-priced items contribute their
    first (lowest) price.
    """
    parts = ITEM_SPLIT_RE.split(html)
    items = []
    for block in parts[1:]:
        tm = TITLE_RE.search(block)
        title = ""
        if tm:
            title = re.sub(r"<[^>]+>", "", tm.group(1)).strip()
            title = (title.replace("&amp;", "&").replace("&quot;", '"')
                          .replace("&#39;", "'"))
        if not title or title.lower().startswith("shop on ebay"):
            continue
        # price: prefer the s-item__price span
        price = None
        pm = re.search(r's-item__price[^>]*>(.*?)</span>', block, re.S)
        seg = pm.group(1) if pm else block[:4000]
        nums = PRICE_RE.findall(seg)
        if not nums and not pm:
            nums = PRICE_RE.findall(block[:4000])
        if nums:
            try:
                price = float(nums[0].replace(",", ""))
            except ValueError:
                price = None
        if price is None or price <= 0:
            continue
        entry = {"title": title[:120], "price": price}
        if want_date:
            dm = DATE_RE.search(block)
            entry["date"] = dm.group(1) if dm else None
        items.append(entry)
    return items


def stats_payload(items, source, query_desc):
    prices = sorted(i["price"] for i in items)
    out = {
        "ok": True,
        "blocked": False,
        "source": source,
        "query": query_desc,
        "count": len(prices),
        "fetched_at": int(time.time()),
    }
    if prices:
        out["median"] = round(statistics.median(prices), 2)
        out["mean"] = round(statistics.mean(prices), 2)
        out["min"] = prices[0]
        out["max"] = prices[-1]
        if len(prices) >= 4:
            q = statistics.quantiles(prices, n=4)
            out["p25"] = round(q[0], 2)
            out["p75"] = round(q[2], 2)
    out["items"] = items[:60]
    return out


def ebay_search(query, sold):
    if sold:
        url = ("https://www.ebay.com/sch/i.html?_nkw=%s&LH_Sold=1&LH_Complete=1"
               "&_ipg=120&_sop=13" % urllib.parse.quote_plus(query))
    else:
        url = ("https://www.ebay.com/sch/i.html?_nkw=%s&_ipg=120&_sop=15"
               % urllib.parse.quote_plus(query))
    status, body = fetch(url)
    if is_blocked(status, body):
        return {"ok": False, "blocked": True, "source": "ebay",
                "http_status": status,
                "error": "eBay blocked the request (bot wall)"}
    if status != 200:
        return {"ok": False, "blocked": False, "source": "ebay",
                "http_status": status, "error": "eBay HTTP %s" % status}
    items = parse_items(body, want_date=sold)
    return stats_payload(items,
                         "ebay-sold-search" if sold else "ebay-active-search",
                         query)


# ---------------------------------------------------------------- /lookup

def off_identity(ean):
    """Open Food Facts identity (no prices). Returns (identity|None, status).

    Tries the .org host, then the .net mirror: OFF throttles some
    datacenter egress IPs per-host, and the status reports the HTTP
    code when a lookup is refused rather than calling it a miss.
    """
    last = "miss"
    for host in ("world.openfoodfacts.org", "world.openfoodfacts.net"):
        status, d = fetch_json(
            "https://%s/api/v2/product/%s.json" % (host, ean))
        if status == 200 and d and d.get("status") == 1:
            p = d.get("product") or {}
            img = p.get("image_front_url") or p.get("image_url") or ""
            return {
                "title": p.get("product_name") or "",
                "brand": p.get("brands") or "",
                "image": img,
                "source": "Open Food Facts",
            }, "ok"
        if status == 200:
            last = "miss"
        elif status:
            last = "http_%s" % status
        else:
            last = "error"
    return None, last


def upcitemdb_lookup(upc):
    """UPCitemdb trial lookup. Returns (identity|None, offers, status).

    The trial endpoint is a shared keyless quota: when it answers
    EXCEED_LIMIT we report 'rate_limited' honestly and move on.
    """
    status, d = fetch_json(
        "https://api.upcitemdb.com/prod/trial/lookup?upc=%s"
        % urllib.parse.quote(upc))
    if not d:
        return None, [], "error"
    code = d.get("code")
    if code == "EXCEED_LIMIT":
        return None, [], "rate_limited"
    if code != "OK" or not d.get("total"):
        return None, [], "miss"
    it = (d.get("items") or [{}])[0]
    imgs = it.get("images") or []
    identity = {
        "title": it.get("title") or "",
        "brand": it.get("brand") or "",
        "image": imgs[0] if imgs else "",
        "source": "UPCitemdb",
    }
    offers = []
    for o in it.get("offers") or []:
        price = o.get("price") or 0
        cur = (o.get("currency") or "USD").upper()
        if price <= 0 or cur != "USD":
            continue
        store = (o.get("merchant") or o.get("merchant_domain")
                 or o.get("domain") or "Online store")
        offers.append({
            "store": store,
            "price": round(float(price), 2),
            "source": "UPCitemdb offers",
            "updated": o.get("updated") or None,
            "title": (o.get("title") or "")[:100],
        })
    return identity, offers, "ok"


def open_prices(ean):
    """Open Prices (prices.openfoodfacts.org) crowd price records.

    Returns (offers, history, status): offers = latest price per store,
    history = all dated USD points (for a trend signal).
    """
    status, d = fetch_json(
        "https://prices.openfoodfacts.org/api/v1/prices"
        "?product_code=%s&size=50&order_by=-date" % ean)
    if status != 200 or not d or "items" not in d:
        return [], [], "error"
    items = [i for i in d.get("items") or []
             if (i.get("currency") or "USD") == "USD" and i.get("price")]
    if not items:
        return [], [], "miss"
    history = []
    latest_by_store = {}
    for i in items:
        loc = i.get("location") or {}
        store = (loc.get("osm_brand") or loc.get("osm_name")
                 or "Unknown store")
        city = loc.get("osm_address_city") or ""
        point = {
            "store": store,
            "city": city,
            "price": round(float(i["price"]), 2),
            "date": i.get("date"),
            "source": "Open Prices (crowd record)",
        }
        history.append(point)
        if store not in latest_by_store:
            latest_by_store[store] = point
    return list(latest_by_store.values()), history, "ok"


def trend_from_history(history):
    """Trend from dated price points: newest vs the median of older ones."""
    pts = [h for h in history if h.get("date") and h.get("price")]
    if len(pts) < 2:
        return None
    pts = sorted(pts, key=lambda h: h["date"])
    newest = pts[-1]
    older = [p["price"] for p in pts[:-1]]
    base = statistics.median(older)
    if base <= 0:
        return None
    pct = (newest["price"] - base) / base * 100.0
    if abs(pct) < 3:
        signal = "flat"
    elif pct > 0:
        signal = "rising"
    else:
        signal = "falling"
    return {
        "signal": signal,
        "detail": ("Latest recorded price %s%.2f (%s, %s) vs earlier "
                   "median $%.2f across %d record(s): %+.1f%%"
                   % ("$", newest["price"], newest["store"],
                      newest["date"], base, len(older), pct)),
        "source": "Open Prices (crowd record) history",
    }


def trend_from_sold(items):
    """Trend from eBay sold items with dates: last-30d median vs older."""
    from datetime import datetime
    dated = []
    for it in items or []:
        if not it.get("date"):
            continue
        try:
            dt = datetime.strptime(it["date"], "%b %d, %Y")
        except ValueError:
            continue
        dated.append((dt, it["price"]))
    if len(dated) < 6:
        return None
    dated.sort(key=lambda x: x[0])
    newest = dated[-1][0]
    recent = [p for dt, p in dated if (newest - dt).days <= 30]
    older = [p for dt, p in dated if (newest - dt).days > 30]
    if len(recent) < 3 or len(older) < 3:
        return None
    r = statistics.median(recent)
    o = statistics.median(older)
    if o <= 0:
        return None
    pct = (r - o) / o * 100.0
    signal = "flat" if abs(pct) < 3 else ("rising" if pct > 0 else "falling")
    return {
        "signal": signal,
        "detail": ("eBay sold median last 30 days $%.2f (%d sales) vs "
                   "older $%.2f (%d sales): %+.1f%%"
                   % (r, len(recent), o, len(older), pct)),
        "source": "eBay sold listings",
    }


def lookup_aggregate(upc):
    ean = "0" + upc if len(upc) == 12 else upc
    sources = {}
    store_prices = []

    identity, sources["open_food_facts"] = off_identity(ean)
    u_identity, u_offers, sources["upcitemdb"] = upcitemdb_lookup(upc)
    if u_identity and (not identity or not identity.get("title")):
        identity = u_identity
    elif u_identity and identity:
        # UPCitemdb titles are usually fuller; prefer it, keep OFF image gap-fill
        if len(u_identity.get("title", "")) > len(identity.get("title", "")):
            keep_img = identity.get("image")
            identity = u_identity
            if not identity.get("image"):
                identity["image"] = keep_img
    store_prices.extend(u_offers)

    op_offers, op_history, sources["open_prices"] = open_prices(ean)
    store_prices.extend(op_offers)

    # de-dupe same store+price, sort cheapest first
    seen = set()
    uniq = []
    for o in sorted(store_prices, key=lambda x: x["price"]):
        k = (o["store"].lower(), o["price"])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(o)
    store_prices = uniq

    ebay_sold = ebay_search(upc, sold=True)
    ebay_active = ebay_search(upc, sold=False)
    # warm the /ebay/* cache so the app's own calls return instantly
    now = time.time()
    if ebay_sold.get("ok"):
        CACHE[("/ebay/sold", upc)] = (now, ebay_sold)
    if ebay_active.get("ok"):
        CACHE[("/ebay/active", upc)] = (now, ebay_active)
    sources["ebay_sold"] = "blocked" if ebay_sold.get("blocked") else "ok"
    sources["ebay_active"] = "blocked" if ebay_active.get("blocked") else "ok"

    trend = trend_from_sold(ebay_sold.get("items")) \
        if ebay_sold.get("ok") else None
    if trend is None:
        trend = trend_from_history(op_history)

    asking = None
    if store_prices:
        prices = sorted(o["price"] for o in store_prices)
        asking = {
            "min": prices[0],
            "median": round(statistics.median(prices), 2),
            "max": prices[-1],
            "count": len(prices),
            "source": "store listings (see store_prices)",
        }

    return {
        "ok": True,
        "upc": upc,
        "fetched_at": int(time.time()),
        "identity": identity,
        "store_prices": store_prices,
        "asking": asking,
        "ebay_sold": ebay_sold,
        "ebay_active": ebay_active,
        "trend": trend,
        "sources": sources,
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)
        path = parsed.path.rstrip("/") or "/"
        if path == "/health":
            return self._json(200, {"ok": True, "service": "flipscan-api",
                                    "version": "1.1"})
        if path == "/probe":
            status, body = fetch(
                "https://www.ebay.com/sch/i.html?_nkw=711719541226"
                "&LH_Sold=1&LH_Complete=1&_ipg=60")
            return self._json(200, {
                "ebay_http_status": status,
                "bytes": len(body),
                "blocked": is_blocked(status, body),
                "has_items": "s-item" in body,
            })
        if path == "/lookup":
            upc = (qs.get("upc") or [""])[0].strip()
            digits = re.sub(r"[^0-9]", "", upc)
            if len(digits) == 13 and digits.startswith("0"):
                digits = digits[1:]
            if not digits:
                return self._json(400, {"ok": False, "error": "upc required"})
            key = ("lookup", digits)
            now = time.time()
            if key in CACHE and now - CACHE[key][0] < LOOKUP_CACHE_TTL:
                payload = dict(CACHE[key][1])
                payload["cached"] = True
                return self._json(200, payload)
            payload = lookup_aggregate(digits)
            CACHE[key] = (now, payload)
            return self._json(200, payload)
        if path in ("/ebay/sold", "/ebay/active"):
            sold = path.endswith("sold")
            upc = (qs.get("upc") or [""])[0].strip()
            q = (qs.get("q") or [""])[0].strip()
            query = upc if upc else q
            if not query:
                return self._json(400, {"ok": False, "error": "upc or q required"})
            key = (path, query)
            now = time.time()
            if key in CACHE and now - CACHE[key][0] < CACHE_TTL:
                payload = dict(CACHE[key][1])
                payload["cached"] = True
                return self._json(200, payload)
            payload = ebay_search(query, sold)
            if payload.get("ok"):
                CACHE[key] = (now, payload)
            return self._json(200, payload)
        return self._json(404, {"ok": False, "error": "not found"})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
