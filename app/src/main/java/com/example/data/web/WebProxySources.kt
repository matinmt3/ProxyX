package com.example.data.web

import com.example.model.WebProxyNode

object WebProxySources {
    val feeds = listOf("https://t.me/s/telmtproto", "https://t.me/s/wbnet", "https://t.me/s/uciranir")

    // Published public links, not verified endpoints. Never execute their pages on import.
    fun bootstrap(): List<WebProxyNode> = listOf(
        "max-speed.co.uk" to "84738086f0aa4f0c4f4b977e9d0b254e",
        "voidng.com" to "dd47243e334b95d5041bf86d662b75c99e",
        "proxy.btb.onl" to "c65971187df6f633ab62b21d03c4d504",
        "web.topproxt.asia" to "ddbb0e45caf3bbce0d8d995cfb919fa9d6",
        "one.nxrweb.shop" to "dd7287be476993a67190dbcbe946167b3c",
        "web.shmelproxy.top" to "dd05f548574d4e77a9d068a60933c8ba1c",
        "webtg-1.stepavpn.ru" to "ddc99839390b5db85d6f2160864e74c001"
    ).mapNotNull { (host, secret) -> WebProxyNode.parse("tg://webproxy?server=$host&secret=$secret")?.copy(source = "PUBLIC_BOOTSTRAP") }
}
