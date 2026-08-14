package co.aura.actions

enum class MatchConfidence {
    EXACT,
    ALIAS,
    STRONG,
    FUZZY,
    AMBIGUOUS,
    NONE
}

data class ResolutionResult(
    val matchedApp: InstalledApp?,
    val confidence: MatchConfidence,
    val candidates: List<InstalledApp>
)

class AppAliasResolver {

    private val aliasMap = mapOf(
        "youtube" to listOf("youtube", "yt", "you tube", "my videos", "video app", "videos", "watch videos"),
        "whatsapp" to listOf("whatsapp", "what's app", "what app", "watsapp", "wa", "my messages", "messaging", "chat app"),
        "camera" to listOf("camera", "cam", "camera app", "take a picture", "take a photo", "photos camera"),
        "chrome" to listOf("chrome", "google chrome", "browser", "web browser", "internet", "surfing app", "my browser", "the browser"),
        "instagram" to listOf("instagram", "insta"),
        "maps" to listOf("maps", "google maps", "navigation", "map"),
        "gmail" to listOf("gmail", "google mail", "mail", "email"),
        "calculator" to listOf("calculator", "calc"),
        "settings" to listOf("settings", "phone settings", "system settings"),
        "messages" to listOf("messages", "sms", "text messages", "texting"),
        "phone" to listOf("phone", "dialer", "calls", "calling app"),
        "gallery" to listOf("gallery", "photos", "pictures", "photo gallery"),
        "music" to listOf("music", "music app", "play music")
    )

    fun normalize(text: String): String {
        return text.lowercase()
            .replace("'", "")
            .replace("-", "")
            .replace(Regex("[^a-z0-9\\s]"), "") // Keep spaces for splitting or joining
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString("")
    }

    fun getCanonicalKey(query: String): String? {
        val normalizedQuery = normalize(query)
        return aliasMap.filter { entry ->
            entry.value.any { normalize(it) == normalizedQuery }
        }.keys.firstOrNull()
    }

    fun resolve(query: String, installedApps: List<InstalledApp>): ResolutionResult {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) {
            return ResolutionResult(null, MatchConfidence.NONE, emptyList())
        }

        // 1. EXACT Match
        val exactMatches = installedApps.filter {
            normalize(it.label) == normalizedQuery || normalize(it.packageName) == normalizedQuery
        }
        if (exactMatches.isNotEmpty()) {
            return if (exactMatches.size == 1) {
                ResolutionResult(exactMatches.first(), MatchConfidence.EXACT, exactMatches)
            } else {
                ResolutionResult(null, MatchConfidence.AMBIGUOUS, exactMatches)
            }
        }

        // 2. ALIAS Match
        val canonicalKeys = aliasMap.filter { entry ->
            entry.value.any { normalize(it) == normalizedQuery }
        }.keys

        if (canonicalKeys.isNotEmpty()) {
            val aliasMatches = installedApps.filter { app ->
                val normLabel = normalize(app.label)
                val normPkg = normalize(app.packageName)
                val isMatched = canonicalKeys.any { key ->
                    normLabel == key || normPkg.contains(key) ||
                    (key == "chrome" && (
                        normLabel.contains("chrome") || normLabel.contains("browser") || normLabel.contains("firefox") || normLabel.contains("brave") || normLabel.contains("opera") || normLabel.contains("safari") ||
                        normPkg.contains("chrome") || normPkg.contains("browser") || normPkg.contains("firefox") || normPkg.contains("brave") || normPkg.contains("opera") || normPkg.contains("safari") || normPkg.contains("mozilla")
                    )) ||
                    (key == "youtube" && (normLabel.contains("youtube") || normLabel.contains("yt"))) ||
                    (key == "whatsapp" && (normLabel.contains("whatsapp") || normLabel.contains("wa"))) ||
                    (key == "camera" && normLabel.contains("camera")) ||
                    (key == "gallery" && (normLabel.contains("gallery") || normLabel.contains("photo") || normLabel.contains("picture"))) ||
                    (key == "music" && (normLabel.contains("music") || normLabel.contains("player"))) ||
                    (key == "messages" && (normLabel.contains("message") || normLabel.contains("sms") || normLabel.contains("mms"))) ||
                    (key == "phone" && (normLabel.contains("phone") || normLabel.contains("dialer")))
                }

                isMatched
            }
            if (aliasMatches.isNotEmpty()) {
                return if (aliasMatches.size == 1) {
                    ResolutionResult(aliasMatches.first(), MatchConfidence.ALIAS, aliasMatches)
                } else {
                    ResolutionResult(null, MatchConfidence.AMBIGUOUS, aliasMatches)
                }
            }
        }

        // 3. STRONG Match
        val strongMatches = installedApps.filter {
            val normLabel = normalize(it.label)
            val normPkg = normalize(it.packageName)
            (normLabel.startsWith(normalizedQuery) && normalizedQuery.length >= 3) ||
            (normalizedQuery.startsWith(normLabel) && normLabel.length >= 3) ||
            normPkg.startsWith(normalizedQuery)
        }
        if (strongMatches.isNotEmpty()) {
            return if (strongMatches.size == 1) {
                ResolutionResult(strongMatches.first(), MatchConfidence.STRONG, strongMatches)
            } else {
                ResolutionResult(null, MatchConfidence.AMBIGUOUS, strongMatches)
            }
        }

        // 4. FUZZY Match
        val fuzzyMatches = installedApps.filter {
            val normLabel = normalize(it.label)
            val normPkg = normalize(it.packageName)
            (normLabel.contains(normalizedQuery) && normalizedQuery.length >= 3) ||
            (normalizedQuery.contains(normLabel) && normLabel.length >= 3) ||
            normPkg.contains(normalizedQuery)
        }
        if (fuzzyMatches.isNotEmpty()) {
            return if (fuzzyMatches.size == 1) {
                ResolutionResult(fuzzyMatches.first(), MatchConfidence.FUZZY, fuzzyMatches)
            } else {
                ResolutionResult(null, MatchConfidence.AMBIGUOUS, fuzzyMatches)
            }
        }

        return ResolutionResult(null, MatchConfidence.NONE, emptyList())
    }
}
