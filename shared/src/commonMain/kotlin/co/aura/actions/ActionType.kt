package co.aura.actions

enum class ActionType(val value: String) {
    OPEN_APP("OPEN_APP"),
    GET_BATTERY("GET_BATTERY"),
    GET_CURRENT_TIME("GET_CURRENT_TIME"),
    SET_ALARM("SET_ALARM"),
    CALL("CALL"),
    SEND_SMS("SEND_SMS")
}
