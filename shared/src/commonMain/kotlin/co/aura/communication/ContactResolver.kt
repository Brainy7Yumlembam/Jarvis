package co.aura.communication

interface ContactResolver {
    suspend fun resolveContact(name: String): List<ContactInfo>
}

data class ContactInfo(
    val name: String,
    val phoneNumber: String,
    val phoneLabel: String = ""
)

object ContactMatchingUtils {
    val relationshipAliasMap = mapOf(
        "mom" to listOf("mom", "mummy", "maa", "mother"),
        "mummy" to listOf("mom", "mummy", "maa", "mother"),
        "maa" to listOf("mom", "mummy", "maa", "mother"),
        "mother" to listOf("mom", "mummy", "maa", "mother"),
        "dad" to listOf("dad", "papa", "father", "pop"),
        "papa" to listOf("dad", "papa", "father", "pop"),
        "father" to listOf("dad", "papa", "father", "pop"),
        "brother" to listOf("brother", "bro"),
        "bro" to listOf("brother", "bro"),
        "sister" to listOf("sister", "sis"),
        "sis" to listOf("sister", "sis")
    )

    fun normalize(text: String): String {
        return text.lowercase()
            .replace("'", "")
            .replace("-", "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        val a = s1.lowercase()
        val b = s2.lowercase()
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    fun isFuzzyMatch(query: String, target: String): Boolean {
        val normQuery = normalize(query)
        val normTarget = normalize(target)
        if (normQuery.isBlank() || normTarget.isBlank()) return false
        if (normTarget.contains(normQuery) || normQuery.contains(normTarget)) return true

        // Compute edit distance threshold based on query length
        val dist = levenshteinDistance(normQuery, normTarget)
        val maxLen = maxOf(normQuery.length, normTarget.length)
        return when {
            maxLen <= 4 -> dist <= 1
            maxLen <= 8 -> dist <= 2
            else -> dist <= 3
        }
    }
}
