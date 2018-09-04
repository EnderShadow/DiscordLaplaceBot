package json

class JSONArray(data: List<Any?> = emptyList()): ArrayList<Any?>(data.toList())
{
    fun string(index: Int) = this[index] as String?
    fun number(index: Int) = this[index] as Number?
    fun boolean(index: Int) = this[index] as Boolean?
    fun jsonObject(index: Int) = this[index] as JSONObject
    fun jsonArray(index: Int) = this[index] as JSONArray
    fun isNull(index: Int) = this[index] == null
    
    override fun toString() = "[${joinToString(",") {toString(it)}}]"
    
    private fun toString(obj: Any?): String
    {
        if(obj is String)
            return "\"${obj.replace("\\", "\\\\")}\""
        return obj.toString()
    }
}