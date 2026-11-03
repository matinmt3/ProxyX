package com.example.data.telegram

import com.example.model.MtProtoProxy

internal object SavedProxyMessageVerifier {
    fun requireMatchingMessage(
        message: JsonObject,
        expectedChatId: Long,
        proxy: MtProtoProxy
    ): Long {
        require(message.stringValue("@type") == "message") {
            "TDLib could not read the delivered Saved Messages entry."
        }
        require(message.longValue("chat_id") == expectedChatId) {
            "The delivered proxy message was not stored in Saved Messages."
        }
        val messageId = requireNotNull(message.longValue("id")) {
            "The delivered Saved Messages entry has no message ID."
        }
        val content = requireNotNull(message.objectValue("content")) {
            "The delivered Saved Messages entry has no content."
        }
        require(content.stringValue("@type") == "messageText") {
            "The delivered Saved Messages entry is not a text message."
        }
        val formattedText = requireNotNull(content.objectValue("text")) {
            "The delivered Saved Messages entry has no formatted text."
        }
        val plainText = formattedText.stringValue("text").orEmpty()
        require(
            plainText.contains("ProxyX") &&
                plainText.contains(proxy.server) &&
                plainText.contains(proxy.port.toString())
        ) {
            "The Saved Messages entry does not contain the selected proxy details."
        }
        require(formattedText.containsProxyLink(proxy)) {
            "The Saved Messages entry does not contain a valid proxy connection link."
        }
        return messageId
    }

    private fun JsonObject.containsProxyLink(proxy: MtProtoProxy): Boolean {
        val entities = this["entities"] as? List<*> ?: return false
        return entities.any { rawEntity ->
            @Suppress("UNCHECKED_CAST")
            val entity = rawEntity as? JsonObject ?: return@any false
            val type = entity.objectValue("type") ?: return@any false
            if (type.stringValue("@type") != "textEntityTypeTextUrl") return@any false
            val url = type.stringValue("url") ?: return@any false
            url == proxy.url || MtProtoProxy.parseFromUrl(url)?.url == proxy.url
        }
    }
}
