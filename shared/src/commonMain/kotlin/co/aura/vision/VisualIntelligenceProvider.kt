package co.aura.vision

interface VisualIntelligenceProvider {
    suspend fun performOcr(imageBytes: ByteArray): String
    suspend fun detectObjects(imageBytes: ByteArray): List<String>
}

class VisualIntelligenceProviderImpl : VisualIntelligenceProvider {
    override suspend fun performOcr(imageBytes: ByteArray): String {
        // TODO: Bridge to Google ML Kit (Android) or local parsing engine (Desktop)
        return "Transcribed OCR text placeholder"
    }

    override suspend fun detectObjects(imageBytes: ByteArray): List<String> {
        return emptyList()
    }
}
