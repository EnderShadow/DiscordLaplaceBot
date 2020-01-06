package matt.bot.discord.laplace

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.Guild
import java.nio.ByteBuffer
import java.util.*
import kotlin.concurrent.schedule

/**
 * This is a wrapper around AudioPlayer which makes it behave as an AudioSendHandler for JDA. As JDA calls canProvide
 * before every call to provide20MsAudio(), we pull the frame in canProvide() and use the frame we already pulled in
 * provide20MsAudio().
 */
class AudioPlayerSendHandler
/**
 * @param audioPlayer Audio player to wrap.
 */
(private val guild: Guild, private val audioPlayer: AudioPlayer) : AudioSendHandler
{
    private var frame = MutableAudioFrame()
    private var buffer = ByteBuffer.allocate(1024)
    private var timer = Timer("autoDisconnectTimer", true)
    private var lastTask: TimerTask? = null
    
    init {
        frame.setBuffer(buffer)
    }
    
    override fun canProvide() = audioPlayer.provide(frame)
    
    override fun provide20MsAudio(): ByteBuffer?
    {
        lastTask?.cancel()
        lastTask = timer.schedule(300_000) {
            guild.audioManager.closeAudioConnection()
        }
        
        buffer.flip()
        return buffer
    }
    
    override fun isOpus(): Boolean
    {
        return true
    }
}