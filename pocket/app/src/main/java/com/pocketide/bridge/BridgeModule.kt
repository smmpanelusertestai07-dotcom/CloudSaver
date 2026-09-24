package com.pocketide.bridge

import com.pocketide.AppGraph
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun createPortBridge(graph: AppGraph): PortBridge = StubPortBridge().also { graph.hashCode() }

fun createPhoneBridge(graph: AppGraph): PhoneBridge = StubPhoneBridge().also { graph.hashCode() }

private class StubPortBridge : PortBridge {
    override fun expose(port: Int, purpose: String, injectHeaders: Map<String, String>): BridgedPort = throw UnsupportedOperationException("stub")
    override fun revoke(port: Int) = Unit
    override fun isInternal(url: String) = false
    override val exposed: List<BridgedPort> = emptyList()
    override fun shutdown() = Unit
}

private class StubPhoneBridge : PhoneBridge {
    override fun start(agentId: String) = Unit
    override fun stop(agentId: String) = Unit
    override fun handle(op: String, handler: suspend (String, JsonObject) -> JsonElement) = Unit
}
