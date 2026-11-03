package com.example.viewmodel

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.example.data.generic.*
import com.example.data.network.NetworkUsagePolicy
import com.example.data.network.UserNetworkException
import com.example.data.repository.ProxyRepository
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class GenericTestState(val running: Boolean = false, val activeId: String? = null,
    val completed: Int = 0, val total: Int = 0, val succeeded: Int = 0, val message: String = "") {
    fun withSourceProgress(value: Int): GenericTestState =
        copy(completed = maxOf(completed, value).coerceAtMost(total))
}

@OptIn(ExperimentalCoroutinesApi::class)
class GenericProxyController(context: Context, private val scope: CoroutineScope,
    private val repository: GenericProxyRepository, private val settings: SettingsRepository,
    private val telegramRepository: ProxyRepository, network: Flow<String>, private val canStart: () -> Boolean) {
    private val policy = NetworkUsagePolicy(context)
    private val mutableState = MutableStateFlow(GenericTestState())
    val state = mutableState.asStateFlow()
    val query = MutableStateFlow("")
    val protocol = MutableStateFlow("ALL")
    val count = repository.dao.count()
    val health = repository.dao.health()
    val pages = combine(query, protocol, network.distinctUntilChanged()) { query, protocol, network -> Triple(query, protocol, network) }
        .flatMapLatest { (query, protocol, network) ->
            Pager(PagingConfig(50, enablePlaceholders = false)) { repository.dao.paging(query, protocol, network) }.flow
        }.cachedIn(scope)
    private var job: Job? = null

    fun refresh() = start {
        mutableState.value = GenericTestState(running = true, total = GenericSources.feeds.size, message = "FETCHING")
        val result = repository.refresh { done -> mutableState.update { it.withSourceProgress(done) } }
        mutableState.value = GenericTestState(message = "SOURCES:${result.downloaded}:${result.cached}:${result.failed}")
    }

    fun test(node: GenericProxy) = start { testNodes(listOf(node)) }

    fun testMany(input: String) {
        val requested = input.trim().toIntOrNull()?.takeIf { it > 0 }
        if (requested == null) { mutableState.update { it.copy(message = "INVALID_COUNT") }; return }
        start {
            val rows = repository.dao.snapshot().filter {
                (protocol.value == "ALL" || it.protocol == protocol.value) && it.host.contains(query.value, true)
            }.take(requested)
            if (rows.isEmpty()) mutableState.value = GenericTestState(message = "EMPTY") else testNodes(rows)
        }
    }

    private suspend fun testNodes(nodes: List<GenericProxy>) {
        mutableState.value = GenericTestState(running = true, total = nodes.size, message = "TESTING")
        for (node in nodes) {
            currentCoroutineContext().ensureActive()
            policy.requireAllowed(settings.unmeteredOnly.first())
            mutableState.update { it.copy(activeId = node.id) }
            val result = repository.test(node)
            mutableState.update { it.copy(completed = it.completed + 1,
                succeeded = it.succeeded + if (result.status == "WORKING") 1 else 0) }
        }
        mutableState.update { it.copy(running = false, activeId = null, message = "FINISHED") }
    }

    private fun start(block: suspend () -> Unit) {
        if (state.value.running || job?.isActive == true || !canStart() || !telegramRepository.beginForegroundBenchmark()) {
            mutableState.update { it.copy(message = "BUSY") }; return
        }
        mutableState.value = GenericTestState(running = true)
        job = scope.launch {
            try {
                policy.requireAllowed(settings.unmeteredOnly.first())
                block()
            } catch (e: CancellationException) {
                mutableState.update { it.copy(message = "CANCELLED") }; throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(message = when (e) {
                    is UserNetworkException -> "INCONCLUSIVE"
                    is com.example.data.network.MeteredNetworkNotAllowedException -> "UNMETERED_REQUIRED"
                    else -> "FAILED"
                }) }
            } finally {
                mutableState.update { it.copy(running = false, activeId = null) }
                telegramRepository.endForegroundBenchmark()
            }
        }
    }

    fun cancel() { job?.cancel() }
    suspend fun clear() { repository.clear(); mutableState.value = GenericTestState() }
}
