#!/usr/bin/env python3
"""FlipScan data backend (stdlib only).

Purpose: fetch eBay public search pages server-side (the FlipScan Android
client may be on store Wi-Fi / carrier IPs that eBay treats differently),
parse sold + active listing prices, and return clean stats JSON.

Endpoints:
  GET /health
  GET /probe                      -> egress test vs eBay (status + markers)
  GET /ebay/sold?upc=<digits>     -> sold-listing stats (LH_Sold, last ~90d page)
  GET /ebay/active?upc=<digits>   -> lowest current active listings
  Both accept &q=<title words> instead of upc for a title search (labeled).

Nothing here fabricates data: if eBay blocks us, the response says so
({"ok": false, "blocked": true, ...}) and the app falls back to labeled
asking-price data from other sources.
"""
import json
import os
import re
import statistics
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UA = ("Mozilla/5.0 (Linux; Android 14; SM-S938U) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")

CACHE = {}          # key -> (ts, payload)
CACHE_TTL = 6 * 3600

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


import urllib.parse


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
            return self._json(200, {"ok": True, "service": "flipscan-api"})
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
