package matt.bot.discord.laplace

import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message

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