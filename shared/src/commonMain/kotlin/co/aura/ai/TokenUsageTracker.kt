package co.aura.ai

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory

/**
 * Record of a single AI token consumption event.
 */
data class TokenUsageRecord(
    val id: String,
    val timestamp: Long,
    val model: String,
    val routingReason: String,
    val promptTokens: Int,
    val candidateTokens: Int,
    val thinkingTokens: Int,
    val totalTokens: Int,
    val durationMs: Long,
    val wasRetried: Boolean,
    val isReportedByApi: Boolean,
    val estimatedCostUsd: Double
)

/**
 * Aggregated summary of local vs remote token savings and consumption.
 */
data class TokenUsageSummary(
    val totalRequests: Int,
    val totalPromptTokens: Long,
    val totalCandidateTokens: Long,
    val totalThinkingTokens: Long,
    val totalTokens: Long,
    val estimatedTotalCostUsd: Double,
    val localCommandsSaved: Long
)

/**
 * Thread-safe local token usage monitor and optimizer.
 * Tracks API token consumption and estimates cost with zero remote requests.
 */
object TokenUsageTracker {

    private val records = mutableListOf<TokenUsageRecord>()
    private var localCommandsExecutedCount: Long = 0L

    // Official Gemini pricing benchmarks (per 1,000,000 tokens)
    // Flash tier: ~$0.075 per 1M input, ~$0.30 per 1M output
    private const val COST_PER_MILLION_INPUT_USD = 0.075
    private const val COST_PER_MILLION_OUTPUT_USD = 0.30

    @Synchronized
    fun recordLocalCommandSaved() {
        localCommandsExecutedCount++
        AuraLogger.d(LogCategory.AI, "[TOKEN_SAVER] Local operation resolved without Gemini. Total local actions: $localCommandsExecutedCount")
    }

    @Synchronized
    fun recordUsage(
        model: String,
        routingReason: String,
        promptTokens: Int?,
        candidateTokens: Int?,
        thinkingTokens: Int?,
        durationMs: Long,
        wasRetried: Boolean = false,
        promptLength: Int = 0,
        responseLength: Int = 0
    ) {
        val isReported = promptTokens != null && candidateTokens != null
        val finalPromptTokens = promptTokens ?: (promptLength / 4).coerceAtLeast(1)
        val finalCandidateTokens = candidateTokens ?: (responseLength / 4).coerceAtLeast(1)
        val finalThinkingTokens = thinkingTokens ?: 0
        val total = finalPromptTokens + finalCandidateTokens + finalThinkingTokens

        val cost = (finalPromptTokens * COST_PER_MILLION_INPUT_USD / 1_000_000.0) +
                ((finalCandidateTokens + finalThinkingTokens) * COST_PER_MILLION_OUTPUT_USD / 1_000_000.0)

        val record = TokenUsageRecord(
            id = "usage_${System.currentTimeMillis()}_${records.size}",
            timestamp = System.currentTimeMillis(),
            model = model,
            routingReason = routingReason,
            promptTokens = finalPromptTokens,
            candidateTokens = finalCandidateTokens,
            thinkingTokens = finalThinkingTokens,
            totalTokens = total,
            durationMs = durationMs,
            wasRetried = wasRetried,
            isReportedByApi = isReported,
            estimatedCostUsd = cost
        )

        records.add(record)
        // Bound in-memory history to last 200 records
        if (records.size > 200) {
            records.removeAt(0)
        }

        AuraLogger.i(
            LogCategory.AI,
            "[TOKEN_USAGE] model=$model reason=$routingReason tokens=$total (in=$finalPromptTokens out=$finalCandidateTokens think=$finalThinkingTokens) time=${durationMs}ms retried=$wasRetried cost=\$${String.format(java.util.Locale.US, "%.6f", cost)} ${if (isReported) "(API reported)" else "(Estimated)"}"
        )
    }

    @Synchronized
    fun getSummary(): TokenUsageSummary {
        val totalReq = records.size
        val promptSum = records.sumOf { it.promptTokens.toLong() }
        val candidateSum = records.sumOf { it.candidateTokens.toLong() }
        val thinkingSum = records.sumOf { it.thinkingTokens.toLong() }
        val totalSum = records.sumOf { it.totalTokens.toLong() }
        val costSum = records.sumOf { it.estimatedCostUsd }

        return TokenUsageSummary(
            totalRequests = totalReq,
            totalPromptTokens = promptSum,
            totalCandidateTokens = candidateSum,
            totalThinkingTokens = thinkingSum,
            totalTokens = totalSum,
            estimatedTotalCostUsd = costSum,
            localCommandsSaved = localCommandsExecutedCount
        )
    }

    @Synchronized
    fun getTopConsumingRequests(limit: Int = 5): List<TokenUsageRecord> {
        return records.sortedByDescending { it.totalTokens }.take(limit)
    }

    @Synchronized
    fun getRecentRecords(limit: Int = 20): List<TokenUsageRecord> {
        return records.takeLast(limit).reversed()
    }

    @Synchronized
    fun generateDebugReport(): String {
        val summary = getSummary()
        val top = getTopConsumingRequests(3)
        return buildString {
            append("=== JARVIS TOKEN CONSUMPTION REPORT ===\n")
            append("Local Commands Handled (0 Tokens): ${summary.localCommandsSaved}\n")
            append("Gemini Requests: ${summary.totalRequests}\n")
            append("Total Tokens Used: ${summary.totalTokens} (Prompt: ${summary.totalPromptTokens}, Output: ${summary.totalCandidateTokens}, Thinking: ${summary.totalThinkingTokens})\n")
            append("Estimated Cost: \$${String.format(java.util.Locale.US, "%.5f", summary.estimatedTotalCostUsd)} USD\n")
            if (top.isNotEmpty()) {
                append("\nTop Token-Consuming Requests:\n")
                top.forEachIndexed { idx, r ->
                    append("  ${idx + 1}. [${r.routingReason}] ${r.totalTokens} tokens (${r.durationMs}ms, ${r.model})\n")
                }
            }
            append("========================================")
        }
    }
}
