package matt.bot.discord.laplace

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.*
import net.dv8tion.jda.core.requests.RestAction
import net.dv8tion.jda.core.requests.restaction.AuditableRestAction
import net.dv8tion.jda.core.requests.restaction.MessageAction
import java.io.File
import java.net.URI
import java.time.OffsetDateTime
import java.util.*

fun commandLine(bot: JDA)
{
    var selectedGuild: Guild? = null
    var selectedTextChannel: TextChannel? = null
    var selectedVoiceChannel: VoiceChannel? = null
    
    val scanner = Scanner(System.`in`)
    lineLoop@while(true)
    {
        val line = scanner.nextLine()
        val tokenizer = Tokenizer(line)
        if(!tokenizer.hasNext())
            continue
        
        val command = tokenizer.next().tokenValue
        when(command)
        {
            "shutdown" -> shutdownBot(bot)
            "reload" -> reloadBot(bot)
            "selectedGuild" -> println(selectedGuild?.name)
            "selectedTextChannel" -> println(selectedTextChannel?.name)
            "selectedVoiceChannel" -> println(selectedVoiceChannel?.name)
            "list" -> {
                if(tokenizer.hasNext())
                {
                    when(tokenizer.remainingTextAsToken.tokenValue)
                    {
                        "guilds" -> bot.guilds.map {it.name}.forEach {println(it)}
                        "textChannels" ->
                        {
                            if(selectedGuild == null)
                                println("Select a guild first")
                            else
                                selectedGuild.textChannels.map {it.name}.forEach {println(it)}
                        }
                        "voiceChannels" ->
                        {
                            if(selectedGuild == null)
                                println("Select a guild first")
                            else
                                selectedGuild.voiceChannels.map {it.name}.forEach {println(it)}
                        }
                        else -> println("Unknown command")
                    }
                }
                else
                {
                    println("Not enough command arguments")
                }
            }
            "select" -> {
                if(!tokenizer.hasNext())
                {
                    println("Not enough command arguments")
                    continue@lineLoop
                }
                val category = tokenizer.next().tokenValue
                if(!tokenizer.hasNext())
                {
                    println("Not enough command arguments")
                    continue@lineLoop
                }
                val value = tokenizer.remainingTextAsToken.tokenValue
                when(category)
                {
                    "guild" -> {
                        bot.guilds.firstOrNull {it.name == value}?.let {
                            selectedGuild = it
                            selectedTextChannel = null
                            selectedVoiceChannel = null
                        } ?: println("Cannot find guild with name \"$value\"")
                    }
                    "textChannel" -> {
                        if(selectedGuild == null)
                            println("Select a guild first")
                        else
                            selectedGuild!!.textChannels.firstOrNull {it.name == value}?.let {selectedTextChannel = it} ?: println("Cannot find text channel with name \"$value\"")
                    }
                    "voiceChannel" -> {
                        if(selectedGuild == null)
                            println("Select a guild first")
                        else
                            selectedGuild!!.voiceChannels.firstOrNull {it.name == value}?.let {selectedVoiceChannel = it} ?: println("Cannot find voice channel with name \"$value\"")
                    }
                }
            }
            "type" -> {
                if(selectedTextChannel == null)
                    println("Select a text channel first")
                else
                    MessageBuilder(tokenizer.remainingTextAsToken.tokenValue).sendTo(selectedTextChannel).complete()
            }
            "join" -> {
                if(selectedVoiceChannel == null)
                    println("Select a voice channel first")
                else
                    selectedVoiceChannel!!.guild.audioManager.openAudioConnection(selectedVoiceChannel)
            }
            "leave" -> {
                if(selectedGuild == null)
                    println("Select a guild first")
                else if(!selectedGuild!!.audioManager.isConnected)
                    println("Not connected to a voice channel")
                else
                    selectedGuild!!.audioManager.closeAudioConnection()
            }
            "play" -> {
                if(selectedGuild == null)
                    println("Select a guild first")
                else if(!selectedGuild!!.audioManager.isConnected)
                    println("Connect to a voice channel first")
                else
                {
                    val musicManager = joinedGuilds[selectedGuild!!]!!.musicManager
                    if(tokenizer.hasNext())
                    {
                        val uri = File(tokenizer.remainingTextAsToken.tokenValue).run {if(exists()) toURI() else null} ?: URI(tokenizer.remainingTextAsToken.tokenValue)
                        playerManager.loadItemOrdered(musicManager, uri.toString(), object : AudioLoadResultHandler
                        {
                            override fun loadFailed(exception: FriendlyException)
                            {
                                println("Could not load track")
                            }
        
                            override fun trackLoaded(track: AudioTrack)
                            {
                                musicManager.scheduler.queue(track)
                            }
        
                            override fun noMatches()
                            {
                                println("Could not find track")
                            }
        
                            override fun playlistLoaded(playlist: AudioPlaylist)
                            {
                                if(playlist.selectedTrack != null)
                                    musicManager.scheduler.queue(playlist.selectedTrack)
                                else
                                    playlist.tracks.forEach {musicManager.scheduler.queue(it)}
                            }
                        })
                    }
                    else
                    {
                        musicManager.scheduler.resume()
                    }
                }
            }
            "pause" -> joinedGuilds[selectedGuild!!]!!.musicManager.scheduler.pause()
            "skip" -> {
                if(selectedGuild == null)
                    println("Select a guild first")
                else if(!selectedGuild!!.audioManager.isConnected)
                    println("Connect to a voice channel first")
                else
                    joinedGuilds[selectedGuild!!]!!.musicManager.scheduler.nextTrack()
            }
            "volume" -> {
                @Suppress("CascadeIf")
                if(selectedGuild == null)
                    println("Select a guild first")
                else if(tokenizer.hasNext())
                    joinedGuilds[selectedGuild!!]!!.musicManager.player.volume = tokenizer.remainingTextAsToken.tokenValue.toInt()
                else
                    println("Volume: ${joinedGuilds[selectedGuild!!]!!.musicManager.player.volume}")
            }
            "external" -> {
                if(selectedGuild == null)
                    println("Select a guild first")
                else
                    runCommand(tokenizer.next().tokenValue, tokenizer, CommandLineMessage(selectedGuild!!, selectedTextChannel, line))
            }
        }
    }
}

private class CommandLineMessage(private val guild: Guild, private val textChannel: TextChannel?, private val text: String): Message
{
    override fun isFromType(type: ChannelType?) = type == textChannel?.type ?: ChannelType.UNKNOWN
    override fun getGroup() = null
    override fun isEdited() = false
    override fun isPinned() = false
    override fun mentionsEveryone() = false
    override fun addReaction(emote: Emote?): RestAction<Void>? = null
    override fun addReaction(unicode: String?): RestAction<Void>? = null
    override fun clearReactions(): RestAction<Void>? = null
    override fun formatTo(p0: Formatter?, p1: Int, p2: Int, p3: Int) {}
    override fun getContentRaw() = text
    override fun getContentStripped() = text
    override fun getGuild() = guild
    override fun isTTS() = false
    override fun isMentioned(mentionable: IMentionable?, vararg types: Message.MentionType?) = false
    override fun editMessageFormat(format: String?, vararg args: Any?): MessageAction? = null
    override fun getMentionedChannels() = mutableListOf<TextChannel>()
    override fun getMember(): Member = guild.owner
    override fun getIdLong() = 0L
    override fun getContentDisplay() = text
    override fun getPrivateChannel(): PrivateChannel? = null
    override fun getChannelType() = textChannel?.type ?: ChannelType.UNKNOWN
    override fun getAttachments() = mutableListOf<Message.Attachment>()
    override fun getMentionedRoles() = mutableListOf<Role>()
    override fun pin(): RestAction<Void>? = null
    override fun getMentionedMembers(guild: Guild?) = mutableListOf<Member>()
    override fun getMentionedMembers() = mutableListOf<Member>()
    override fun unpin(): RestAction<Void>? = null
    override fun getCategory(): Category? = null
    override fun getInvites() = mutableListOf<String>()
    override fun getEditedTime(): OffsetDateTime = OffsetDateTime.MAX
    override fun getMentionedUsers() = mutableListOf<User>()
    override fun getEmotes() = mutableListOf<Emote>()
    override fun getAuthor(): User = guild.owner.user
    override fun editMessage(newContent: CharSequence?): MessageAction? = null
    override fun editMessage(newContent: MessageEmbed?): MessageAction? = null
    override fun editMessage(newContent: Message?): MessageAction? = null
    override fun delete(): AuditableRestAction<Void>? = null
    override fun getMentions(vararg types: Message.MentionType?) = mutableListOf<IMentionable>()
    override fun isWebhookMessage() = false
    override fun getEmbeds() = mutableListOf<MessageEmbed>()
    override fun getType() = MessageType.UNKNOWN
    override fun getChannel(): MessageChannel? = textChannel
    override fun getJDA() = bot
    override fun getReactions() = mutableListOf<MessageReaction>()
    override fun getTextChannel(): TextChannel? = textChannel
    override fun getNonce() = ""
}