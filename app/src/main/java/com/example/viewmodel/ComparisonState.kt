package com.example.viewmodel

import com.example.model.MtProtoProxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeComparedProxies(
    selectedUrls: Flow<List<String>>,
    observeProxy: (String) -> Flow<MtProtoProxy?>
): Flow<List<MtProtoProxy>> = selectedUrls.distinctUntilChanged().flatMapLatest { urls ->
    if (urls.isEmpty()) flowOf(emptyList())
    else combine(urls.map(observeProxy)) { results -> results.filterNotNull() }
}
