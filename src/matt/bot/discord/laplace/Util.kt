package matt.bot.discord.laplace

import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import org.json.JSONObject

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

/**
 * All items matching the filter are put into the first list. All items not matching the filter are put into the second list
 */
fun <T> Collection<T>.split(filter: (T) -> Boolean) = splitAndMap(filter) {it}

/**
 * All items matching the filter are put into the first list. All items not matching the filter are put into the second list
 */
fun <T, U> Collection<T>.splitAndMap(filter: (T) -> Boolean, mapper: (T) -> (U)): Pair<List<U>, List<U>>
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