package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.security.ApprovalManager
import io.github.arttvad9r.hermesbridge.security.ApprovalTicket
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

object BridgeApprovalRuntime {
    private val manager = ApprovalManager()
    private val mutablePendingTickets = MutableStateFlow<List<ApprovalTicket>>(emptyList())

    val pendingTickets: StateFlow<List<ApprovalTicket>> = mutablePendingTickets.asStateFlow()

    fun request(
        tool: String,
        risk: ToolRisk,
        arguments: JsonObject,
        displaySummary: String,
    ): ApprovalTicket {
        val ticket = manager.request(tool, risk, arguments, displaySummary)
        publish()
        return ticket
    }

    fun approve(id: String): ApprovalTicket? {
        val result = manager.approve(id)
        publish()
        return result
    }

    fun deny(id: String): ApprovalTicket? {
        val result = manager.deny(id)
        publish()
        return result
    }

    fun consumeApproved(tool: String, arguments: JsonObject): ApprovalTicket? {
        val result = manager.consumeApproved(tool, arguments)
        publish()
        return result
    }

    fun refresh() {
        publish()
    }

    fun clear() {
        manager.clear()
        publish()
    }

    private fun publish() {
        mutablePendingTickets.value = manager.pending()
    }
}
