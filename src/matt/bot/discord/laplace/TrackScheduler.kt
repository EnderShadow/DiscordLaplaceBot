package matt.bot.discord.laplace

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import net.dv8tion.jda.core.entities.Guild
import java.util.concurrent.LinkedBlockingQueue

/**
 * This class schedules tracks for the audio player. It contains the queue of tracks.
 */
class TrackScheduler
/**
 * @param player The audio player this scheduler uses
 */
(private val guild: Guild, private val player: AudioPlayer) : AudioEventAdapter()
{
    private val queue = LinkedBlockingQueue<AudioTrack>()
    val durationSeconds
        get() = (queue.map {it.duration}.sum() + (player.playingTrack?.let {it.duration - it.position} ?: 0)) / 1000.0
    
    /**
     * Add the next track to queue or play right away if nothing is in the queue.
     *
     * @param track The track to play or add to queue.
     */
    fun queue(track: AudioTrack)
    {
        // Calling startTrack with the noInterrupt set to true will start the track only if nothing is currently playing. If
        // something is playing, it returns false and does nothing. In that case the player was already playing so this
        // track goes to the queue instead.
        if(!player.startTrack(track, true))
        {
            queue.offer(track)
        }
        else
        {
            player.volume = joinedGuilds[guild]!!.let {it.volume * it.volumeMultipliers.getOrDefault(track.info.uri, 1.0)}.toInt()
            joinedGuilds[guild]!!.musicChannel?.sendMessage("Now playing ${track.info.title}")?.complete()
        }
    }
    
    fun restartSong()
    {
        player.playingTrack.position = 0
    }
    
    fun remove(index: Int)
    {
        if(index == -2)
            queue.remove(queue.elementAt(queue.size - 1))
        else
            queue.remove(queue.elementAt(index))
    }
    
    fun numSongs() = queue.size
    
    fun getQueuedSongs() = queue.mapIndexed {index, track -> "${index + 1}: ${track.info.title}"}.joinToString("\n")
    
    fun stop(clearQueue: Boolean = true)
    {
        player.isPaused = false
        player.stopTrack()
        if(clearQueue)
            queue.clear()
    }
    
    /**
     * Start the next track, stopping the current one if it is playing.
     */
    fun nextTrack()
    {
        player.isPaused = false
        
        if(queue.isNotEmpty())
            joinedGuilds[guild]!!.musicChannel?.sendMessage("Now playing ${queue.peek().info.title}")?.complete()
        // Start the next track, regardless of if something is already playing or not. In case queue was empty, we are
        // giving null to startTrack, which is a valid argument and will simply stop the player.
        val track = queue.poll()
        player.startTrack(track, false)
        if(track != null)
            player.volume = joinedGuilds[guild]!!.let {it.volume * it.volumeMultipliers.getOrDefault(track.info.uri, 1.0)}.toInt()
    }
    
    fun pause()
    {
        if(!player.isPaused && player.playingTrack != null)
            player.isPaused = true
    }
    
    fun resume()
    {
        if(player.isPaused && player.playingTrack != null)
            player.isPaused = false
    }
    
    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason)
    {
        // Only start the next track if the end reason is suitable for it (FINISHED or LOAD_FAILED)
        if(endReason.mayStartNext)
        {
            nextTrack()
        }
    }
}