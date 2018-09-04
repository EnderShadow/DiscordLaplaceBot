package json

import java.io.File
import java.math.BigDecimal
import java.math.BigInteger

object JSONParser
{
    fun parse(file: File) = parse(file.readText())
    
    /**
     * Will return a JSONObject, JSONArray
     */
    fun parse(json: String): Any
    {
        var index = 0
        while(index < json.length)
        {
            if(!json[index].isWhitespace())
            {
                return when(json[index])
                {
                    '{' -> parseObject(json, index).first
                    '[' -> parseArray(json, index).first
                    else -> throw IllegalArgumentException("Malformed JSON")
                }
            }
            index++
        }
        throw IllegalArgumentException("Malformed JSON")
    }
    
    private fun parseObject(json: String, index: Int): Pair<JSONObject, Int>
    {
        @Suppress("NAME_SHADOWING")
        var index = index + 1
        val obj = JSONObject()
        var foundKey: String? = null
        while(index < json.length)
        {
            if(!json[index].isWhitespace())
            {
                if(foundKey == null)
                {
                    if(json[index] == '}')
                        return Pair(obj, index)
                    else if(json[index] != ',')
                    {
                        // Since there is no stored key, this must be a new entry
                        if (json[index] != '"')
                            throw IllegalArgumentException("Malformed JSON")
                        val data = parseString(json, index)
                        foundKey = data.first
                        index = data.second
                    }
                }
                else if(json[index] in "-0123456789")
                {
                    val data = parseNumber(json, index)
                    obj[foundKey] = data.first
                    index = data.second
                    foundKey = null
                }
                else
                {
                    when(json[index])
                    {
                        '"' -> {
                            val data = parseString(json, index)
                            obj[foundKey] = data.first
                            index = data.second
                            foundKey = null
                        }
                        '{' -> {
                            val data = parseObject(json, index)
                            obj[foundKey] = data.first
                            index = data.second
                            foundKey = null
                        }
                        '[' -> {
                            val data = parseArray(json, index)
                            obj[foundKey] = data.first
                            index = data.second
                            foundKey = null
                        }
                        't' -> {
                            if(json.startsWith("true", index))
                            {
                                obj[foundKey] = true
                                index += 3
                                foundKey = null
                            }
                            else
                                throw IllegalArgumentException("Malformed boolean")
                        }
                        'f' -> {
                            if(json.startsWith("false", index))
                            {
                                obj[foundKey] = false
                                index += 4
                                foundKey = null
                            }
                            else
                                throw IllegalArgumentException("Malformed boolean")
                        }
                        'n' -> {
                            if(json.startsWith("null", index))
                            {
                                obj[foundKey] = null
                                index += 3
                                foundKey = null
                            }
                            else
                                throw IllegalArgumentException("Malformed null object")
                        }
                        ':' -> {}
                        else -> throw IllegalArgumentException("Malformed JSON: ${json[index]}")
                    }
                }
            }
            index++
        }
        throw IllegalArgumentException("Malformed JSON Object")
    }
    
    private fun parseArray(json: String, index: Int): Pair<JSONArray, Int>
    {
        @Suppress("NAME_SHADOWING")
        var index = index + 1
        val arr = JSONArray()
        while(index < json.length)
        {
            if(!json[index].isWhitespace())
            {
                if(json[index] == ']')
                {
                    return Pair(arr, index)
                }
                else if(json[index] == ',') // ignore commas
                else if(json[index] in "-0123456789")
                {
                    val data = parseNumber(json, index)
                    arr.add(data.first)
                    index = data.second
                }
                else
                {
                    when(json[index])
                    {
                        '"' -> {
                            val data = parseString(json, index)
                            arr.add(data.first)
                            index = data.second
                        }
                        '{' -> {
                            val data = parseObject(json, index)
                            arr.add(data.first)
                            index = data.second
                        }
                        '[' -> {
                            val data = parseArray(json, index)
                            arr.add(data.first)
                            index = data.second
                        }
                        't' -> {
                            if(json.startsWith("true", index))
                            {
                                arr.add(true)
                                index += 3
                            }
                            else
                                throw IllegalArgumentException("Malformed boolean")
                        }
                        'f' -> {
                            if(json.startsWith("false", index))
                            {
                                arr.add(false)
                                index += 4
                            }
                            else
                                throw IllegalArgumentException("Malformed boolean")
                        }
                        'n' -> {
                            if(json.startsWith("null", index))
                            {
                                arr.add(null)
                                index += 3
                            }
                            else
                                throw IllegalArgumentException("Malformed null object")
                        }
                        else -> throw IllegalArgumentException("Malformed JSON")
                    }
                }
            }
            index++
        }
        throw IllegalArgumentException("Malformed JSON Array")
    }
    
    private fun parseString(json: String, index: Int): Pair<String, Int>
    {
        val sb = StringBuilder()
        if(json[index] != '"')
            throw IllegalArgumentException("Invalid String")
        @Suppress("NAME_SHADOWING")
        var index = index + 1
        while(index < json.length)
        {
            when(json[index])
            {
                '"' -> return Pair(sb.toString(), index)
                '\\' -> {
                    when(json[index + 1])
                    {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> sb.append(Character.toChars(json.substring(index + 2, index + 6).toInt(16)))
                        else -> throw IllegalArgumentException("Invalid escape sequence in string")
                    }
                    if(json[index + 1] == 'u')
                        index += 5
                    else
                        index++
                }
                else -> sb.append(json[index])
            }
            index++
        }
        throw IllegalArgumentException("Malformed String")
    }
    
    private fun parseNumber(json: String, index: Int): Pair<Number, Int>
    {
        var negative = false
        var digits = ""
        var negativeExponent = false
        var exponent = ""
        @Suppress("NAME_SHADOWING")
        var index = index
        if(json[index] == '-')
        {
            negative = true
            index++
        }
        
        if(json.startsWith("Infinity", index, true))
            return Pair(if(negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY, index + 8)
        
        // find digits
        while(index < json.length)
        {
            if(json[index] == '.')
            {
                if('.' !in digits)
                {
                    if(digits.isEmpty())
                        digits = "0."
                    else
                        digits += '.'
                }
                else
                    throw IllegalArgumentException("Invalid number format")
            }
            else if(json[index] in "0123456789")
                digits += json[index]
            else
                break
            index++
        }
        
        if(json[index] in "eE")
        {
            if(json[index + 1] in "+-")
            {
                negativeExponent = json[index + 1] == '-'
                index += 2
            }
            else
            {
                index++
            }
            while(index < json.length && json[index] in "0123456789")
                exponent += json[index++]
        }
        
        while(digits.startsWith("00"))
            digits = digits.substring(1)
        if(digits.length > 1 && digits[0] == '0' && digits[1].isDigit())
            digits = digits.substring(1)
        while(exponent.startsWith("00"))
            exponent = exponent.substring(1)
        if(exponent.length > 1 && exponent[0] == '0' && exponent[1].isDigit())
            exponent = exponent.substring(1)
        
        var number: Number
        if(negative)
            digits = "-$digits"
        if(negativeExponent)
            exponent = "-$exponent"
        if('.' in digits || exponent.isNotEmpty())
        {
            number = if(exponent.isEmpty()) digits.toDouble() else "${digits}E$exponent".toDouble()
            if(number.isInfinite())
                number = BigDecimal(if(exponent.isEmpty()) digits else "${digits}E$exponent")
        }
        else
        {
            var temp: Number? = digits.toIntOrNull()
            if(temp == null)
                temp = digits.toLongOrNull()
            if(temp == null)
                temp = BigInteger(digits)
            number = temp
        }
        
        return Pair(number, index - 1)
    }
}