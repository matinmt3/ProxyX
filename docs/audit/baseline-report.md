# ProxyX audit baseline

Audit date: 2026-10-26 (Asia/Tehran). Status: baseline unit suite and
online build/lint follow-up completed. Historical reports are not counted
as evidence for this audit.

## Source and environment

- Version 7.1.0, versionCode 75; package `com.aistudio.proxyx.vbnmkw`.
- `C:/Users/User/Desktop/proxyx` is now a junction to a directory with Persian
  path components. Android Gradle Plugin rejects its canonical path on Windows.
- A 232-file, secret-free copy was created at
  `C:/Users/User/Documents/Codex/2026-07-08/new-chat/work/proxyx-audit-20261026`.
  Its initial SHA-256 inventory is in the sibling file
  `proxyx-audit-20261026-baseline-manifest.json`. No private local configuration,
  signing keys, session files or user databases were copied.
- The enclosing Git root is the user profile, not a dedicated ProxyX repository.
  Existing staged/untracked user files were left intact. No commit or push.
- Windows, JDK 21 selected by Gradle toolchain, Gradle 9.3.1, AGP 9.1.1,
  Kotlin Compose plugin 2.2.10, KSP 2.3.5. AGP supplies Kotlin integration.
- Minimum API 24, compile SDK 36.1, target SDK 36, Java bytecode target 11.
- At initial inspection the 8 GiB laptop had about 375 MiB free RAM. Builds use
  one worker and a 1 GiB daemon heap. Device runs will not overlap compilation.

## Architecture traced

The app is native Android, with no separate web backend. Compose/Material 3
screens consume StateFlow and Paging through ProxyViewModel. AppContainer owns
Room, repositories, settings, the benchmark coordinator and TDLib.

- MTProto: ProxySourceManager (50 bundled feeds / optional signed catalog) ->
  ProxyCollector -> GithubSourceEndpointResolver -> ProxyContentParser ->
  normalized MtProtoProxy records -> progressive Room refresh. Source health,
  provenance and quarantine live in separate tables.
- Checks: UserNetworkSession -> DNS/public-address checks -> TCP samples ->
  MtProtoConnectionProbe (obfuscated req_pq_multi, optional Fake TLS) ->
  repeated three-stage benchmark -> DAO persistence and ranking queries.
- Authenticated file transfers use TDLib and separate measured byte/rate fields;
  TCP reachability and protocol score are not transfer throughput.
- Telegram WEB has separate nodes, DAO, controller, trust UI and restricted
  WebView carrier. It is not an HTTP CONNECT or SOCKS checker.
- Room schema is version 16, with registered migrations 1 through 16 and
  exported schemas 14, 15 and 16. Paging, DataStore, WorkManager, foreground
  services/jobs and a performance-test module are present.
- General HTTP/SOCKS sources are not supported by the Telegram data model.

## Commands and actual observations

Environment for the ASCII copy: `JAVA_HOME=C:/Program Files/Android/Android Studio/jbr`
and `ANDROID_HOME=C:/Users/User/AppData/Local/Android/Sdk`.

| Command | Actual result |
|---|---|
| `gradlew.bat :app:cleanTestDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-build-cache "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"` in Desktop project | Failed before tests: canonical project path contains non-ASCII characters. No path-security override added. |
| `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-build-cache "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"` in ASCII copy | Unit task executed: 304 tests, 57 suites, 0 failures/errors/skips (XML counted). Then lint model resolution failed because six instrumentation artifacts were absent from the offline cache. Overall command failed after 4m37s. |
| `gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"` | Passed after 6m03s. Debug APK built and lint completed. |

Logs: original project's `artifacts/audit-20261026-baseline.log`, plus sibling
`proxyx-audit-20261026-baseline-ascii.log` and `proxyx-audit-20261026-baseline-online.log`
beside the ASCII build copy. Fresh test XML is in that copy's
`app/build/test-results/testDebugUnitTest`.

Observed warnings: deprecated Java API usage in WebProxyTransport; a unit-test
manifest merge warning that the removed WorkManager initializer was not present.

## Audit leads, not yet demonstrated fixes

1. MTProto DNS uses a blocking native lookup without its own bounded execution.
2. Source HTTP client follows redirects automatically; catalog URL validation
   alone does not validate a later redirect or HTTPS downgrade.
3. Socket read timeouts are per read, not necessarily a total handshake deadline.
4. Telegram transfer writes match URL/network but lack ordering by observation
   time; stale same-network completions need regression coverage.
5. Source catalog merge deduplicates URLs but can retain colliding display names,
   while transient source status is keyed by display name.
6. UserNetworkSession detects a different Network object, but its handling of
   link/DNS changes within the same Network needs coverage.

No implementation fixes, database migrations, frontend changes, device tests,
or live-source success claims have been made at this baseline checkpoint.
