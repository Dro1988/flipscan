# FlipScan v1.0 — build report (2026-11-03)

**What it is:** Android app (`com.droapps.flipscan`) for in-store clearance arbitrage.
Stand in the clearance aisle → scan a barcode → see the product, its resale price, the full
fee/shipping math, and a BUY / MAYBE / SKIP verdict. Phase 1 of the merged reseller product
(scan → price → later: list everywhere). His own tool first; sideload only, no Play submission.

## Deliverables (all verified)

| Thing | Where | Verified |
|---|---|---|
| Install page + APK | https://dro1988.github.io/flipscan/ (repo `Dro1988/flipscan`, `/docs`) | Page live (HTTP 200); served APK downloaded and **MD5 matches the local build byte-for-byte** |
| APK | v1.0 (code 1), 324,853 bytes, **MD5 `918a53a3e01046812c4ce689f7379203`** | aapt2 badging: package/version/permissions correct; signed with the **dedicated FlipScan keystore** (cert SHA-256 `18ed477f2c28a68e79c9c3f65586603fe26fb9774ef016030b9d66fc429eda4e`); keystore + password are local-only, git-ignored, never committed |
| Backend | https://flipscan-api.onrender.com (Render free, service `flipscan-api`, repo `backend/app.py`, stdlib-only Python) | `/health` → 200 live |
| Source | `~/workspace/flipscan/` (app/, backend/, build-apk.sh, docs/) | Built with the manual pipeline (aapt2 + javac --release 17 + D8 from build-tools 35.0.0), per AGENTS.md |

## What's in the app

- **Scanner:** BookShelf's proven LiveScanActivity pattern (Camera2 + ZXing continuous decode,
  torch, beep/vibrate), retargeted to retail formats (UPC-A/E, EAN-8/13, Code 128/39).
  Manual entry on the home screen takes a UPC **or a typed product name** (damaged barcodes).
- **Verdict screen:** product photo/name/brand; big resale number labeled by source; big-keypad
  shelf-price entry (prefilled from the lowest online asking price when one is found, per spec);
  editable shipping; itemized breakdown exactly as he asked —
  *Sells for − eBay fees (13.25% + $0.40, adjustable in Settings) − shipping − shelf price = NET PROFIT* —
  and a color verdict: **BUY** (sold-data basis, profit ≥ $5 and ≥ 25% of resale), **MAYBE**
  (thin profit, or basis is asking prices — capped, never BUY, on unverified data), **SKIP** (≤ $0).
- **Haul list:** every lookup auto-saves (date, store chips Walmart/Target/Dollar General/Other,
  shelf, resale, fees, ship, profit, verdict, data basis); tap toggles "bought", long-press deletes;
  header shows bought count + total estimated profit on bought items. Local-first (SharedPreferences).
- **Cache:** lookups cached on-device 6h — repeat scans of the same item are instant and free.
- **Settings:** fee %, per-order fee, default shipping, price-server URL. The app also warms the
  free-tier server at launch so scans don't wait on a cold start.

## Data sources — what actually returned data (tested 2026-11-03)

| Source | Result |
|---|---|
| **UPCitemdb** (keyless trial endpoint) | ✅ **Works.** Identity + offers. End-to-end example below. |
| **Open Food Facts** | ✅ Works (fallback; strong for grocery, thin elsewhere — DualSense correctly "not found", Coca-Cola UPC 049000042511 found). |
| **eBay public sold search — from sandbox** | ❌ HTTP 403 bot wall (Akamai). |
| **eBay public sold search — from Render backend** | ❌ HTTP 403 bot wall. The backend reports `{"ok":false,"blocked":true}` honestly — it never invents numbers. |
| **eBay Browse API** | Not used: requires an eBay developer app key (OAuth). Per instructions, no-credential paths only in v1. |
| 130point / WatchCount | ❌ Connection failure / Cloudflare captcha from sandbox. |
| r.jina.ai reader proxy | ❌ Upstream still 403 from eBay. |

**End-to-end example (real, run today):** UPC `711719541226` → UPCitemdb identity
"Sony PlayStation Dualsense Wireless Controller — PlayStation 5", brand Sony, product photos;
USD asking offers computed (min/median from live offer set; $74.99 Newegg at test time).
eBay sold path correctly returned *blocked* at both server and sandbox, so in-app the basis
falls to asking prices and the verdict caps at MAYBE with an on-screen warning — by design.

**The one untested-from-here path:** the app tries eBay sold data **three** ways at scan time —
(1) price server, (2) direct HTTP from the phone, (3) an in-app WebView fetch (real Chromium
fingerprint on his residential/carrier IP — the route most likely to pass the bot wall).
(1) is proven blocked; (2)/(3) can only be proven on his phone. This is his first-scan test.

**Shipped-code tests:** the exact Java parser/stats classes in the APK were run on the JVM
against sample eBay HTML: 3 items parsed with prices + sold dates, median correct,
captcha-page detection true/false correct, EAN-13→UPC-A normalization correct.
The Python backend parser passes the same sample set.

## Honest gaps / known limits

1. **eBay sold data is not guaranteed in v1.** Datacenter IPs are walled; the phone routes may
   work but are unproven until his first real scan. The permanent fix is an official eBay
   developer key (his free signup at developer.ebay.com, ~5 min) → Browse API for active
   listings by GTIN, and Marketplace Insights (approval-gated) for true sold data. The backend
   is already the right home for those calls.
2. **UPCitemdb trial quota:** the keyless *search* endpoint hit EXCEED_LIMIT during testing
   (typed-name identity therefore comes from eBay listing titles, labeled as such); the *lookup*
   endpoint kept working, and on-device caching stretches it. Heavy daily scanning could hit the
   cap — a free UPCitemdb dev key (his signup) removes it.
3. **UPC-keyword searches on eBay can include loose matches** (any listing mentioning the number).
   Median-of-many keeps the verdict sane; sold item titles are retained in results for sanity checks.
4. UPCitemdb offer data can be dated (some offers carry old timestamps) — the app labels it
   "asking prices," never "sold."
5. Fees default (13.25% + $0.40) matches the task spec; promoted-listing fees and sales tax on
   purchase are not modeled in v1.

## His test steps (phone-only)

1. Install from https://dro1988.github.io/flipscan/ (allow unknown apps for the browser once).
2. Scan 2–3 real clearance items at Walmart/Target. Watch the basis label under the big price:
   "median of N sold" = the phone routes beat the bot wall; "asking price… capped at MAYBE" = they didn't.
3. Check the numpad math on one item against a hand calculation; mark one item bought and
   confirm the haul total moves.

## Next phase hooks (already shaped for it)

Backend is deployed and versioned for the listing-engine phase (eBay Sell API first per the
marketplace research); the haul list's per-item record (UPC, title, photos, cost, verdict) is
the natural seed for a listing draft.
