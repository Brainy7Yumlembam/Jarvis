package co.aura.memory

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.model.MemoryFragment
import co.aura.domain.repository.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeywordMemoryRetriever(
    private val memoryRepository: MemoryRepository
) : MemoryRetriever {

    private val stopWords = setOf(
        "a", "about", "above", "after", "again", "all", "also", "am", "an", "and", "any", "are",
        "aren't", "as", "at", "be", "because", "been", "before", "being", "below", "between",
        "both", "but", "by", "can", "can't", "cannot", "could", "couldn't", "did", "didn't",
        "do", "does", "doesn't", "doing", "don't", "down", "during", "each", "few", "for",
        "from", "further", "had", "hadn't", "has", "hasn't", "have", "haven't", "having",
        "he", "her", "here", "hers", "herself", "him", "himself", "his", "how", "i", "if",
        "in", "into", "is", "isn't", "it", "its", "itself", "just", "like", "me", "more",
        "most", "my", "myself", "no", "nor", "not", "of", "off", "on", "once", "only", "or",
        "other", "ought", "our", "ours", "ourselves", "out", "over", "own", "please", "same",
        "shan't", "she", "should", "shouldn't", "so", "some", "such", "than", "that", "the",
        "their", "theirs", "them", "themselves", "then", "there", "these", "they", "this",
        "those", "through", "to", "too", "under", "until", "up", "very", "was", "wasn't",
        "we", "were", "weren't", "what", "when", "where", "which", "while", "who", "whom",
        "why", "will", "with", "won't", "would", "wouldn't", "you", "your", "yours", "yourself",
        "yourselves", "tell", "show", "give", "know", "think", "make", "take", "come", "want", "need"
    )

    override suspend fun retrieveRelevantMemories(query: String, limit: Int): List<MemoryFragment> = withContext(Dispatchers.Default) {
        try {
            val allMemories = memoryRepository.getMemoryFragments()
            if (allMemories.isEmpty()) return@withContext emptyList()

            // Safe bounded limit: 3 to 5 (default capped at max 5)
            val effectiveLimit = limit.coerceIn(1, 5)

            // Tokenize query: remove punctuation, lowercase, filter out stop words and short tokens (<3 chars)
            val queryTokens = query.lowercase()
                .split(Regex("[^a-zA-Z0-9_]+"))
                .map { it.trim() }
                .filter { it.length >= 3 && !stopWords.contains(it) }
                .distinct()

            if (queryTokens.isEmpty()) {
                // If no substantive keywords remain, return top pinned memories
                return@withContext allMemories
                    .filter { it.pinned }
                    .sortedWith(compareByDescending<MemoryFragment> { it.importance }.thenByDescending { it.lastAccessedAt })
                    .take(effectiveLimit)
            }

            // Score each memory based on:
            // 1. Keyword overlap (+20 for exact token match, +10 for substring match)
            // 2. Pinned status (+50)
            // 3. Importance (+1 to +10)
            val scoredMemories = allMemories.mapNotNull { memory ->
                val contentLower = memory.content.lowercase()
                val memoryTokens = contentLower.split(Regex("[^a-zA-Z0-9_]+")).toSet()

                var matchScore = 0
                for (token in queryTokens) {
                    when {
                        memoryTokens.contains(token) -> matchScore += 3
                        contentLower.contains(token) -> matchScore += 1
                    }
                }

                // If memory is not pinned and has zero keyword match, exclude it
                if (matchScore == 0 && !memory.pinned) {
                    null
                } else {
                    // Pinned (+100) > Importance (*10) > Keyword overlap (+1..+3)
                    val totalScore = (if (memory.pinned) 100 else 0) + (memory.importance * 10) + matchScore
                    memory to totalScore
                }
            }

            val ranked = scoredMemories
                .sortedWith(
                    compareByDescending<Pair<MemoryFragment, Int>> { it.second }
                        .thenByDescending { it.first.importance }
                        .thenByDescending { it.first.lastAccessedAt }
                )
                .map { it.first }
                .take(effectiveLimit)

            // Non-blocking update of last accessed timestamp so retrieval is instant
            val now = System.currentTimeMillis()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                ranked.forEach { memory ->
                    try {
                        memoryRepository.updateLastAccessed(memory.id, now)
                    } catch (e: Exception) {
                        // Non-critical
                    }
                }
            }

            ranked
        } catch (e: Exception) {
            AuraLogger.w(LogCategory.AI, "Failed to retrieve relevant memories: ${e.message}")
            emptyList()
        }
    }
}

