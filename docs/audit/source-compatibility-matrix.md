# Source compatibility - 2026-10-26

Research observations are point-in-time, not guarantees of working endpoints. All supplied addresses use HTTPS. Tool-level failures below are not invented HTTP status codes. No public proxies were tested by an external checker or uploaded to it. No CAPTCHA, login or paywall was bypassed.

| Source and inspected URL | Observed availability / format | Protocol / country | Access and integration status | Verification and limitations |
|---|---|---|---|---|
| [SPYS.ONE](https://spys.one/free-proxy-list/IR/) | HTML available; extracted ports absent | HTTP/HTTPS/SOCKS5; IR | Disabled: reliable extraction not established | robots permits crawling; obfuscated port output not executed |
| [PremProxy](https://premproxy.com/proxy-by-country/Iran-Islamic-Republic-of-01.htm) | HTTP 404 | Unknown current data | Disabled | No valid feed verified |
| [ProxyNova](https://www.proxynova.com/proxy-server-list/country-ir/) | Research tool denied by robots | Not inspected | Disabled/restricted | No bypass attempted |
| [Free-Proxy.cz](https://free-proxy.cz/en/proxylist/country/IR/all/ping/all) | Research fetch 502 | Unknown current data | Disabled | Availability and extraction unverified |
| [HideMy.name list](https://hidemy.name/en/proxy-list/?country=IR#list) | Tool could not access supplied page | Unknown current list | Disabled | Checker redirects to hide.mn; that does not verify list access |
| [Proxy Spider](https://proxy-spider.com/proxies/locations/ir-iran-islamic-republic-of) | HTML sample rows | Protocol not explicit; IR | Disabled: full API/download requires account/paid plan | No protocol inferred from port; no credentials used |
| [ProxyStack](https://proxystack.com/proxies-by-country/ir) | Inaccessible through tool | Unknown | Disabled | No HTTP status invented |
| [ProxyDocker](https://www.proxydocker.com/en/proxylist/country/Iran) | HTML records available | HTTP/HTTPS; IR | Disabled pending permitted stable extraction | Downloads/API link to plans/login |
| [FreeProxyUpdate](https://freeproxyupdate.com/iran-ir) | HTML records available | HTTP/HTTPS/SOCKS5; IR | Disabled pending automation terms and raw format verification | robots retrieval inaccessible through tool; no parser claimed |
| [ProxyRanker](https://proxyranker.com/iran_islamic_republic_of/) | Inaccessible through tool | Unknown | Disabled | No parser claimed |
| [FreeProxy.World](https://www.freeproxy.world/?country=IR) | HTML records available | HTTP/HTTPS/SOCKS4/SOCKS5; IR | Disabled pending permitted stable extraction | robots retrieval inaccessible through tool |
| [Litport](https://litport.net/free-proxy/iran) | HTML records available | HTTP/SOCKS4 observed; IR | Disabled: API/export requires registration | Public preview is not API authorization |
| [ProxySpace](https://proxyspace.pro/country/ir/) | Public raw downloads available | Per-protocol HTTP/HTTPS/SOCKS4/SOCKS5; source DB-IP country | Implemented: four typed files in the independent generic module; cache at least 20 minutes | GenericProxyTest parser/protocol/Room fixtures passed. Device fetch accepted 37 HTTP, 1 CONNECT, 32 SOCKS4 and 7 SOCKS5 records. These are untested source records. all.txt loses type and is not used. [API terms](https://proxyspace.pro/api/) |
| [ProxyHub](https://proxyhub.me/en/ir-free-proxy-list.html) | HTML records available | HTTP/SOCKS4/SOCKS5; IR | Disabled pending permitted stable extraction | robots retrieval inaccessible through tool |
| [ProxyLister](https://proxylister.com/free-proxies/country/iran) | HTML records; download/API controls | Protocol labels; IR | Disabled pending documented endpoint/permission verification | Provider working claim is not local evidence |
| [ProxyCheck](https://proxycheck.app/free-proxies/ir) | Research fetch timeout | Undetermined list/checker role | Disabled | Cannot honestly classify service without content |
| [ProxyVerity](https://proxyverity.com/free-proxy-list/ir/) | HTML but masked endpoints | HTTP/SOCKS4 metadata; IR | Disabled: no usable endpoint data | Masking not bypassed |
| [Proxy Scraper](https://maximilianfeix.github.io/proxy-scraper/country/ir/) | Public typed text download | socks4 observed; IR metadata | Implemented: typed public download, >=60-minute cache; repository MIT license | GenericProxyTest typed parser/provenance fixtures passed. Device fetch accepted 1 record (feed changed since initial laptop observation of 2). Provider checks are not user-network checks. [License](https://raw.githubusercontent.com/maximilianfeix/proxy-scraper/main/LICENSE) |
| [HideMy.name checker](https://hidemy.name/en/proxy-checker/) | Redirects to https://hide.mn/en/proxy-checker/; web form | Advertises HTTP/HTTPS/SOCKS4/5 | Not a list adapter; no public API verified | No submissions |
| [CheckerProxy](https://checkerproxy.net/) | Web form and API documentation link | Proxy checker | Disabled external service: account API key required | [Inspected API introduction](https://docs.checker.net/); sharing requires explicit opt-in; no submissions |
| [ProxyChecker.ge](https://www.proxychecker.ge/) | Web form available via research tool | Advertises GET/POST/CONNECT tests | No public API verified; disabled | No submissions; web crawl may be stale |

For each of the sixteen disabled list providers and all three checker services, adapter-specific automated tests are not applicable: no adapter was implemented, and no successful integration is claimed. The two enabled providers share the tested typed parser, bounded HTTP policy, separate Room/provenance model and cancellation-aware local checker. All five actual feeds fetched on the audit emulator; a second refresh used five cached results and issued no refetch. Tests are recorded in GenericProxyTest, NetworkAuditRegressionTest and GenericProxyCopyTest, with live UI evidence in final-verification.md.

The existing 50 bundled MTProto sources are not replaced by these generic sites. Thirty-nine MTProto feeds remain enabled after eleven confirmed 404 endpoints were disabled. Country metadata does not prove physical location, throughput, safety or Telegram support. None of the external checkers received any endpoint or result.
