package com.betterblue.kit.log

enum class BBLogLevel(
    val emoji: String,
) {
    DEBUG("🔍"),
    INFO("ℹ️"),
    WARNING("⚠️"),
    ERROR("❌"),
}

enum class BBLogCategory(
    val label: String,
) {
    API("API"),
    AUTH("Auth"),
    MFA("MFA"),
    LIVE_ACTIVITY("LiveActivity"),
    INTENT("Intent"),
    BACKGROUND("Background"),
    PUSH("Push"),
    APP("App"),
    VEHICLE("Vehicle"),
    FAKE_API("FakeAPI"),
}

/** Implement to integrate with custom logging systems (e.g. Logcat/Timber). */
fun interface BBLogSink {
    fun log(level: BBLogLevel, category: BBLogCategory, message: String)
}

/**
 * Global logger that delegates to a configurable sink. Configure the sink at
 * app startup; defaults to printing to stdout.
 */
object BBLogger {
    @Volatile
    var sink: BBLogSink =
        BBLogSink { level, category, message ->
            println("${level.emoji} [${category.label}] $message")
        }

    fun debug(category: BBLogCategory, message: String) = sink.log(BBLogLevel.DEBUG, category, message)

    fun info(category: BBLogCategory, message: String) = sink.log(BBLogLevel.INFO, category, message)

    fun warning(category: BBLogCategory, message: String) = sink.log(BBLogLevel.WARNING, category, message)

    fun error(category: BBLogCategory, message: String) = sink.log(BBLogLevel.ERROR, category, message)
}
