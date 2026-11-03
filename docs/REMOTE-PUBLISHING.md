# Owner-operated publishing

The app supports an ECDSA-signed source catalog and stable/beta release manifests. No hosting account, domain or publishing credentials have been supplied. The APK must therefore show "not configured", not claim to have checked an online service.

## One-time configuration

1. Use the Android Studio JDK to run `tools/ReleaseSigner.java keygen <private-directory-outside-project>`. Secure that directory with owner-only permissions and keep an offline backup. Never publish the PKCS8 private key.
2. Host static JSON and APK files on your own public HTTPS server. Direct URLs must return 200 without redirects. Public-key verification is in addition to HTTPS, not a replacement.
3. Add `sourceCatalog.url`, `sourceCatalog.publicKey`, `releaseManifest.url` and `releaseManifest.publicKey` to the ignored `local.properties`. The release URL may contain `{channel}`, replaced by `stable` or `beta`. Values can instead come from matching `PROXYX_*` environment variables documented in the build script.
4. Rebuild once with those settings. Future source changes and signed releases can then be published without changing those URLs. These public verification keys are not the APK signing key.

## Source catalog payload

Use schemaVersion 1, issuedAt/expiresAt in Unix milliseconds, mode `MERGE` or `REPLACE`, and sources containing name, url, description and enabled. Validity is at most 90 days. In MERGE mode, an entry with enabled=false revokes the same bundled URL. REPLACE uses only the supplied list. All feed URLs must be public HTTPS. The app verifies signatures, rejects rollback and atomically caches the last valid envelope; expired catalogs fall back to bundled feeds.

## App release

Create a payload with `tools/New-ReleasePayload.ps1`, passing the signed APK, its direct HTTPS URL, exact version code/name, channel and a new output filename. Sign it using `java tools/ReleaseSigner.java sign <private-key.pk8> <payload.json> <new-envelope.json>` and publish the envelope at the configured channel URL.

The client verifies signature, validity, package, Android compatibility and monotonic manifest time. Download is explicit and cancellable. The downloaded APK must match its declared size, SHA-256, package/version and the currently installed signing certificate. Installation requires user confirmation through Android. Certificate rotation is deliberately unsupported in this version; use the same release keystore. Do not uninstall the app to upgrade, because uninstalling removes its local Telegram session.

`REQUEST_INSTALL_PACKAGES` is used for this direct-distribution updater. Before publishing to an app store, review that store's eligibility and update-distribution rules; this build is not a claim of store approval.
