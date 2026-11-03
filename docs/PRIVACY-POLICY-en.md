# ProxyX Privacy Policy

Last updated: October 26, 2026

ProxyX contains no advertising or behavioral analytics service and does not send personal data to the developer.

## Data and network use

- The app uses the device's active Internet connection to fetch public MTProto feeds and perform network tests.
- Test results, favorites, settings, and source-quality history are stored locally on the device.
- A hashed network fingerprint is used to detect route changes. Raw Wi-Fi names and network identifiers are not included in exported diagnostics.
- Direct speed tests and authenticated TDLib transfers consume data. The app shows a pre-run estimate and provides a transfer data cap.

## Telegram and TDLib

- Signing in to TDLib is optional.
- TDLib session files and the database key stay in private no-backup storage.
- Favoriting a proxy sends only that proxy's connection link and measurements to the user's own Saved Messages.
- Phone numbers, login codes, two-step passwords, session keys, proxy secrets, and message content are not sent to the developer.

## WEB providers

- Importing a WEB link does not execute provider JavaScript. Testing requires an explicit trust confirmation and runs only while the WEB screen is in the foreground.
- The provider page runs in a restricted WebView without access to the app's TDLib session, private files, clipboard or device permissions. SSL errors are not bypassed.
- A provider can observe the client's IP address, authenticated bridge requests, and encrypted traffic sizes/timing. Its server logging and privacy practices are independent of ProxyX; inclusion in a public feed is not a trust endorsement.
- Optional WEB file tests send three random files to the user's own Saved Messages and upload/download about 9 MiB of payload, plus protocol overhead. Generated messages may remain in Saved Messages; these tests do not delete unrelated Telegram content.
- WEB measurements are stored locally and are shown as real transfer speeds only after completed Telegram transfers on the same active network.

## Generic HTTP/SOCKS diagnostics

- This optional Vault screen is separate from Telegram MTProto and WEB. It does not configure a VPN or change system proxy settings.
- Explicit refreshes fetch public text lists from ProxySpace and Proxy Scraper. The app caches successful fetches for at least 20 and 60 minutes respectively. Source-reported country is not independently verified exit location.
- Explicit tests use the device's captured active network. The proxy receives a small request to Google's connectivity test endpoints (`connectivitycheck.gstatic.com` and `www.gstatic.com`); HTTPS uses normal certificate and hostname verification. No account data or personal payload is sent. Providers can observe connection IPs, destinations and timing.
- Endpoints, provenance and results are stored in a separate local database. Authentication credentials are not imported or stored. Test results are not uploaded to external checker websites or the developer. Public-list inclusion is not a safety endorsement.
- Leaving the screen or backgrounding the app cancels generic tests. The Settings data-clear action includes this database.

## Data deletion

Users can clear proxy data in Settings and delete the TDLib session by signing out of Telegram Transfer mode. Uninstalling the app also removes its private local data.

App support: [SymexVPN channel](https://t.me/SymexVPN)
