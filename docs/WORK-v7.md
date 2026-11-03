# ProxyX 7 implementation ledger

- [x] Transfer integrity, interleaved rounds, conservative statistics and explicit sample-data budget implemented.
- [x] Database migration and Best/download/upload ordering implemented; regression tests added.
- [x] Network-specific shortlist and bounded recheck after network changes implemented.
- [x] Signed release channels and catalog operator tooling implemented; hosting setup still pending owner details.
- [x] Simpler home and evidence labels; advanced controls retained.
- [x] Initial 167 unit tests and 6 device instrumentation tests passed.
- [x] Settings, language/theme, local favorites/comparison, 1800-count pause/resume/cancel and disabled unauthenticated transfer UI checked.
- [x] Regenerated release baseline/startup profiles on Medium_Phone (2026-09-20); two generator tests passed. Macrobenchmark tests were intentionally skipped by generation and must run separately.
- [x] Final 171 unit tests passed on 2026-09-20 after atomic benchmark-state/journal, catalog backup recovery and local-file removal guards.
- [x] Final Release APK/AAB and benchmark APK built successfully on 2026-09-20 in 9m14s. Lint: zero errors, 13 warnings. APK v2 signature matches the existing release certificate; AAB JAR verification passed with self-signed/timestamp/archive-order warnings.
- [x] Final minified APK Macrobenchmark: two tests passed in 77.159s (five cold starts, three navigation/scroll iterations). Emulator-only measurements, not physical-device performance claims. Rechecked 1800-count pause/resume/cancel and Settings without automatic login prompts.
- [x] Account-preserving upgrade 6.0.0 (60) to 7.0.0 (70): 1928 routes, favorites, Persian language and auto-refresh OFF preserved; TDLib restored READY without a login code. New favorite delivery has SYNCED state and a nonzero receipt, read only from ProxyX's Room table; no TDLib session files were accessed. Independent Telegram Desktop visual check unavailable.
- [ ] Native three-round transfer on the final APK. Incomplete: heavy host memory pressure and emulator ANRs interrupted acceptance. On 2026-09-21 the ProxyX startup ANR trace showed main in nativePollOnce, not an identified application lock; several Android system processes also ANRed. Do not label this a clean performance pass.
- [x] Rebuilt on 2026-09-21 after the final wording correction in 8m23s: READY means signed in, not proof of a currently working Telegram connection. All 171 unit tests passed again; Lint zero errors and six warnings.
- [x] Final APK/AAB signatures verified; APK ZIP and all six 64-bit ELF libraries have 16 KiB alignment. Packaged desktop APK/AAB with checksums and an honest test report; full native-transfer/physical-device acceptance remains incomplete.
- [x] Installed the exact delivered APK with install -r, confirmed version 70, restored ProxyX_Test display to physical 720x1280/320dpi, confirmed normal shell ADB and closed the emulator. No app data was removed.

Physical-device testing and deployment require an attached phone and owner-provided hosting details. Questions sent on 2026-09-14. No Android device was attached initially. Do not read, export or reset Telegram session data. The parent user-profile Git repository is not this project; do not use it for commits or resets.

## 2026-09-22 / 7.0.1 QA

- [x] Audited startup, transfer rounds, benchmark lifecycle, Vault, QR sharing and wrapped-key recovery.
- [x] Moved Vault/diagnostic file work off Main; bounded stream reads; export/import limits consistent; cancellation no longer swallowed in private-node imports.
- [x] Unique QR/export file names prevent overlapping shares from replacing previous content. Private-node read/update is serialized.
- [x] AtomicFile backup-only wrapped-key recovery fixed; malformed data rejected without replacement. No user key/session files accessed.
- [x] 183 unit tests across 40 suites passed, zero failures/errors/skips, 8m06s including compilation. Native Robolectric PNG QR decode passed for consecutive shares.
- [x] Further audit found deleteFile does not stop TDLib native downloads. Added explicit cancelDownloadFile before cache deletion and bounded TDLib file/message event maps at 128 entries while preserving the active transfer. Final rerun: 190 tests across 42 suites, all passed.
- [x] Telegram Desktop Saved Messages visually inspected: existing ProxyX templates and connection links are present. No new desktop message sent; this is not a fresh 7.0.1 favorite-send acceptance test.
- [x] 7.0.1 Release APK/AAB built in 8m44s with all 190 unit tests passing; Lint zero errors/six warnings, signatures verified, APK zipalign 16 KiB check passed. Installed the same signed APK with install -r.
- [x] Sep22-23 practical QA: auth restored without OTP, auto-refresh OFF preserved, progressive refresh from 1903/25 tested to 2191/0 tested, three favorites retained. Benchmark 25 completed with zero native-transfer candidates; pause/resume, Home and return-to-top checked. Vault JSON exported and reimported via Android picker; QR preview rendered. A manual native transfer failed honestly with Pong timeout expired and no stored throughput.
- [x] Sep26: verified Sep23 journal COMPLETED for 100 proxies, strict stage 20/20, zero native-transfer candidates; 2191 routes/100 measured/zero transfer results. Restored original unlimited payload budget and checked start/pause/cancel on 7.0.1.
- [x] 7.0.1 QA report updated; separate delivery superseded by the corrected 7.0.2 package below.
- [ ] Full native three-round transfer, fresh favorite-send and download-cancellation acceptance remain pending. On Sep26 no device or emulator was attached and host free RAM was 330-580 MiB. Do not call prior 7.0.0 tests new 7.0.1 results; do not access TDLib key/session files.

## 2026-09-26 / 7.0.2

- [x] User chose emulator-only acceptance. Preserve ProxyX_Test data and Telegram session; no phone requests needed again.
- [x] Found manual-transfer preparation/inter-round cancellation visibility gap. Added lifetime tracker, global top-bar Cancel across tabs, blocked overlapping row tests/favorite sync, and use the latest stored/reverified proxy for adaptive measurement.
- [x] Added four focused tracker tests; corrected QA helper's repeated-label selection and reverse-scroll start point.
- [x] 194 unit tests across 43 suites passed, zero failures/errors/skips; all four manual-transfer tracker regressions passed.
- [x] Final 7.0.2 (72) unit/Lint/APK/AAB build succeeded in 9m58s; artifacts/qa-20260926-release72-build.log. Initial attempt intentionally stopped before completion for the global Cancel fix. Lint zero errors/six warnings; signatures and 16 KiB ZIP alignment verified. APK SHA256 8e4987a207cf109aab12504e05e76473a11dd3203f47f887a437b2ccdff890c6.
- [x] Installed the exact final 7.0.2 APK with install -r. Auth retained; global Cancel worked across tabs during preparation. Fresh refresh: 2127 routes/zero old test results; favorites retained; dark/system theme and source-quality views checked.
- [x] Real three-round TDLib transfer completed on 91.234.147.10.aprawood.ir: 512 KiB + 2 MiB + 2 MiB per direction, first result 242/131 KiB/s, floors 236/131, evidence score 89. UI and Room result verified. Fresh favorite SYNCED with nonzero receipt and empty error; no independent Desktop observation of the new message.
- [x] Another complete transfer finished before a cancellation attempt; not counted as a cancel test. A separate attempt was cancelled at 2.3/4.0 MiB during download; READY/session preserved. Independent direct network test completed at 744/191 KiB/s. No ProxyX am_anr/am_crash in the checked final-run events; emulator Bluetooth crashed five times.
- [x] Package exact APK/AAB, checksums and updated reports. On Sep27 no emulator/build process was active; hashes matched the tested output.
- [ ] Remaining acceptance: multi-finalist native group run, full scheduled/private-node/network-change combinations, physical-device/cellular/120 Hz/16 KiB runtime and owner-hosted update deployment. User chose emulator-only; do not claim exhaustive or bug-free coverage.
