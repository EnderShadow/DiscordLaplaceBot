package matt.bot.discord.laplace

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import net.dv8tion.jda.core.entities.Guild

/**
 * Holder for both the player and a track scheduler for one guild.
 */
class GuildMusicManager
/**
 * Creates a player and a track scheduler.
 * @param manager Audio player manager to use for creating the player.
 */
(private val guild: Guild, manager: AudioPlayerManager)
{
    /**
     * Audio player for the guild.
     */
    val player: AudioPlayer = manager.createPlayer()
    /**
     * Track scheduler for the player.
     */
    val scheduler = TrackScheduler(guild, player)
    
    /**
     * @return Wrapper around AudioPlayer to use it as an AudioSendHandler.
     */
    val sendHandler: AudioPlayerSendHandler
        get() = AudioPlayerSendHandler(guild, player)
    
    init
    {
        player.addListener(scheduler)
    }
}