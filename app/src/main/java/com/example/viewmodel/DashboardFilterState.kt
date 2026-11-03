package com.example.viewmodel

fun shouldShowAdvancedFilters(query: String, status: StatusFilter, sort: SortBy): Boolean =
    query.isNotBlank() || status != StatusFilter.ALL ||
        sort !in setOf(SortBy.BEST, SortBy.DOWNLOAD, SortBy.UPLOAD)
