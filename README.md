# ProxyX 8.0.0 Release Candidate

Native neon Android interface, 2026-11-03. The original 7.1.0 network and
Telegram features described below are retained. Current physical-phone evidence
and remaining limitations are in [the device report](docs/PHONE-QA-20261103.md).
The [earlier preview report](docs/NEON-VERIFICATION.md) is historical, not the
acceptance record for this release candidate.

Public source excludes owner Telegram API credentials, signing keys and account
data. Publication APKs use the owner's existing release certificate; the separate
ProxyX QA package uses a Debug certificate. Neither a successful build nor the
current API 26 phone run proves acceptance on every Android version. This is an
explicitly experimental release, not a bug-free or guaranteed-speed claim.

Build with your own Telegram API configuration and signing file. Private inputs
can stay outside this checkout using `-Pproxyx.telegramConfigFile=...` and
`-Pproxyx.signingConfigFile=...`. `-Pproxyx.requireReleaseSigning=true` rejects
unsigned or audit publication builds. See [release notes](docs/RELEASE-8.0.0-RC.md).

## Telegram WEB Proxies

Vault now has a separate WEB tab. It imports `tg://webproxy` and
`https://t.me/webproxy` links, supports hostname/base-path credentials, fetches
three public Telegram feeds and preserves manually imported entries during
public refresh. Public starter links are explicitly unverified and are used only
when feeds cannot be reached and no public WEB entries exist.

WEB testing is opt-in and foreground-only. The provider's page runs in a
restricted Android System WebView after a trust confirmation. Updated WebView
features are required; there is no SSL bypass, JavaScript-interface wildcard,
direct Telegram fallback, or automatic background carrier. Each test performs
DNS/public-address validation, authenticated WEB session setup, and three real
Telegram `req_pq_multi` route checks. Optional stage four uses the existing
TDLib account to upload/download random files in Saved Messages across three
rounds. Speeds are shown only for completed transfer evidence on the current
network. Test cancellation/foreground exit closes the carrier and local sockets.

WEB is not SOCKS or an HTTP CONNECT proxy, and WEB results do not enter ordinary
MTProto ranking. Opening a link in Telegram is separate from local testing and
requires a WEB-capable Telegram version. Availability on a laptop/emulator does
not guarantee availability on an Iranian cellular network.

The WEB transport adapts Telegram Android GPL-2.0 code. Distribute the matching
source archive and retain [license and attribution](third_party/telegram-web/NOTICE.md)
alongside the APK; bundled components retain their own licenses.

ProxyX is a Kotlin/Jetpack Compose Android app that collects, verifies, ranks, and opens public Telegram MTProto proxies. It uses Room, Paging, DataStore, coroutines, WorkManager, Baseline Profiles, and the official TDLib JSON runtime.

## What is measured

- Every route test uses the user's currently active Android network. Managed DNS, TCP, Fake-TLS, MTProto, and direct-speed sockets are bound to the captured network. Native TDLib uses Android's current network; guards invalidate a transfer when that network changes instead of mixing evidence.
- The three-stage benchmark measures TCP and genuine account-free Telegram responses, repeated finalist continuity, P90 latency, jitter, stability, and Transfer Score. It does not infer fake KB/s values from ping.
- `INCONCLUSIVE` distinguishes local DNS/network failures from a confirmed offline proxy, so temporary mobile-network problems are not pruned as dead servers.
- Optional authenticated TDLib mode uploads random files, removes their local cached copies, downloads them, and verifies their exact length and SHA-256. Real transfer figures are in KiB/s or MiB/s, not estimates derived from ping.
- Automatic finalist transfers run three interleaved rounds at equal sample sizes. Typical rates use the two longer confirmation rounds; the UI also shows their observed floor/peak. Best favors repeatable throughput, with a larger download than upload weight. A measured floor is not a guarantee of future speed.
- The file-payload budget includes both directions and reserves attempted samples even when they fail. TDLib/network overhead is extra. Tests run sequentially; all selected finalists receive three rounds unless a transfer fails, the session/network becomes unavailable, or the user cancels. There is no hidden finalist percentage or count cap.
- A direct Internet baseline is persisted per hashed network fingerprint. TDLib rates can then be presented as a share of that same connection's capacity, while stale or different-network evidence loses confidence.

## Reliability and performance

- `Data saver`, `Balanced`, and `Precise` plans change real sample counts, repeated rounds, and maximum TDLib confirmation size. Every qualifying route proceeds to the next stage.
- The UI shows a heuristic pre-run time/data estimate, supports arbitrary stored-proxy counts, and exposes Pause, Resume, and Cancel from the foreground notification. Duration depends on actual candidate yield and adaptive worker throttling; it is not a guaranteed deadline.
- Interrupted runs can restart safely within 24 hours without silently repeating TDLib data transfers.
- Optional scheduled checks test a bounded sample only while charging on an unmetered network.
- Thousands of rows use Room-backed Paging and database sorting. Collection and benchmark writes are committed in small chunks to avoid large UI bursts.
- Home defaults to Best, Download and Upload. Search and detailed sorting remain under Advanced filters. An optional network-change recheck uses up to six recent candidates, with a 90-second overall limit and no file transfer.
- Returning Home exposes active search/status/advanced-sort filters. A successful fresh collection clears those filters, measurements and the old benchmark journal/outcome; favorites and their explicitly historical last-good records remain separate.
- Small benchmark queues prioritize routes with genuine previous MTProto confirmations, followed by untested/old rows. All selected routes are tested afresh; previous evidence never counts as a new pass.
- Baseline Profile and Macrobenchmark modules cover startup, scrolling, and screen navigation.

## Source quality

- The bundled catalog contains 50 MTProto-only feeds. SOCKS sources are not accepted.
- Source health tracks last success, unique yield, duplicate rate, endpoint diversity, verified yield, 7/30-day history, and quarantine state.
- The source summary counts only enabled catalog entries whose latest recorded fetch succeeded with a non-empty unique yield, not every source that succeeded at some point in the past.
- GitHub sources try raw GitHub, jsDelivr, and GitHub's media endpoint, with cellular-friendly ordering.
- An optional signed remote catalog supports ECDSA verification, expiry, rollback rejection, URL validation, atomic caching, and bundled fallback. Signed stable/beta update feeds verify APK hash, package/version and signing certificate before offering installation. Owner hosting is not configured by default. See [publishing setup](docs/REMOTE-PUBLISHING.md).

## Privacy

ProxyX has no advertising or analytics SDK. TDLib sign-in is optional; session files and the database key stay in private no-backup storage. Favoriting sends the selected proxy only to the user's own Saved Messages. See the [Persian](docs/PRIVACY-POLICY-fa.md) and [English](docs/PRIVACY-POLICY-en.md) policies.

## Build and verify

The source ZIP excludes owner credentials, signing keys, caches, and build outputs.
It includes the three TDLib JNI binaries required to build the app. See
[source package setup](docs/SOURCE-PACKAGE-fa.md) and the
[7.1.0 verification report](docs/QA-2026-10-06-v710-en.md).

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease :app:bundleRelease
```

Telegram owner credentials belong in untracked `local.properties` as `telegram.apiId` and `telegram.apiHash`. Release signing belongs in untracked `keystore.properties` or the `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` environment variables. Never commit either secret set.

CI verifies unit tests, lint, a debug APK, and a release AAB. Device instrumentation includes TDLib native-runtime and Room 13-to-14 / 14-to-15 migration checks. Use the version-specific test report for what was actually run; the presence of a test module is not evidence of a passing device run.
