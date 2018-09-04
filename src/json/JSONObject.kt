package json

class JSONObject(map: Map<String, Any?> = emptyMap()): LinkedHashMap<String, Any?>(map)
{
    fun string(key: String) = this[key] as String?
    fun number(key: String) = this[key] as Number?
    fun boolean(key: String) = this[key] as Boolean?
    fun jsonObject(key: String) = this[key] as JSONObject
    fun jsonArray(key: String) = this[key] as JSONArray
    fun isNull(key: String) = key in this && this[key] == null
    
    override fun toString() = "{${entries.joinToString(",") {(key, value) -> "\"$key\":${toString(value)}"}}}"
    
    private fun toString(obj: Any?): String
    {
        if(obj is String)
            return "\"${obj.replace("\\", "\\\\")}\""
        return obj.toString()
    }
}