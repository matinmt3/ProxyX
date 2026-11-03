# Telegram WEB Transport

`app/src/main/java/com/example/data/web/WebProxyTransport.java` is adapted from
the official Telegram Android implementation, authored by Telegram Android
contributors, under GPL-2.0.

Upstream file:
https://github.com/DrKLO/Telegram/blob/c84801762fd5f936c8296ecf09a14a48ebfc4fe4/TMessagesProj/src/main/java/org/telegram/utils/proxy/WebProxyTransport.java

Changes dated 2026-10-05: ProxyX context integration, one-shot foreground testing,
bounded executors and queues, restricted WebChromeClient, independent
document-start security policy, incoming frame validation and cleanup.

The APK must be distributed with corresponding source and the GPL-2.0 license.
No warranty is provided. Preserve the license and attribution in redistributed
copies. Independently licensed bundled components retain their own notices.

Protocol and interoperability references:
- https://github.com/telegramdesktop/tproxy-server/blob/master/PROTOCOL.md
- https://github.com/telegramdesktop/tproxy-server/blob/master/ANDROID.md
- https://github.com/telegramdesktop/tproxy-server/blob/master/BASE_PATH.md

Public starter links were found in https://t.me/s/telmtproto,
https://t.me/s/wbnet, https://t.me/s/uciranir and
https://t.me/s/TechWithSina on 2026-10-05. Inclusion is not a trust endorsement or
proof that an endpoint is alive. No provider JavaScript runs on import/refresh.
