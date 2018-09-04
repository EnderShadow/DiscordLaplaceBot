package matt.bot.discord.laplace

import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.Role
import net.dv8tion.jda.core.entities.TextChannel

class GuildInfo(val guild: Guild, serverAdminRoles: List<Role> = emptyList(), var initialRole: Role? = null, var rulesChannel: TextChannel? = null,
                var welcomeMessageChannel: TextChannel? = guild.defaultChannel, var userLeaveChannel: TextChannel? = guild.defaultChannel,
                var userBannedChannel: TextChannel? = guild.defaultChannel, var botLogChannel: TextChannel? = null, var musicChannel: TextChannel? = null,
                var welcomeChannel: TextChannel? = null)
{
    val serverAdminRoles = serverAdminRoles.toMutableList()
    val musicManager = GuildMusicManager(guild, playerManager)
    val messageBuffer = RingBuffer<Message>(1 shl 9) // 512
    
    init
    {
        guild.audioManager.sendingHandler = musicManager.sendHandler
        musicManager.player.volume = 50
    }
}