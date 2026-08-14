package co.aura.actions

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface ActionParser {
    fun parseAction(rawJson: String): Action?
}

fun parseTime(timeStr: String): Pair<Int, Int>? {
    val cleanTime = timeStr.trim().uppercase()
    val amPmRegex = Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)""")
    val amPmMatch = amPmRegex.find(cleanTime)
    if (amPmMatch != null) {
        var hour = amPmMatch.groupValues[1].toInt()
        val minute = amPmMatch.groupValues[2].toInt()
        val ampm = amPmMatch.groupValues[3]
        if (ampm == "PM" && hour < 12) hour += 12
        if (ampm == "AM" && hour == 12) hour = 0
        return Pair(hour, minute)
    }

    val hourOnlyAmPmRegex = Regex("""(\d{1,2})\s*(AM|PM)""")
    val hourOnlyMatch = hourOnlyAmPmRegex.find(cleanTime)
    if (hourOnlyMatch != null) {
        var hour = hourOnlyMatch.groupValues[1].toInt()
        val ampm = hourOnlyMatch.groupValues[2]
        if (ampm == "PM" && hour < 12) hour += 12
        if (ampm == "AM" && hour == 12) hour = 0
        return Pair(hour, 0)
    }

    val militaryRegex = Regex("""(\d{1,2}):(\d{2})""")
    val militaryMatch = militaryRegex.find(cleanTime)
    if (militaryMatch != null) {
        val hour = militaryMatch.groupValues[1].toInt()
        val minute = militaryMatch.groupValues[2].toInt()
        if (hour in 0..23 && minute in 0..59) {
            return Pair(hour, minute)
        }
    }

    val hourOnlyRegex = Regex("""^(\d{1,2})$""")
    val hourOnlyMatch2 = hourOnlyRegex.find(cleanTime)
    if (hourOnlyMatch2 != null) {
        val hour = hourOnlyMatch2.groupValues[1].toInt()
        if (hour in 0..23) {
            return Pair(hour, 0)
        }
    }

    return null
}

class ActionParserImpl(private val json: Json) : ActionParser {
    override fun parseAction(rawJson: String): Action? {
        return try {
            val jsonObject = json.parseToJsonElement(rawJson).jsonObject
            val actionType = jsonObject["action"]?.jsonPrimitive?.content
                ?: jsonObject["actionType"]?.jsonPrimitive?.content
                ?: return null
            
            val parameters = jsonObject["parameters"]?.jsonObject
            
            when (actionType) {
                "OPEN_APP" -> {
                    val appName = parameters?.get("appName")?.jsonPrimitive?.content
                        ?: parameters?.get("packageName")?.jsonPrimitive?.content
                        ?: jsonObject["appName"]?.jsonPrimitive?.content
                        ?: jsonObject["packageName"]?.jsonPrimitive?.content
                        ?: ""
                    OpenAppAction(appName)
                }
                "PLAY_MEDIA" -> {
                    val query = parameters?.get("query")?.jsonPrimitive?.content
                        ?: jsonObject["query"]?.jsonPrimitive?.content
                        ?: ""
                    val source = parameters?.get("source")?.jsonPrimitive?.content
                        ?: jsonObject["source"]?.jsonPrimitive?.content
                    PlayMediaAction(query = query, source = source)
                }
                "PAUSE_MEDIA" -> PauseMediaAction()
                "RESUME_MEDIA" -> ResumeMediaAction()
                "SKIP_MEDIA" -> SkipMediaAction()
                "PREVIOUS_MEDIA" -> PreviousMediaAction()
                "STOP_MEDIA" -> StopMediaAction()
                "CALL_CONTACT" -> {
                    val contactName = parameters?.get("contactName")?.jsonPrimitive?.content
                        ?: jsonObject["contactName"]?.jsonPrimitive?.content
                        ?: ""
                    CallContactAction(contactName)
                }
                "CALL" -> {
                    val phone = parameters?.get("phoneNumber")?.jsonPrimitive?.content
                        ?: parameters?.get("phone")?.jsonPrimitive?.content
                        ?: parameters?.get("number")?.jsonPrimitive?.content
                        ?: jsonObject["phoneNumber"]?.jsonPrimitive?.content
                        ?: jsonObject["phone"]?.jsonPrimitive?.content
                        ?: jsonObject["number"]?.jsonPrimitive?.content
                        ?: ""
                    CallAction(phone)
                }
                "SEND_SMS" -> {
                    val contactName = parameters?.get("contactName")?.jsonPrimitive?.content
                        ?: parameters?.get("contact")?.jsonPrimitive?.content
                        ?: jsonObject["contactName"]?.jsonPrimitive?.content
                        ?: jsonObject["contact"]?.jsonPrimitive?.content
                    
                    val phone = parameters?.get("phoneNumber")?.jsonPrimitive?.content
                        ?: parameters?.get("phone")?.jsonPrimitive?.content
                        ?: parameters?.get("number")?.jsonPrimitive?.content
                        ?: jsonObject["phoneNumber"]?.jsonPrimitive?.content
                        ?: jsonObject["phone"]?.jsonPrimitive?.content
                        ?: jsonObject["number"]?.jsonPrimitive?.content
                    
                    val msg = parameters?.get("message")?.jsonPrimitive?.content
                        ?: jsonObject["message"]?.jsonPrimitive?.content
                        ?: ""
                        
                    if (contactName != null) {
                        SendSmsAction(contactName, msg)
                    } else if (phone != null) {
                        SmsAction(phone, msg)
                    } else {
                        SendSmsAction("", msg)
                    }
                }
                "SET_ALARM" -> {
                    val time = parameters?.get("time")?.jsonPrimitive?.content
                        ?: jsonObject["time"]?.jsonPrimitive?.content
                    
                    if (time != null && time.isNotBlank()) {
                        val parsed = parseTime(time) ?: Pair(0, 0)
                        SetAlarmAction(parsed.first, parsed.second, null, time)
                    } else {
                        val hour = parameters?.get("hour")?.jsonPrimitive?.content?.toIntOrNull()
                            ?: jsonObject["hour"]?.jsonPrimitive?.content?.toIntOrNull()
                            ?: 0
                        val minute = parameters?.get("minute")?.jsonPrimitive?.content?.toIntOrNull()
                            ?: jsonObject["minute"]?.jsonPrimitive?.content?.toIntOrNull()
                            ?: 0
                        val label = parameters?.get("label")?.jsonPrimitive?.content
                            ?: jsonObject["label"]?.jsonPrimitive?.content
                        SetAlarmAction(hour, minute, label)
                    }
                }
                "SET_TIMER" -> {
                    val duration = parameters?.get("durationSeconds")?.jsonPrimitive?.content?.toLongOrNull()
                        ?: parameters?.get("duration")?.jsonPrimitive?.content?.toLongOrNull()
                        ?: jsonObject["durationSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: jsonObject["duration"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: 0L
                    val label = parameters?.get("label")?.jsonPrimitive?.content
                        ?: jsonObject["label"]?.jsonPrimitive?.content
                    SetTimerAction(duration, label)
                }
                "VOLUME_UP" -> VolumeUpAction()
                "VOLUME_DOWN" -> VolumeDownAction()
                "SET_VOLUME" -> {
                    val level = parameters?.get("level")?.jsonPrimitive?.content?.toIntOrNull()
                        ?: jsonObject["level"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: 50
                    SetVolumeAction(level)
                }
                "TOGGLE_FLASHLIGHT" -> {
                    val enabled = parameters?.get("enabled")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        ?: jsonObject["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        ?: true
                    ToggleFlashlightAction(enabled)
                }
                "TAKE_SCREENSHOT" -> TakeScreenshotAction()
                "SEND_WHATSAPP" -> {
                    val contactName = parameters?.get("contactName")?.jsonPrimitive?.content
                        ?: jsonObject["contactName"]?.jsonPrimitive?.content
                        ?: ""
                    val msg = parameters?.get("message")?.jsonPrimitive?.content
                        ?: jsonObject["message"]?.jsonPrimitive?.content
                        ?: ""
                    SendWhatsAppAction(contactName, msg)
                }
                "GET_BATTERY" -> GetBatteryAction()
                "GET_CURRENT_TIME" -> GetCurrentTimeAction()
                else -> {
                    val params = parameters?.mapValues { it.value.jsonPrimitive.content }
                        ?: jsonObject.filterKeys { it != "action" && it != "type" && it != "parameters" }.mapValues { entry ->
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
