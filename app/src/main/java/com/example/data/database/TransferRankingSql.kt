package com.example.data.database

/** Keep these predicates aligned with TransferRankingEvidence; Room tests cover both paths. */
internal object TransferRankingSql {
    const val MEASURED = """(p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
        AND p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
        AND p.protocolSuccessRate > 0 AND p.telegramTransferMeasuredAt > 0
        AND p.telegramTransferMeasuredAt >= p.lastChecked AND p.telegramTransferRounds >= 3
        AND p.telegramDownloadKbps > 0 AND p.telegramUploadKbps > 0
        AND p.benchmarkFailureReason IN ('', 'STABILITY_LOW'))"""
    const val DOWNLOAD = """((CASE WHEN p.telegramDownloadFloorKbps > 0
        THEN min(p.telegramDownloadFloorKbps, p.telegramDownloadKbps)
        ELSE p.telegramDownloadKbps * 6 / 10 END) * 7 + p.telegramDownloadKbps * 3)"""
    const val UPLOAD = """((CASE WHEN p.telegramUploadFloorKbps > 0
        THEN min(p.telegramUploadFloorKbps, p.telegramUploadKbps)
        ELSE p.telegramUploadKbps * 6 / 10 END) * 7 + p.telegramUploadKbps * 3)"""
    const val BALANCED = "(" + DOWNLOAD + " * 3 + " + UPLOAD + " * 2)"
    const val TRANSFER_SORT = ":sort IN ('BEST', 'HIGH', 'DOWN_UP', 'DOWNLOAD', 'UPLOAD')"
    private const val PAGING_MEASURED = MEASURED +
        " AND (:currentNetworkFingerprint = '' OR p.networkFingerprint = :currentNetworkFingerprint)"
    private const val PREVIEW_MEASURED = MEASURED +
        " AND (:networkFingerprint = '' OR p.networkFingerprint = :networkFingerprint)"
    private const val HEALTHY = """p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
        AND p.protocolSuccessRate > 0 AND p.benchmarkFailureReason = ''
        AND p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')"""
    const val PAGING_PREFIX =
        "CASE WHEN " + TRANSFER_SORT + " AND " + PAGING_MEASURED + " THEN 1 ELSE 0 END DESC, " +
        "CASE WHEN " + TRANSFER_SORT + " AND " + PAGING_MEASURED +
        " AND :currentNetworkFingerprint != '' AND p.networkFingerprint = :currentNetworkFingerprint" +
        " THEN 1 ELSE 0 END DESC, " +
        "CASE WHEN :sort IN ('BEST', 'DOWN_UP') AND " + PAGING_MEASURED +
        " THEN " + BALANCED + " ELSE 0 END DESC, " +
        "CASE WHEN :sort IN ('HIGH', 'DOWNLOAD') AND " + PAGING_MEASURED +
        " THEN " + DOWNLOAD + " ELSE 0 END DESC, " +
        "CASE WHEN :sort = 'UPLOAD' AND " + PAGING_MEASURED +
        " THEN " + UPLOAD + " ELSE 0 END DESC, " +
        "CASE WHEN " + TRANSFER_SORT + " AND " + HEALTHY +
        " AND :currentNetworkFingerprint != '' AND p.networkFingerprint = :currentNetworkFingerprint" +
        " THEN 1 ELSE 0 END DESC, "
    const val PREVIEW_PREFIX =
        "CASE WHEN " + PREVIEW_MEASURED + " THEN 1 ELSE 0 END DESC, " +
        "CASE WHEN " + PREVIEW_MEASURED + " AND :networkFingerprint != ''" +
        " AND p.networkFingerprint = :networkFingerprint THEN 1 ELSE 0 END DESC, " +
        "CASE WHEN " + PREVIEW_MEASURED + " THEN " + BALANCED + " ELSE 0 END DESC, " +
        "CASE WHEN " + HEALTHY + " AND :networkFingerprint != ''" +
        " AND p.networkFingerprint = :networkFingerprint THEN 1 ELSE 0 END DESC, "
}
