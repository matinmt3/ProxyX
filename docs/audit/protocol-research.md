# Protocol research - 2026-10-26

## Scope and evidence

Inspected the current Android code and these primary references during this audit:

- [Telegram MTProto](https://core.telegram.org/mtproto) and [proxy links](https://core.telegram.org/proxy).
- [Android Network](https://developer.android.com/reference/android/net/Network) and [ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager).
- [SOCKS5 RFC 1928](https://www.rfc-editor.org/rfc/rfc1928), [authentication RFC 1929](https://www.rfc-editor.org/rfc/rfc1929), [HTTP CONNECT RFC 9110](https://httpwg.org/specs/rfc9110.html#CONNECT), and [TLS RFC 8446](https://www.rfc-editor.org/rfc/rfc8446).
- [Telegram WEB protocol](https://github.com/telegramdesktop/tproxy-server/blob/master/PROTOCOL.md), [Android bridge](https://raw.githubusercontent.com/telegramdesktop/tproxy-server/master/ANDROID.md), and [base paths](https://raw.githubusercontent.com/telegramdesktop/tproxy-server/master/BASE_PATH.md).
- [Pinned Telegram Android transport](https://raw.githubusercontent.com/DrKLO/Telegram/c84801762fd5f936c8296ecf09a14a48ebfc4fe4/TMessagesProj/src/main/java/org/telegram/utils/proxy/WebProxyTransport.java). Existing GPL notices remain in third_party/telegram-web.
- [OkHttp 4.10.0 RealConnection](https://raw.githubusercontent.com/square/okhttp/parent-4.10.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RealConnection.kt). The supplied documentation root returned 404 through the research tool; the version-pinned upstream source was inspected instead.

## Existing contracts

MTProto parsing normalizes supported links/secrets; TCP opening alone is not Telegram verification. The probe establishes an obfuscated transport, optionally FakeTLS, and validates the nonce in a Telegram resPQ reply. Three-stage checks retain repeated observations, P90, jitter and continuity. Only optional authenticated TDLib transfer supplies real file rates. No rate is inferred from ping.

Telegram WEB is a separate trusted-provider feature, not HTTP CONNECT. Its bridge binds origin, base path and a per-instance nonce, rejects non-main-frame messages and certificate errors, and restricts WebView capabilities. The native loopback sidecar is intentional, not a public proxy address. WebView carrier routing cannot be bound using the Java Network socket factory; network identity is checked before accepting evidence. This platform limitation must remain explicit.

Android Network DNS and socket factories select a captured network. A Network handle alone is insufficient freshness evidence when its link properties change. Native DNS may ignore interruption: cancellation must stop waiting, bounded executors must prevent unbounded blocked threads, and no result should survive a changed network identity.

Socket read timeout is per read, not an overall handshake deadline. Slow fragmented responses require a separate operation deadline which closes the socket. Coroutine cancellation must remain cancellation, not an offline result.

Source HTTP clients must retain certificate validation, reject private addresses and insecure/credential-bearing redirects, bound decompressed bodies, and cancel active calls. Signed catalogs retain expiry, rollback and atomic-cache rules, and must actually enforce the documented P-256 key curve.

## Generic compatibility decision

Generic endpoints must have a separate registry/model/results. A list's HTTPS label normally advertises CONNECT capability, not TLS to the proxy endpoint. HTTP forwarding, CONNECT, SOCKS4, SOCKS4a and SOCKS5 must not be guessed from ports. Country from a feed is source metadata, not locally verified exit location.

OkHttp 4.10.0 uses the configured socket factory for DIRECT/HTTP, but constructs Socket(proxy) for SOCKS. Merely setting its socket factory does not prove SOCKS network binding. Any generic checker must explicitly solve this before claiming support. Authentication and unimplemented protocols must be reported as unsupported, never successful. No user endpoints have been submitted to external checkers.

## Test strategy

Also inspected the original [SOCKS4](https://www.openssh.org/txt/socks4.protocol) and [SOCKS4a](https://www.openssh.org/txt/socks4a.protocol) specifications and Android [SSLParameters endpoint identification](https://developer.android.com/reference/javax/net/ssl/SSLParameters). The generic checker implements only TCP CONNECT with empty SOCKS4 USERID and SOCKS5 no-auth negotiation. It never attempts hidden credentials, UDP or BIND. HTTPS destination identity is verified by the platform SSLSocket with endpoint identification set to HTTPS; no custom trust manager is installed.

Use existing JUnit/Robolectric/Room tests, controlled socket streams and local fixtures for timeout, cancellation, malformed replies, redirect validation, stale writes, source collisions and migration preservation. Live feed HTTP success only establishes fetch availability, not working proxy service. Device verification is a separate acceptance gate; historical reports and desktop requests do not establish phone-network behavior.
