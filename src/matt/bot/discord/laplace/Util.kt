package matt.bot.discord.laplace

import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import org.json.JSONObject
import java.lang.IllegalStateException

fun countMentions(message: Message) = message.mentionedChannels.size + message.mentionedRoles.size + message.mentionedUsers.size + if(message.mentionsEveryone()) 1 else 0

fun isServerAdmin(member: Member) = member.isOwner || member.roles.intersect(joinedGuilds[member.guild]!!.serverAdminRoles).isNotEmpty()

fun reloadBot(bot: JDA)
{
    shutdownMode = ExitMode.RELOAD
    bot.shutdown()
}

fun shutdownBot(bot: JDA)
{
    shutdownMode = ExitMode.SHUTDOWN
    bot.shutdown()
}

fun toOrdinal(num: Int): String
{
    val numAsString = num.toString()
    return when(numAsString.last())
    {
        '1' -> "${numAsString}st"
        '2' -> "${numAsString}nd"
        '3' -> "${numAsString}rd"
        else -> "${numAsString}th"
    }
}

fun splitAt2000(text: String): List<String>
{
    if(text.length <= 2000)
        return listOf(text)
    val splitIndex = text.lastIndexOf('\n', 2000)
    return if(splitIndex < 0)
        listOf(text.substring(0, 2000)) + splitAt2000(text.substring(2000))
    else
        listOf(text.substring(0, splitIndex)) + splitAt2000(text.substring(splitIndex))
}

/**
 * All items matching the filter are put into the first list. All items not matching the filter are put into the second list
 */
inline fun <reified T> Collection<T>.split(filter: (T) -> Boolean) = splitAndMap(filter) {it}

/**
 * All items matching the filter are put into the first list. All items not matching the filter are put into the second list
 */
inline fun <reified T, reified U> Collection<T>.splitAndMap(filter: (T) -> Boolean, mapper: (T) -> (U)): Pair<List<U>, List<U>>
{
    val l1 = mutableListOf<U>()
    val l2 = mutableListOf<U>()
    forEach {
        if(filter(it))
            l1.add(mapper(it))
        else
            l2.add(mapper(it))
    }
    return Pair(l1, l2)
}

fun JSONObject.getStringOrNull(key: String) = if(isNull(key)) null else getString(key)

inline fun <reified T, reified R> T.retry(amt: Int, provider: (T) -> R): R
{
    if(amt <= 0)
    {
        while(true)
        {
            try
            {
                return provider(this)
            }
            catch(_: Throwable) {}
        }
    }
    else
    {
        for(i in 0 until amt)
        {
            try
            {
                return provider(this)
            }
            catch(t: Throwable)
            {
                if(i == amt - 1)
                    throw t
            }
        }
        throw IllegalStateException("This should never happen")
    }
}

fun String.containsSparse(text: String): Boolean
{
    if(text.length > length)
        return false
    var index = 0
    for(c in text)
    {
        index = indexOf(c, index) + 1
        if(index <= 0)
            return false
    }
    return true
}