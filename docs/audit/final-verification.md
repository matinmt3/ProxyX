# ProxyX 7.1.0 audit verification

Date: 2026-10-26. Native Android source updated in `C:/Users/User/Desktop/proxyx`.
Version/code remain 7.1.0/75. No commit, push, public upload or release distribution.

## Execution and safety

The baseline, protocol/source research and core fixes preceded the separate generic module. The backend gate passed 333 tests before frontend changes. Frontend compilation, tests, lint and fresh device checks followed. The final source passed 342 tests across 62 suites, debug lint, Debug APK, unsigned Release APK and unsigned AAB builds. Earlier passing results were not substituted for the final rebuild.

The Desktop directory is a junction whose canonical path contains Persian characters. AGP rejects that path. Builds use the secret-free ASCII source copy at `C:/Users/User/Documents/Codex/2026-07-08/new-chat/work/proxyx-audit-20261026`. No path-check bypass was added. The original source location was preserved. Signing configuration, private local configuration, Telegram sessions and the user's databases were not reset or copied. The enclosing user-profile Git index was not changed.

Device tests use only a new, isolated AVD `ProxyX_Audit_D_20261026`, API 34 Google APIs x86_64, serial `emulator-5580`, with data on `D:/CodexAudit/ProxyX-20261026`. Existing `ProxyX_Test` was not opened. The first new C-drive AVD could not start due to disk space, so a distinct D-drive AVD was created. WHPX works. Initial System UI ANR and low host memory mean emulator frame rate is not a device-performance result. Builds and emulator runs were serialized.

## Root causes and changes

| Area | Root cause and correction | Principal files |
|---|---|---|
| Source health | Duplicate display names across catalogs could collide in name-keyed state; case-insensitive name deduplication now accompanies URL deduplication. | `ProxySourceManager.kt`, `ProxySourceManagerTest.kt` |
| Catalog authentication | A valid signature on the wrong EC curve was accepted despite the P-256 contract; exact curve parameters are checked. | `SignedSourceCatalog.kt`, `SignedSourceCatalogTest.kt` |
| Transfer ordering | Late same-network results could overwrite newer transfer/protocol observations; conditional SQL now compares both timestamps. | `ProxyDao.kt`, `ProxyDaoTest.kt` |
| DNS and network identity | Native DNS could block unboundedly, and same-handle DNS/link changes were missed; bounded workers/deadlines and full identity rechecks added. | `BoundedDnsLookup.kt`, `UserNetworkSession.kt`, `ProxyVerifier.kt` |
| Protocol deadline | Per-read timeout did not bound the whole connect/handshake; overall coroutine deadline and exact-socket cleanup added. | `MtProtoConnectionProbe.kt`, `NetworkAuditRegressionTest.kt` |
| Untrusted feeds | Redirect/body/DNS policies and cancellation were inconsistent; HTTPS/public-address/byte limits and cancellable call handling centralized. | `SourceHttpPolicy.kt`, `ProxyCollector.kt`, `WebProxyController.kt` |
| Dead bundled feeds | Eleven endpoints and their two repository roots returned 404; preserved but disabled rather than repeatedly fetched. | `ProxySourceManager.kt`, `tools/AuditSourceAvailability.ps1` |
| Generic compatibility | General proxies cannot enter Telegram tables; added independent model, Room database, repository, protocol checker and paged screen. | `data/generic/*`, `GenericProxyController.kt`, `GenericProxyPanel.kt`, `AppContainer.kt`, `VaultScreen.kt` |
| Home scrolling | An already-consumed benchmark revision could reset later refreshes; consume once and create a new Pager generation for completed benchmarks. | `ProxyViewModel.kt`, `DashboardScreen.kt` |
| Home network validity | Compact Home hid the existing different-network warning while still showing a historical verified badge. Badge, latency/evidence rows and transfer eligibility now use current-network identity; historical data is retained but not presented as current. | `DashboardEvidence.kt`, `DashboardScreen.kt`, `DashboardEvidenceTest.kt` |
| Responsive/RTL UI | Screen configuration height did not always represent actual constraints; use BoxWithConstraints. Generic numeric progress needed explicit LTR direction in Persian. | `DashboardScreen.kt`, `GenericProxyPanel.kt` |
| Concurrent progress | Parallel source callbacks could arrive out of order and lower the displayed counter; progress is monotonic and bounded, with two regression tests. | `GenericProxyController.kt`, `GenericProgressTest.kt` |
| Privacy | New request destination/storage needed disclosure; privacy text and scrollable dialog updated. | `SettingsScreen.kt`, `docs/PRIVACY-POLICY-en.md` |

Full changed-file hashes: `artifacts/audit-20261026-changes.json`. Four regressions were first run against the unchanged implementation and failed, then passed after correction. Other focused tests exercise limits, cancellation, protocol frames and persistence; they are not claims of arbitrary network success.

## Database and protocol boundaries

Main Telegram Room schema 16 is unchanged. Generic records use a separate schema-1 database `generic_proxy_audit_database`, with an exported JSON schema. Generic refresh is transactional: successful feeds replace their own membership, shared provenance survives, failed/empty feeds retain prior records, and stale test generations cannot overwrite refreshed rows. Existing user data was not cleared.

MTProto TCP reachability, genuine req_pq response verification and authenticated TDLib byte throughput remain distinct. One live MTProto finalist completed the three-stage route check on the emulator. No authenticated file throughput was measured in this audit.

Generic protocol fixtures cover HTTP/CONNECT response parsing, SOCKS4 local DNS, SOCKS4a/SOCKS5 remote destination names, malformed/truncated responses, authentication-required rejection and header limits. Platform TLS certificate/hostname validation is retained. Live generic requests used the captured Android network; no tested public endpoint completed the HTTPS success path during the sample runs. A zero-success batch is not converted to a working result. No UDP/BIND or credential authentication is implemented; these limits are explicit.

WEB remains a restricted, trust-gated WebView transport, not generic CONNECT/SOCKS. Public feed availability failed in the observed device run, and bundled fallback links were labeled untested. An invalid import was rejected. Provider trust was cancelled rather than silently accepted, so no live WEB handshake success is claimed. Android WebView cannot use the custom socket factory; network identity is checked around the result, but underlying VPN-path changes hidden by Android cannot be fully proven.

## Source results

- All 18 requested general-list sites and 3 checker services are documented in source-compatibility-matrix.md with access/format/permission limitations.
- Two public providers are integrated through five typed feeds, separately from Telegram. Device refresh accepted 78 records, including four ProxySpace lists and one Proxy Scraper record at that time. A second refresh used all five cached feeds.
- Sixteen general-list providers remain disabled because stable permitted extraction, protocol information or availability could not be verified. The three external checker services are not integrated and received no uploads.
- Existing MTProto direct source audit: 39 HTTP 200 and 11 HTTP 404. The app progressively collected 553 MTProto records in the device run. Counts are point-in-time, not guarantees of proxy quality.

## Automated test and build evidence

Commands below run in the ASCII build copy with `JAVA_HOME=C:/Program Files/Android/Android Studio/jbr`, `ANDROID_HOME=C:/Users/User/AppData/Local/Android/Sdk`, one Gradle worker and a 1 GiB heap. Sibling logs under `work/` retain actual output.

| Test category | Command | Actual result | Notes |
|---|---|---|---|
| Baseline unit | `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-build-cache` | 304 tests / 57 suites passed; overall command failed later | Offline lint dependencies missing; separate online assemble/lint then passed. |
| Reproduce bugs | `gradlew.bat :app:testDebugUnitTest` with four method-specific test filters | Four added regression cases failed before fixes | `proxyx-audit-20261026-regression-red.log` lists the four failing methods; this was not a whole-suite run. |
| Backend gate | `gradlew.bat :app:testDebugUnitTest --offline --no-daemon --max-workers=1` | 333 tests / 59 suites; 0 failures/errors/skips | `proxyx-audit-20261026-backend-final.log`; before frontend changes. |
| Device-build gate | `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --max-workers=1` | PASS, 5m30s; 336 tests / 60 suites; 0 failures/errors/skips | `proxyx-audit-20261026-verified-build.log`; debug lint 0 errors, 12 warnings. |
| Instrumentation | `adb -s emulator-5580 shell am instrument -w com.aistudio.proxyx.vbnmkw.test/androidx.test.runner.AndroidJUnitRunner` | PASS, 7 tests, 2.567s | `artifacts/audit-20261026-instrumentation.txt`; 3 migration fixtures + 4 native TDLib cases. |
| Final APK instrumentation | `adb -s emulator-5580 shell am instrument -w com.aistudio.proxyx.vbnmkw.test/androidx.test.runner.AndroidJUnitRunner` | PASS, same 7 tests repeated, 0.840s | `artifacts/audit-20261026-instrumentation-final.txt`; after installing the final Debug APK, not counted as seven additional unique cases. |
| Home-network APK instrumentation | `adb -s emulator-5580 shell am instrument -w com.aistudio.proxyx.vbnmkw.test/androidx.test.runner.AndroidJUnitRunner` | PASS, same 7 tests repeated, 3.006s | `artifacts/audit-20261026-instrumentation-home-final.txt`; after the last 342-test source build. |
| Live source audit | `powershell -File tools/AuditSourceAvailability.ps1` | 50 attempted: 39 HTTP 200 / 11 HTTP 404 | `artifacts/audit-20261026-source-http.json`; no proxy health inference. |
| RTL/release rebuild | `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --offline --no-daemon --max-workers=1` | PASS, 10m52s | `proxyx-audit-20261026-release-check.log`; signing environment cleared only in the build process; no signing settings/key files edited. |
| Monotonic-progress gate | `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --offline --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"` | PASS, 10m39s; 338 tests / 61 suites; 0 failures/errors/skips | `proxyx-audit-20261026-final-gate.log`; includes monotonic progress regressions. |
| Final Home-network gate | `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --offline --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"` | PASS, 12m11s; 342 tests / 62 suites; 0 failures/errors/skips | `proxyx-audit-20261026-home-network-gate.log`; includes four network-badge regressions. Final debug lint: 0 errors / 12 warnings. |
| APK ZIP alignment | `zipalign.exe -c -P 16 4 app-release-unsigned.apk` | Exit 0 | ZIP alignment check, not a substitute for native ELF/page-size device testing. |
| Release signing check | `apksigner.bat verify app-release-unsigned.apk` | Expected exit 1: unsigned | AAB also has zero signature entries. Neither is a signed publication artifact. |

The 12 debug lint warnings in the device-build gate are AGPVersion (1), GradleDependency (1), NewerVersionAvailable (6), UseKtx (3) and UseTomlInstead (1). No mass dependency upgrades or unrelated refactors were made just to remove advisory warnings. The screen-height warning was removed by correcting the layout implementation.

Final build sizes: Debug APK 55,946,103 bytes; unsigned Release APK 34,187,817 bytes; unsigned AAB 37,881,582 bytes. Full local paths and SHA-256 hashes are in `artifacts/audit-20261026-build-outputs.json`. The build tasks include R8 and release vital lint; a full `lintRelease` was not run. No publication/signing credentials were used.

All 196 files under the original project's `app/src` were SHA-256 compared with the last built ASCII copy: zero mismatches. Main Room schema files remain unchanged. RTL visual regression: `audit-20261026-final-rtl-progress.png` shows the corrected completed/total fraction in Persian/dark mode at font scale 1.3 on the 338-test build. Cancel worked on that rebuilt APK; previous-network generic rows retained their explicit stale-network label. Font scale was restored to 1.0 afterward. System UI ANR recurred during cold boots on this memory-constrained host and was handled with Wait; no app crash was inferred from it.

## Device workflows actually exercised

Evidence directory: `artifacts/v7-device`, prefix `audit-20261026`. See the CSV and fresh screenshots, not historical v7 screenshots. The broad workflow pass used the 336-test audit build; the 338-test rebuild added the corrected RTL/progress checks, direct Internet measurement and explicit refresh check. The last 342-test rebuild adds the Home network-state correction and its targeted device recheck. Not every workflow was repeated after each display-only patch; the full automated suite and builds were repeated.

| Workflow | Observation |
|---|---|
| Startup and initial DB | Fresh app launched; channel prompt dismissed; progressive collection produced untested MTProto rows. |
| Three-stage benchmark | Requested 5; stage 1 tested 5, stage 2 tested 1, stage 3 tested 1 and passed 1; stage 4 explicitly disabled without login. |
| Pause/Resume | Paused at 4/5, resumed, completed three stages. |
| Ranking/Home | Returned to top; successful finalist ranked first, showing TCP 30/30 and MTProto 24/24 evidence, not invented transfer rates. |
| Final Home network regression | Last APK manual retest produced 144ms, TCP 10/10 and MTProto 8/8. WiFi/data loss changed both compact header and row to OTHER NETWORK and hid historical latency. Fresh screenshot `audit-20261026-home-network-final.png` visually verified. Both interfaces restored; no fake database evidence inserted. |
| Local favorites/comparison | Selected endpoint appeared in each distinct Vault panel. No Saved Messages claim without login. |
| Settings | Auto-refresh switched off; English->Persian, light/system->dark and font scale 1.3 applied. |
| Generic fetch/cache | Five feeds succeeded, 78 records; repeat refresh fetched zero and cached five. Source health differentiated fetched lists from tested proxies. |
| Generic tests | Count 2 executed to completion with zero success; SOCKS5 batch also exercised handshake failures. Explicit cancellation marked the operation/active record cancelled. |
| Lifecycle | After confirmed Android launcher foregrounding, returning showed cancelled generic operation. Immediate Home/reopen was not treated as proof of ON_STOP. |
| Network loss | Disabled WiFi and mobile data on the isolated emulator mid-batch; result was inconclusive, then both interfaces restored. No host network setting changed. |
| Generic filter/search | SOCKS5 filter selected; unmatched host search returned an empty state. |
| Direct Internet speed | The 338-test audit build completed an actual direct request measurement: 700 KiB/s download, 419 KiB/s upload, with UI sample payloads 631 KB / 728 KB. Screenshot `audit-20261026-direct-speed.png`. This is neither Telegram throughput nor a generic-proxy throughput result. |
| Refresh reset | Explicit MTProto refresh replaced old measured rows with UNTESTED entries; progressive list reached 2000 at observation time. Screenshot `audit-20261026-refresh-final.png`. This was an ordinary refresh on the isolated AVD, not a database reset used to make tests pass. |
| WEB | Invalid localhost HTTP import rejected; unavailable remote feeds produced seven explicitly untested fallback records; trust dialog opened then cancelled. Configuration recreation cancelled an in-flight WEB fetch. |
| Crashes | First pass crash buffer was empty. The final bounded crash buffer contained native aborts from `com.google.android.bluetooth` during emulator boot (HciHalHidl startup assertion), with no ProxyX crash in the captured records. System UI ANRs also occurred at cold boot. These are recorded as emulator/platform issues; their exact cause was not established. |

## Remaining limitations and acceptance status

This is a verified set of changes, not a claim that every button, all networks, all Android versions or every source is bug-free. Remaining acceptance work:

1. Authorized TDLib login persistence, Saved Messages and real upload/download end-to-end on the final signed release were not run: confidential account/build configuration was deliberately excluded.
2. Live WEB handshake/cancellation after explicit provider trust, and successful real HTTPS transfer through a public generic proxy, remain unverified. Fixture tests do not replace them.
3. Android API 24, physical phone/mobile-carrier behavior, 120 Hz smoothness, thermal adaptation, Macrobenchmark and Baseline Profile generation were not measured. The low-RAM emulator is not representative hardware and had System UI/Bluetooth platform failures at boot. The dedicated audit AVD was stopped after verification to release host memory.
4. Migration fixtures cover 13->14, 14->15 and 15->16, not every historical database version. Main schema did not change; the separate generic database has no older schema to migrate.
5. Some auxiliary flows (all export/import/document pickers, notification actions, every scheduled-job condition, release update/catalog server administration) were not exhaustively driven on-device; ordinary unit tests cover only their tested contracts.
6. Signed release validation requires the owner's existing protected signing/Telegram configuration and a version/code decision. No replacement keys, application-ID change or version bump was made.

To build locally, use an ASCII project path and the documented SDK/JDK. Preserve protected local configuration separately; do not upload it with the source. The audit build is intentionally not an account-ready publication artifact. Generic results describe one HTTPS connectivity request, not a VPN, anonymity guarantee, Telegram support or file throughput.
