package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.MtProtoProxy
import com.example.model.BenchmarkRunRecord
import com.example.model.FavoriteProxy
import com.example.model.ProxyTestHistory
import com.example.model.ProxySourceHealth
import com.example.model.ProxySourceMembership
import com.example.model.PrivateProxyNode
import com.example.model.SourceFetchRecord
import com.example.model.NetworkSpeedBaseline

@Database(
    entities = [
        MtProtoProxy::class,
        ProxySourceHealth::class,
        ProxySourceMembership::class,
        FavoriteProxy::class,
        ProxyTestHistory::class,
        BenchmarkRunRecord::class,
        SourceFetchRecord::class,
        PrivateProxyNode::class,
        NetworkSpeedBaseline::class,
        com.example.model.WebProxyNode::class
    ],
    version = 16,
    exportSchema = true
)
abstract class ProxyDatabase : RoomDatabase() {
    abstract fun proxyDao(): ProxyDao
    abstract fun sourceHealthDao(): ProxySourceHealthDao
    abstract fun webProxyDao(): WebProxyDao

    companion object {
        @Volatile
        private var INSTANCE: ProxyDatabase? = null

        fun getDatabase(context: Context): ProxyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ProxyDatabase::class.java,
                    "proxy_x_database"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16
                )
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN downloadKbps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN uploadKbps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN speedCategory TEXT NOT NULL DEFAULT 'UNTESTED'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN verificationStatus TEXT NOT NULL DEFAULT 'UNTESTED'")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN verificationConfidence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN protocolLatency INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN protocolSuccessRate REAL NOT NULL DEFAULT 0")
                // Version 2 only measured TCP reachability. Force a fresh strict benchmark so an
                // old positive result cannot be presented as Telegram-verified after migration.
                db.execSQL(
                    """
                    UPDATE mtproto_proxies SET
                        isWorking = 0,
                        latency = 0,
                        successRate = 0,
                        stability = 0,
                        downloadKbps = 0,
                        uploadKbps = 0,
                        speedCategory = 'UNTESTED',
                        score = 0,
                        lastChecked = 0
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE mtproto_proxies_real (
                        url TEXT NOT NULL,
                        server TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        secret TEXT NOT NULL,
                        country TEXT NOT NULL,
                        countryCode TEXT NOT NULL,
                        isWorking INTEGER NOT NULL,
                        latency INTEGER NOT NULL,
                        successRate REAL NOT NULL,
                        stability REAL NOT NULL,
                        score INTEGER NOT NULL,
                        isFavorite INTEGER NOT NULL,
                        recentlyUsed INTEGER NOT NULL,
                        lastChecked INTEGER NOT NULL,
                        verificationStatus TEXT NOT NULL,
                        verificationConfidence INTEGER NOT NULL,
                        protocolLatency INTEGER NOT NULL,
                        protocolSuccessRate REAL NOT NULL,
                        tcpAttempts INTEGER NOT NULL,
                        tcpSuccesses INTEGER NOT NULL,
                        protocolAttempts INTEGER NOT NULL,
                        protocolConfirmations INTEGER NOT NULL,
                        PRIMARY KEY(url)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO mtproto_proxies_real (
                        url, server, port, secret, country, countryCode,
                        isWorking, latency, successRate, stability, score,
                        isFavorite, recentlyUsed, lastChecked,
                        verificationStatus, verificationConfidence,
                        protocolLatency, protocolSuccessRate,
                        tcpAttempts, tcpSuccesses, protocolAttempts, protocolConfirmations
                    )
                    SELECT
                        url, server, port, secret, 'Global', 'UN',
                        0, 0, 0, 0, 0,
                        isFavorite, recentlyUsed, 0,
                        'UNTESTED', 0,
                        0, 0,
                        0, 0, 0, 0
                    FROM mtproto_proxies
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE mtproto_proxies")
                db.execSQL("ALTER TABLE mtproto_proxies_real RENAME TO mtproto_proxies")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM mtproto_proxies " +
                        "WHERE lower(url) LIKE 'tg://socks?%' OR secret = ''"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN tcpP90Latency INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN tcpJitter INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN protocolP90Latency INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN protocolJitter INTEGER NOT NULL DEFAULT 0")
                // The stricter six-sample/four-probe benchmark is not comparable to old results.
                db.execSQL(
                    """
                    UPDATE mtproto_proxies SET
                        isWorking = 0,
                        latency = 0,
                        successRate = 0,
                        stability = 0,
                        score = 0,
                        lastChecked = 0,
                        verificationStatus = 'UNTESTED',
                        verificationConfidence = 0,
                        protocolLatency = 0,
                        protocolSuccessRate = 0,
                        tcpAttempts = 0,
                        tcpSuccesses = 0,
                        protocolAttempts = 0,
                        protocolConfirmations = 0,
                        tcpP90Latency = 0,
                        tcpJitter = 0,
                        protocolP90Latency = 0,
                        protocolJitter = 0
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 7 adds Fake-TLS verification and a ten-TCP/eight-MTProto profile.
                // Old results cannot satisfy or be compared with the new evidence contract.
                db.execSQL(
                    """
                    UPDATE mtproto_proxies SET
                        isWorking = 0,
                        latency = 0,
                        successRate = 0,
                        stability = 0,
                        score = 0,
                        lastChecked = 0,
                        verificationStatus = 'UNTESTED',
                        verificationConfidence = 0,
                        protocolLatency = 0,
                        protocolSuccessRate = 0,
                        tcpAttempts = 0,
                        tcpSuccesses = 0,
                        protocolAttempts = 0,
                        protocolConfirmations = 0,
                        tcpP90Latency = 0,
                        tcpJitter = 0,
                        protocolP90Latency = 0,
                        protocolJitter = 0
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN transferScore INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN benchmarkStage INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN benchmarkRounds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN continuityRate REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN networkFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN testedNetworkType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN telegramDownloadKbps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN telegramUploadKbps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN telegramTransferBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN telegramTransferMeasuredAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS proxy_source_health (
                        sourceUrl TEXT NOT NULL,
                        sourceName TEXT NOT NULL,
                        lastAttemptAt INTEGER NOT NULL,
                        lastSuccessAt INTEGER NOT NULL,
                        totalFetches INTEGER NOT NULL,
                        successfulFetches INTEGER NOT NULL,
                        consecutiveFailures INTEGER NOT NULL,
                        rawProxyCount INTEGER NOT NULL,
                        uniqueProxyCount INTEGER NOT NULL,
                        verifiedProxyCount INTEGER NOT NULL,
                        duplicateRate REAL NOT NULL,
                        verifiedRate REAL NOT NULL,
                        averageDurationMs INTEGER NOT NULL,
                        quarantineUntil INTEGER NOT NULL,
                        lastError TEXT NOT NULL,
                        PRIMARY KEY(sourceUrl)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS proxy_source_membership (
                        sourceUrl TEXT NOT NULL,
                        proxyUrl TEXT NOT NULL,
                        PRIMARY KEY(sourceUrl, proxyUrl)
                    )
                    """.trimIndent()
                )
                db.execSQL("UPDATE mtproto_proxies SET score = 0, transferScore = 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mtproto_proxies_lastChecked " +
                        "ON mtproto_proxies(lastChecked)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mtproto_proxies_isWorking_latency " +
                        "ON mtproto_proxies(isWorking, latency)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_mtproto_proxies_verificationStatus_benchmarkStage_" +
                        "benchmarkRounds_transferScore ON mtproto_proxies(" +
                        "verificationStatus, benchmarkStage, benchmarkRounds, transferScore)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mtproto_proxies_countryCode_transferScore " +
                        "ON mtproto_proxies(countryCode, transferScore)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_proxy_source_membership_proxyUrl " +
                        "ON proxy_source_membership(proxyUrl)"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE mtproto_proxies ADD COLUMN " +
                        "benchmarkFailureReason TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS favorite_proxies (
                        url TEXT NOT NULL,
                        server TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        secret TEXT NOT NULL,
                        country TEXT NOT NULL,
                        countryCode TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        savedMessageId INTEGER NOT NULL,
                        syncState TEXT NOT NULL,
                        lastSyncError TEXT NOT NULL,
                        PRIMARY KEY(url)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_favorite_proxies_isActive " +
                        "ON favorite_proxies(isActive)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_favorite_proxies_updatedAt " +
                        "ON favorite_proxies(updatedAt)"
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO favorite_proxies (
                        url, server, port, secret, country, countryCode, isActive,
                        createdAt, updatedAt, savedMessageId, syncState, lastSyncError
                    )
                    SELECT
                        url, server, port, secret, country, countryCode, 1,
                        lastChecked, lastChecked, 0, 'LOCAL_ONLY', ''
                    FROM mtproto_proxies
                    WHERE isFavorite = 1
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS proxy_test_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        proxyUrl TEXT NOT NULL,
                        measuredAt INTEGER NOT NULL,
                        benchmarkStage INTEGER NOT NULL,
                        networkFingerprint TEXT NOT NULL,
                        networkType TEXT NOT NULL,
                        verificationStatus TEXT NOT NULL,
                        failureReason TEXT NOT NULL,
                        latency INTEGER NOT NULL,
                        tcpP90Latency INTEGER NOT NULL,
                        tcpJitter INTEGER NOT NULL,
                        protocolLatency INTEGER NOT NULL,
                        protocolP90Latency INTEGER NOT NULL,
                        protocolJitter INTEGER NOT NULL,
                        successRate REAL NOT NULL,
                        protocolSuccessRate REAL NOT NULL,
                        stability REAL NOT NULL,
                        continuityRate REAL NOT NULL,
                        verificationConfidence INTEGER NOT NULL,
                        transferScore INTEGER NOT NULL,
                        telegramDownloadKbps INTEGER NOT NULL,
                        telegramUploadKbps INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_proxy_test_history_proxyUrl_measuredAt " +
                        "ON proxy_test_history(proxyUrl, measuredAt)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_proxy_test_history_networkFingerprint_measuredAt " +
                        "ON proxy_test_history(networkFingerprint, measuredAt)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_proxy_test_history_measuredAt " +
                        "ON proxy_test_history(measuredAt)"
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS benchmark_run_state (
                        id INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        requestedCount INTEGER NOT NULL,
                        selectedWorkers INTEGER NOT NULL,
                        stage INTEGER NOT NULL,
                        stageCompleted INTEGER NOT NULL,
                        stageTotal INTEGER NOT NULL,
                        completedWork INTEGER NOT NULL,
                        totalWork INTEGER NOT NULL,
                        telegramTotal INTEGER NOT NULL,
                        telegramCompleted INTEGER NOT NULL,
                        telegramSucceeded INTEGER NOT NULL,
                        telegramFailed INTEGER NOT NULL,
                        enableTelegramTransfers INTEGER NOT NULL,
                        startedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        finishedAt INTEGER NOT NULL,
                        failure TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE mtproto_proxies ADD COLUMN " +
                        "telegramTransferRounds INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE mtproto_proxies ADD COLUMN " +
                        "telegramTransferConfidence INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE mtproto_proxies ADD COLUMN " +
                        "telegramNetworkUploadBytes INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE mtproto_proxies ADD COLUMN " +
                        "telegramNetworkDownloadBytes INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE proxy_test_history ADD COLUMN " +
                        "telegramTransferRounds INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE proxy_test_history ADD COLUMN " +
                        "telegramTransferConfidence INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE proxy_test_history ADD COLUMN " +
                        "telegramNetworkUploadBytes INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE proxy_test_history ADD COLUMN " +
                        "telegramNetworkDownloadBytes INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE favorite_proxies ADD COLUMN folder TEXT NOT NULL DEFAULT 'Favorites'"
                )
                db.execSQL(
                    "ALTER TABLE favorite_proxies ADD COLUMN tags TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE favorite_proxies ADD COLUMN note TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE favorite_proxies ADD COLUMN lastKnownGoodAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE favorite_proxies ADD COLUMN " +
                        "lastKnownTransferScore INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE favorite_proxies ADD COLUMN lastKnownLatency INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE favorite_proxies ADD COLUMN " +
                        "lastKnownDownloadKbps INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE favorite_proxies ADD COLUMN " +
                        "lastKnownUploadKbps INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE proxy_source_health ADD COLUMN " +
                        "contentSignature TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE proxy_source_health ADD COLUMN mirrorGroupSize INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE proxy_source_health ADD COLUMN endpointDiversity INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS source_fetch_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        success INTEGER NOT NULL,
                        rawProxyCount INTEGER NOT NULL,
                        uniqueProxyCount INTEGER NOT NULL,
                        duplicateRate REAL NOT NULL,
                        durationMs INTEGER NOT NULL,
                        contentSignature TEXT NOT NULL,
                        endpointDiversity INTEGER NOT NULL,
                        networkType TEXT NOT NULL,
                        errorCode TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_source_fetch_history_sourceUrl_fetchedAt " +
                        "ON source_fetch_history(sourceUrl, fetchedAt)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_source_fetch_history_fetchedAt " +
                        "ON source_fetch_history(fetchedAt)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_source_fetch_history_contentSignature_fetchedAt " +
                        "ON source_fetch_history(contentSignature, fetchedAt)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS private_proxy_nodes (
                        url TEXT NOT NULL,
                        label TEXT NOT NULL,
                        server TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        secret TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastChecked INTEGER NOT NULL,
                        verificationStatus TEXT NOT NULL,
                        verificationConfidence INTEGER NOT NULL,
                        latency INTEGER NOT NULL,
                        p90Latency INTEGER NOT NULL,
                        jitter INTEGER NOT NULL,
                        stability REAL NOT NULL,
                        transferScore INTEGER NOT NULL,
                        networkFingerprint TEXT NOT NULL,
                        networkType TEXT NOT NULL,
                        lastError TEXT NOT NULL,
                        PRIMARY KEY(url)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_private_proxy_nodes_enabled " +
                        "ON private_proxy_nodes(enabled)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_private_proxy_nodes_lastChecked " +
                        "ON private_proxy_nodes(lastChecked)"
                )
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS web_proxy_nodes (
                    url TEXT NOT NULL PRIMARY KEY, address TEXT NOT NULL, secret TEXT NOT NULL,
                    createdAt INTEGER NOT NULL, testedAt INTEGER NOT NULL, status TEXT NOT NULL,
                    stage INTEGER NOT NULL, latencyMs INTEGER NOT NULL, networkFingerprint TEXT NOT NULL,
                    networkType TEXT NOT NULL, downloadKbps INTEGER NOT NULL, uploadKbps INTEGER NOT NULL,
                    transferBytes INTEGER NOT NULL, transferRounds INTEGER NOT NULL, source TEXT NOT NULL
                )""".trimIndent())
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("telegramDownloadFloorKbps", "telegramUploadFloorKbps",
                    "telegramDownloadPeakKbps", "telegramUploadPeakKbps").forEach { column ->
                    db.execSQL("ALTER TABLE mtproto_proxies ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE benchmark_run_state ADD COLUMN " +
                        "accuracyMode TEXT NOT NULL DEFAULT 'BALANCED'"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS network_speed_baselines (
                        networkFingerprint TEXT NOT NULL,
                        networkType TEXT NOT NULL,
                        isMetered INTEGER NOT NULL,
                        downloadKbps INTEGER NOT NULL,
                        uploadKbps INTEGER NOT NULL,
                        downloadSampleBytes INTEGER NOT NULL,
                        uploadSampleBytes INTEGER NOT NULL,
                        measuredAt INTEGER NOT NULL,
                        PRIMARY KEY(networkFingerprint)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_network_speed_baselines_measuredAt " +
                        "ON network_speed_baselines(measuredAt)"
                )
            }
        }
    }
}
