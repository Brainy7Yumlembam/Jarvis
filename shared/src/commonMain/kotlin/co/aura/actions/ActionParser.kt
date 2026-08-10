package co.aura.actions

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface ActionParser {
    fun parseAction(rawJson: String): Action?
}

class ActionParserImpl(private val json: Json) : ActionParser {
    override fun parseAction(rawJson: String): Action? {
        return try {
            val jsonObject = json.parseToJsonElement(rawJson).jsonObject
            val actionType = jsonObject["action"]?.jsonPrimitive?.content ?: return null
            
            when (actionType) {
                "OPEN_APP" -> {
                    val pkg = jsonObject["packageName"]?.jsonPrimitive?.content ?: ""
                    OpenAppAction(pkg)
                }
                "SEND_SMS" -> {
                    val contact = jsonObject["contact"]?.jsonPrimitive?.content ?: ""
                    val msg = jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    SendSmsAction(contact, msg)
                }
                "SET_ALARM" -> {
                    val time = jsonObject["time"]?.jsonPrimitive?.content ?: ""
                    SetAlarmAction(time)
                }
                else -> {
                    val params = jsonObject.filterKeys { it != "action" }.mapValues { entry ->
                        entry.value.jsonPrimitive.content
                    }
                    GenericAction(actionType, params)
                }
            }
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to parse action json: $rawJson", e)
            null
        }
    }
}
