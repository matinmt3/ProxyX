# TDLib Android runtime

ProxyX bundles the official TDLib JSONJava JNI runtime.

- TDLib version: 1.8.66
- TDLib commit: `022d60202e446ad1287b9fb68e687c8a0760788b`
- Source: https://github.com/tdlib/td
- OpenSSL: 3.5.7 LTS, statically linked
- Android NDK: 29.0.14206865
- Minimum Android API: 24
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`
- JNI library: `libtdjsonjava.so`

Bundled stripped library SHA-256 checksums:

- `arm64-v8a`: `F7E74C9304F8BD5F83EB090421E2A93EC7360FEDDB21CA35D863D9AD5938E63A`
- `armeabi-v7a`: `DEB196B46125E2F610EC57DFEC9D8185DE6C67430DC4E111AAA45B104361C32B`
- `x86_64`: `3D04C86EE57A1E33C30D6ED7FC223150590755DFFF214F3299BDB30A49808CCE`

The runtime was built from source with the official Android `JSONJava` target.
Each shared object is stripped for release and linked with a 16 KiB maximum
page size so it can be packaged for current Android devices.

The upstream build guide is at:
https://github.com/tdlib/td/blob/master/example/android/README.md

Telegram API credentials are intentionally not stored in source control. A
release build reads `telegram.apiId` and `telegram.apiHash` from the ignored
root `local.properties` file or from `PROXYX_TELEGRAM_API_ID` and
`PROXYX_TELEGRAM_API_HASH` environment variables.
