package co.aura.communication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory

sealed class ContactResolutionException(message: String) : Exception(message) {
    class PermissionDenied : ContactResolutionException("Sir, contact access is not enabled. Please grant permission in Android settings.")
    class ProviderError(cause: Throwable) : ContactResolutionException("Sir, I couldn't access your contacts right now.")
}

class AndroidContactResolver(private val context: Context) : ContactResolver {

    override suspend fun resolveContact(name: String): List<ContactInfo> {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val rawQuery = name.trim()
        val queryLower = rawQuery.lowercase()
        val normQuery = ContactMatchingUtils.normalize(rawQuery)

        AuraLogger.d(LogCategory.ACTION, "Requested contact: '$rawQuery'")
        AuraLogger.d(LogCategory.ACTION, "Normalized query: '$normQuery'")
        AuraLogger.d(LogCategory.ACTION, "Contacts permission: ${if (hasPermission) "GRANTED" else "DENIED"}")

        if (!hasPermission) {
            throw ContactResolutionException.PermissionDenied()
        }

        val aliases = ContactMatchingUtils.relationshipAliasMap[queryLower] ?: listOf(queryLower)
        val allContacts = mutableListOf<ContactInfo>()

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )

        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

                if (nameIdx >= 0 && numberIdx >= 0) {
                    while (it.moveToNext()) {
                        val displayName = it.getString(nameIdx) ?: ""
                        val number = it.getString(numberIdx) ?: ""
                        val type = if (typeIdx >= 0) it.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                        val customLabel = if (labelIdx >= 0) it.getString(labelIdx) else null

                        if (displayName.isNotBlank() && number.isNotBlank()) {
                            val phoneLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                context.resources, type, customLabel ?: ""
                            ).toString()

                            allContacts.add(
                                ContactInfo(
                                    name = displayName,
                                    phoneNumber = number,
                                    phoneLabel = phoneLabel
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Error querying Android ContactsContract", e)
            throw ContactResolutionException.ProviderError(e)
        }

        AuraLogger.d(LogCategory.ACTION, "Provider results: ${allContacts.size}")

        if (normQuery.isBlank()) {
            return formatContactDisplayNames(allContacts.distinctBy { "${it.name}_${it.phoneNumber}" })
        }

        val distinctContacts = allContacts.distinctBy { "${it.name}_${it.phoneNumber}" }

        // Resolution Priority:
        // Tier 1: Exact display-name match (case-insensitive & normalized)
        val exactMatches = distinctContacts.filter { contact ->
            val contactLower = contact.name.lowercase().trim()
            val normContact = ContactMatchingUtils.normalize(contact.name)
            contactLower == queryLower ||
            normContact == normQuery ||
            aliases.any { alias -> contactLower == alias || normContact == ContactMatchingUtils.normalize(alias) }
        }
        if (exactMatches.isNotEmpty()) {
            AuraLogger.d(LogCategory.ACTION, "Best match (Tier 1 Exact): ${exactMatches.size} candidates")
            return formatContactDisplayNames(exactMatches)
        }

        // Tier 2: Token match (e.g. query "Rahul" matching contact "Rahul Kumar")
        val tokenMatches = distinctContacts.filter { contact ->
            val tokens = contact.name.lowercase().split(Regex("\\s+"))
            val normTokens = tokens.map { ContactMatchingUtils.normalize(it) }
            tokens.contains(queryLower) || normTokens.contains(normQuery) ||
            aliases.any { alias -> tokens.contains(alias) || normTokens.contains(ContactMatchingUtils.normalize(alias)) }
        }
        if (tokenMatches.isNotEmpty()) {
            AuraLogger.d(LogCategory.ACTION, "Best match (Tier 2 Token): ${tokenMatches.size} candidates")
            return formatContactDisplayNames(tokenMatches)
        }

        // Tier 3: Prefix match (e.g. query "Ankit" matching "Ankit Sharma", "Ankit Singh")
        val prefixMatches = distinctContacts.filter { contact ->
            val contactLower = contact.name.lowercase().trim()
            val normContact = ContactMatchingUtils.normalize(contact.name)
            contactLower.startsWith(queryLower) || normContact.startsWith(normQuery)
        }
        if (prefixMatches.isNotEmpty()) {
            AuraLogger.d(LogCategory.ACTION, "Best match (Tier 3 Prefix): ${prefixMatches.size} candidates")
            return formatContactDisplayNames(prefixMatches)
        }

        // Tier 4: Substring / partial match
        val partialMatches = distinctContacts.filter { contact ->
            val contactLower = contact.name.lowercase()
            val normContact = ContactMatchingUtils.normalize(contact.name)
            contactLower.contains(queryLower) || queryLower.contains(contactLower) ||
            normContact.contains(normQuery) || normQuery.contains(normContact) ||
            aliases.any { alias -> contactLower.contains(alias) || ContactMatchingUtils.normalize(alias).let { normContact.contains(it) } }
        }
        if (partialMatches.isNotEmpty()) {
            AuraLogger.d(LogCategory.ACTION, "Best match (Tier 4 Partial): ${partialMatches.size} candidates")
            return formatContactDisplayNames(partialMatches)
        }

        // Tier 5: Fuzzy STT match
        val fuzzyMatches = distinctContacts.filter { contact ->
            ContactMatchingUtils.isFuzzyMatch(rawQuery, contact.name)
        }
        AuraLogger.d(LogCategory.ACTION, "Best match (Tier 5 Fuzzy): ${fuzzyMatches.size} candidates")
        return formatContactDisplayNames(fuzzyMatches)
    }

    private fun formatContactDisplayNames(contacts: List<ContactInfo>): List<ContactInfo> {
        val byName = contacts.groupBy { it.name }
        return contacts.map { contact ->
            val phoneCount = byName[contact.name]?.size ?: 1
            if (phoneCount > 1 && contact.phoneLabel.isNotBlank()) {
                contact.copy(name = "${contact.name} (${contact.phoneLabel})")
            } else {
                contact
            }
        }
    }
}
