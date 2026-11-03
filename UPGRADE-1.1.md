# FlipScan v1.1 — market-data upgrade (2026-11-03)

## Field report that triggered this

Scanner works on his phone (barcode read confirmed). A Monster scan
showed NO data. His spec: a scan must pull pricing from the internet as
a whole — current prices at multiple stores (e.g. Walmart $3, Target
$3.50), eBay current selling price AND sold prices, plus market/trend
signals for an accurate buy decision.

## Root cause of the empty Monster result (traced end-to-end)

The v1.0 chain had exactly two price-bearing paths and both were dead
at scan time:

1. **UPCitemdb keyless trial — rate-limited.** Direct test today:
   `{"code":"EXCEED_LIMIT"}` on every lookup, from sandbox and server.
   The trial endpoint is one shared demo quota for all keyless users;
   v1.0's testing window happened to fit under it. With it gone, the
   ONLY source of retailer offers (and the best identity) vanished.
2. **eBay walls datacenter IPs.** Backend `/ebay/sold` and
   `/ebay/active` → `blocked: true` (403 / block page), confirmed live
   on Render and in the sandbox. Phone-direct and in-app WebView routes
   remain unproven either way.
3. What survived was **Open Food Facts identity** (title/brand/photo,
   zero prices) — but only from some egress IPs: from Render,
   world.openfoodfacts.org throttles and identity came back "miss" too
   until the .net mirror fallback shipped in this upgrade.

Net effect on his phone: title at best, no prices anywhere → "no data."

Also tested and dead from server egress (do not retry as a strategy):
Walmart search page (200 but captcha-walled, no price data), Target
search page (no price data), PicClick (403), WatchCount/130point
(captcha/connection failure, as in v1.0). Walmart/Target expose no
free no-key price API; their live shelf prices require partner/
affiliate keys.

## What shipped (commit 7a9fe19 + 0fa89e7; backend v1.1, app v1.1 code 2)

**Backend — new `GET /lookup?upc=` aggregate** (old endpoints untouched,
so v1.0 clients are unaffected):
- Identity: Open Food Facts (.org → .net mirror fallback; refusal is
  reported as `http_<code>`, not a fake "miss"), UPCitemdb identity
  preferred when its quota answers.
- **Per-store current prices** (`store_prices`: store, price, source,
  date/updated): UPCitemdb offers (merchant names — Walmart, Target,
  Amazon, Walgreens, eBay, etc. — when quota allows) + **Open Prices**
  (prices.openfoodfacts.org, a real no-key API: crowd-recorded store
  prices with store name, city, and record date; latest per store).
- eBay active range + eBay sold stats, each reported ok/blocked.
- **Trend signal** computed only from dated data actually returned:
  eBay sold 30-day median vs older (when sold items carry dates), else
  Open Prices history (newest record vs median of earlier records).
  `trend: null` when the data doesn't support one — never fabricated.
- 12h server cache per UPC; /lookup also warms the /ebay/* cache.

**App v1.1:** merges backend /lookup into every UPC lookup (identity
gap-fill, asking fallback when the phone-side UPCitemdb quota is dead,
storePrices, trend) and renders a **PRICES AROUND THE WEB** card on the
verdict screen: one line per store — name (+city), price, source,
date — plus the trend line. Verdict math/basis rules unchanged
(sold > active > asking; MAYBE cap without sold data).

## Live verification (production, 2026-11-03, after deploy)

| UPC | Result |
|---|---|
| Monster Original 16oz `070847037569` | Identity "Monster Energy" ✓; store price **Safeway (Mountain View) $19.99 — Open Prices crowd record, 2025-09-28** ✓; UPCitemdb `rate_limited` (honest); eBay sold/active `blocked` (honest) |
| Coca-Cola `049000042511` | Identity ✓ (OFF); no store prices (Open Prices miss; UPCitemdb rate-limited) |
| Cheerios `016000275287` | Identity ✓ (OFF); no store prices (same reason) |
| Monster Pacific Punch `070847031918` | Identity ✓ (OFF); no store prices (same reason) |

Install page serves the v1.1 APK byte-for-byte (MD5
`b472ef82c34cab37a83c2a1e9a08a61f`, same signing cert as v1.0 →
installs in place).

## Honest limits that remain

- **UPCitemdb offers are the Walmart/Target/Amazon price feed, and the
  keyless trial quota is shared and currently exhausted.** The code
  path is live and reports `rate_limited`; offers flow automatically
  whenever quota answers (as they did during v1.0 testing). The fix is
  a free UPCitemdb dev key (his signup) — 100 lookups/day on his own
  quota, server-cached 12h, which stretches a long way.
- **Open Prices coverage is crowd-sourced and thin in the US** — real
  prices with store+date when records exist, silence when they don't.
- **eBay sold data still needs his eBay developer key** (Browse API +
  Marketplace Insights) — no server-side route exists; the phone
  routes remain his on-device test. Nothing about that changed.
- Trend signals will be rare until sold data or richer price history
  exists; the field stays empty rather than guessing.
