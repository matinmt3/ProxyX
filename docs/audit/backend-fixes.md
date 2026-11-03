# Backend audit - 2026-10-26

## Reproduced defects and corrections

Four new regressions were executed against the unchanged baseline implementation before fixes. All four failed as expected; the log is `work/proxyx-audit-20261026-regression-red.log` in the audit workspace.

1. Catalog merging deduplicated URLs but not display names, while fetch health is keyed by name. Remote and bundled entries could overwrite each other's UI health. Merge now also deduplicates names, case-insensitively, with remote priority.
2. Signature verification accepted a valid EC signature on curves other than the documented P-256 curve. Exact curve parameters are now validated before verifying the signature. No signing/private keys were read or changed; tests generate ephemeral test keys.
3. A late transfer on the same network could replace newer transfer evidence. SQL now compares measurement timestamps.
4. A late transfer could attach itself to newer protocol evidence. SQL now also requires transfer time not to precede the stored protocol measurement.

Additional audited corrections:

- DNS waits and native-worker capacity are bounded; cancelled lookups cannot accumulate unlimited threads. Active network identity includes link properties, not merely the Network handle. Native DNS that ignores interruption can occupy one of four bounded workers; it cannot block a caller indefinitely.
- MTProto now has an overall deadline covering connect and handshake, in addition to per-read timeouts. Cancellation closes the exact active socket. No TCP result was promoted to protocol success.
- Source collection explicitly cancels OkHttp calls, rejects HTTPS downgrades/credential-bearing redirect requests, disables system proxy inheritance, rejects mixed public/private DNS, and measures response limits in decompressed bytes.
- Telegram WEB feed DNS uses the same public-address policy. Existing trust confirmation, WebView TLS checks and bridge isolation remain unchanged.
- Fresh source chunk writes are transactional. Signed catalog cancellation/network changes propagate instead of silently becoming cache fallback.
- Direct HTTP auditing found 39 HTTP 200 responses and 11 HTTP 404 responses among the 50 bundled MTProto feeds. The ten V2RAYCONFIGSPOOL Telegram Proxy feeds and iwh3nTGProxy are retained but disabled by default after the corresponding two repository API roots also returned 404. HTTP 200 is source availability, not proof that every contained proxy works.

## Stage C verification

Fresh baseline: 304 tests passed. Core changes: full unit suite passed in 3m30s (log `proxyx-audit-20261026-core-tests.log`). Additional tests cover byte limits, mixed/private DNS, DNS timeout/error propagation, protocol deadline and parent cancellation. Final aggregate counts and lint are recorded in final-verification.md after the next gate.

## Generic module boundary

Generic HTTP/SOCKS records use their own model, DAO, repository and **separate Room database** `generic_proxy_audit_database` (schema 1). Existing Telegram schema 16 is unchanged; no destructive migration, no copying of sessions, no reset. No generic row can enter MTProto/WEB tables or ranking. New database creation and stale-write/provenance tests are deterministic.

Only two verified public providers have enabled adapters: ProxySpace (four typed files, >=20-minute cache) and Proxy Scraper (typed download, >=60-minute cache). Sixteen candidate websites remain disabled with reasons. Source metadata is not local test evidence. Failed/empty fetches retain stored entries; a successful source refresh replaces that source's membership and resets refreshed evidence.

HTTP checks require ordinary HTTP plus a TLS-verified HTTPS CONNECT request; CONNECT-only entries require the latter. SOCKS4 uses captured-network local IPv4 DNS; SOCKS4a/5 use remote destination DNS. All proxy endpoint resolution and sockets use UserNetworkSession. A bounded minimal CONNECT-only SOCKS handshake is justified because OkHttp 4.10's SOCKS socket construction bypasses its configured socket factory. Authentication is intentionally unsupported and reported as AUTH_FAILED; credentials are rejected by parsing and never stored. There is no UDP/BIND support.

The controlled destination is Google's generate_204 connectivity endpoint, with normal platform certificate/hostname validation for HTTPS. Outcomes describe this specific request, not general internet reachability, country, anonymity, safety, Telegram support or throughput. A destination-specific block can yield REQUEST_FAILED. Network outage/change and DNS uncertainty are INCONCLUSIVE. No external checker receives endpoints or results. A public proxy sees the test request; the destination sees the proxy exit, as expected for a proxy test.

## Acceptance and limits

Backend acceptance before frontend changes: 333 tests / 59 suites, zero failures, errors or skips. The final debug build before the device pass had 336 tests / 60 suites and lint with zero errors and 12 warnings. See final-verification.md for the last rebuild and device evidence.

On the isolated API 34 emulator, all seven instrumentation tests passed, including migrations 13->14, 14->15 and 15->16 and four native TDLib tests. The 15->16 fixture verifies preservation of favorites and MTProto measurements while creating an empty WEB table. This is not an exhaustive migration test from every historic database version.

Five generic feeds fetched successfully in the app (78 records at that time). Live CONNECT/SOCKS batches exercised failures, cancellation and network loss; no tested generic endpoint completed the HTTPS success path. Deterministic protocol fixtures are green, but they do not establish availability of arbitrary public proxies. Do not present this module as a VPN or configure system-wide routing.
