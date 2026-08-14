package co.aura.test

import co.aura.actions.AppAliasResolver
import co.aura.actions.InstalledApp
import co.aura.actions.MatchConfidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppAliasResolverTest {

    private val resolver = AppAliasResolver()

    private val installedApps = listOf(
        InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("WhatsApp", "com.whatsapp"),
        InstalledApp("Camera", "com.android.camera"),
        InstalledApp("Instagram", "com.instagram.android"),
        InstalledApp("Google Chrome", "com.android.chrome"),
        InstalledApp("Firefox", "org.mozilla.firefox"),
        InstalledApp("Brave", "com.brave.browser")
    )

    @Test
    fun testExactMatches() {
        val result = resolver.resolve("Instagram", installedApps)
        assertEquals(MatchConfidence.EXACT, result.confidence)
        assertEquals("Instagram", result.matchedApp?.label)

        val resultLower = resolver.resolve("instagram", installedApps)
        assertEquals(MatchConfidence.EXACT, resultLower.confidence)
    }

    @Test
    fun testAliases() {
        // "youtube" is exact label match
        val youtubeExact = resolver.resolve("youtube", installedApps)
        assertEquals(MatchConfidence.EXACT, youtubeExact.confidence)
        assertEquals("YouTube", youtubeExact.matchedApp?.label)

        // "you tube" normalizes to "youtube", which is exact match
        val youTubeNormalizedExact = resolver.resolve("you tube", installedApps)
        assertEquals(MatchConfidence.EXACT, youTubeNormalizedExact.confidence)

        // Other youtube aliases
        val aliases = listOf("yt", "my videos", "videos")
        for (alias in aliases) {
            val result = resolver.resolve(alias, installedApps)
            assertEquals(MatchConfidence.ALIAS, result.confidence, "Failed for alias: $alias")
            assertEquals("YouTube", result.matchedApp?.label)
        }

        // "whatsapp" is exact label match
        val whatsappExact = resolver.resolve("whatsapp", installedApps)
        assertEquals(MatchConfidence.EXACT, whatsappExact.confidence)
        assertEquals("WhatsApp", whatsappExact.matchedApp?.label)

        // "watsapp" matches alias mapping returning ALIAS
        val watsappAlias = resolver.resolve("watsapp", installedApps)
        assertEquals(MatchConfidence.ALIAS, watsappAlias.confidence)

        val whatsAppNormalizedExact = resolver.resolve("whats app", installedApps)
        assertEquals(MatchConfidence.EXACT, whatsAppNormalizedExact.confidence)

        // Other whatsapp aliases
        val waAliases = listOf("wa")
        for (alias in waAliases) {
            val result = resolver.resolve(alias, installedApps)
            assertEquals(MatchConfidence.ALIAS, result.confidence, "Failed for WhatsApp alias: $alias")
            assertEquals("WhatsApp", result.matchedApp?.label)
        }

        val camResult = resolver.resolve("cam", installedApps)
        assertEquals(MatchConfidence.ALIAS, camResult.confidence)
        assertEquals("Camera", camResult.matchedApp?.label)
    }

    @Test
    fun testStrongMatch() {
        // "fire" is a prefix of "Firefox" of length >= 3
        val result = resolver.resolve("fire", installedApps)
        assertEquals(MatchConfidence.STRONG, result.confidence)
        assertEquals("Firefox", result.matchedApp?.label)
    }

    @Test
    fun testFuzzyMatch() {
        // "fox" is a substring of "Firefox" but not a prefix
        val result = resolver.resolve("fox", installedApps)
        assertEquals(MatchConfidence.FUZZY, result.confidence)
        assertEquals("Firefox", result.matchedApp?.label)
    }

    @Test
    fun testAmbiguity() {
        val result = resolver.resolve("browser", installedApps)
        assertEquals(MatchConfidence.AMBIGUOUS, result.confidence)
        assertEquals(3, result.candidates.size)
    }



    @Test
    fun testNoMatch() {
        val result = resolver.resolve("non_existent_app", installedApps)
        assertEquals(MatchConfidence.NONE, result.confidence)
        assertNull(result.matchedApp)
    }
}
