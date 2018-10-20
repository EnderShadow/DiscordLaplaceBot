package matt.bot.discord.laplace

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.VoiceChannel
import java.lang.StringBuilder
import kotlin.math.min

val urlRegex = Regex("^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]")

fun runCommand(command: String, tokenizer: Tokenizer, sourceMessage: Message)
{
    if(!sourceMessage.channelType.isGuild)
        return
    
    when(command)
    {
        "whoami" -> MessageBuilder("You are ${sourceMessage.member}").sendTo(sourceMessage.channel).complete()
        "saved" -> {
            if(!tokenizer.hasNext())
                return
            val savedMode = tokenizer.next().tokenValue
            when(savedMode)
            {
                "set" -> {
                    if(!tokenizer.hasNext())
                        return
                    val key = tokenizer.next().tokenValue
                    if(!tokenizer.hasNext())
                        return
                    val value = tokenizer.remainingTextAsToken.tokenValue
                    @Suppress("UNCHECKED_CAST")
                    savedUserText.getOrPut(sourceMessage.author.id) {mutableMapOf()}[key] = value
                    sourceMessage.channel.sendMessage("Text saved successfully").complete()
                    save()
                }
                "get" -> {
                    if(tokenizer.hasNext())
                    {
                        val key = tokenizer.remainingTextAsToken.tokenValue
                        savedUserText.getOrDefault(sourceMessage.author.id, null)?.getOrDefault(key, null)?.let {sourceMessage.channel.sendMessage(it).complete()} ?: sourceMessage.channel.sendMessage("Could not find any text saved under: $key").complete()
                    }
                }
                "remove" -> {
                    if(!tokenizer.hasNext())
                        return
                    
                    if(savedUserText.getOrDefault(sourceMessage.author.id, mutableMapOf()).remove(tokenizer.remainingTextAsToken.tokenValue) != null)
                    {
                        sourceMessage.channel.sendMessage("Removed text saved with key: ${tokenizer.remainingTextAsToken.tokenValue}")
                        if(sourceMessage.author.id in savedUserText && savedUserText[sourceMessage.author.id]!!.isEmpty())
                            savedUserText.remove(sourceMessage.author.id)
                        save()
                    }
                }
                "list" -> {
                    val textMap = savedUserText.getOrDefault(sourceMessage.author.id, mutableMapOf())
                    if(textMap.isEmpty())
                        sourceMessage.channel.sendMessage("You don't have any text saved").complete()
                    else
                    {
                        val text = splitAt2000("Here is what you have saved\n\n${textMap.entries.joinToString("\n") {"${it.key}: ${sanitize(it.value)}"}}")
                        text.forEach {sourceMessage.channel.sendMessage(it).complete()}
                    }
                }
                "play" -> {
                    if(!tokenizer.hasNext())
                        return
                    
                    val key = tokenizer.remainingTextAsToken.tokenValue
                    val url = savedUserText.getOrDefault(sourceMessage.author.id, null)?.getOrDefault(key, null)
                    if(url == null)
                        sourceMessage.channel.sendMessage("Could not find any text saved under: $key").complete()
                    else
                        loadAndPlay(sourceMessage, url)
                }
            }
        }
        "play" -> loadAndPlay(sourceMessage, if(tokenizer.hasNext()) tokenizer.remainingTextAsToken.tokenValue else null)
        "restart" -> joinedGuilds[sourceMessage.guild]!!.musicManager.scheduler.restartSong()
        "queue" -> {
            val musicManager = joinedGuilds[sourceMessage.guild]!!.musicManager
            if(tokenizer.hasNext() && tokenizer.remainingTextAsToken.tokenValue.equals("duration", true))
            {
                val stringBuilder = StringBuilder()
                val duration = musicManager.scheduler.durationSeconds
                val hours = duration.toInt() / 3600
                val minutes = (duration.toInt() / 60) % 60
                val seconds = duration.toInt() - hours * 3600 - minutes * 60
                if(hours == 1)
                    stringBuilder.append("1 hour ")
                else if(hours > 1)
                    stringBuilder.append("$hours hours ")
                if(minutes == 1)
                    stringBuilder.append("1 minute ")
                else(minutes > 1)
                    stringBuilder.append("$minutes minutes ")
                if(stringBuilder.isNotEmpty())
                    stringBuilder.append("and ")
                if(seconds == 1)
                    stringBuilder.append("1 second")
                else
                    stringBuilder.append("$seconds seconds")
                sourceMessage.channel.sendMessage("The queue is $stringBuilder long").complete()
            }
            else if(tokenizer.hasNext() && tokenizer.remainingTextAsToken.tokenValue.equals("length", true))
            {
                sourceMessage.channel.sendMessage("The queue contains ${musicManager.scheduler.numSongs()} songs.").complete()
            }
            else
            {
                val currentlyPlaying = musicManager.player.playingTrack?.let {"0 (Currently Playing): ${it.info.title}\n"} ?: ""
                if(musicManager.scheduler.numSongs() > 0 || currentlyPlaying.isNotBlank())
                {
                    val text = splitAt2000("Here are the queued songs.\n\n$currentlyPlaying${musicManager.scheduler.getQueuedSongs()}")
                    text.forEach {sourceMessage.channel.sendMessage(it).queue()}
                }
                else
                    sourceMessage.channel.sendMessage("There are no queued songs.").complete()
            }
        }
        "pause" -> joinedGuilds[sourceMessage.guild]!!.musicManager.scheduler.pause()
        "stop" -> stopMusic(sourceMessage.guild)
        "skip" -> {
            if(tokenizer.hasNext())
            {
                val nextTokens = mutableListOf<Token>()
                while(tokenizer.hasNext())
                    nextTokens.add(tokenizer.next())
                val ranges = nextTokens.asSequence().filter {it.objValue is LongRange}.map {it.objValue as LongRange}.toList()
                var indices = nextTokens.asSequence().filter {it.objValue is Number}.map {(it.objValue as Number).toLong()}.toList()
                indices = (indices + ranges.flatMap {it.toList()}).asSequence().toSet().sortedDescending()
                indices.forEachIndexed {i, index ->
                    if(index >= -1 && index <= joinedGuilds[sourceMessage.guild]!!.musicManager.scheduler.numSongs())
                        skipTrack(sourceMessage.guild, index.toInt() - 1, i != 0)
                }
            }
            else
                skipTrack(sourceMessage.guild, -1)
        }
        "volume" -> {
            if(tokenizer.hasNext())
                tokenizer.remainingTextAsToken.tokenValue.toIntOrNull()?.let {joinedGuilds[sourceMessage.guild!!]!!.musicManager.player.volume = it.coerceIn(0, 200)}
            else
                sourceMessage.channel.sendMessage("Volume is currently set to ${joinedGuilds[sourceMessage.guild!!]!!.musicManager.player.volume}").complete()
        }
        "say" -> {
            if(isServerAdmin(sourceMessage.member))
            {
                var content = tokenizer.remainingTextAsToken.tokenValue
                val tts = content.endsWith("!tts")
                if(tts)
                    content = content.substring(0, content.length - 4).trim()
                MessageBuilder(content).sendTo(sourceMessage.channel).tts(tts).complete()
                joinedGuilds[sourceMessage.guild]!!.messageBuffer.remove(sourceMessage)
                sourceMessage.delete().complete()
                println("${sourceMessage.author.name} made me say \"$content\"")
            }
        }
        "initialRole" -> {
            if(isServerAdmin(sourceMessage.member) && tokenizer.hasNext())
            {
                joinedGuilds[sourceMessage.guild]!!.initialRole = sourceMessage.guild.getRoleById(tokenizer.remainingTextAsToken.tokenValue)
                save()
            }
        }
        "channel" -> {
            if(isServerAdmin(sourceMessage.member) && tokenizer.hasNext())
            {
                val channelMode = tokenizer.next().tokenValue
                if(!tokenizer.hasNext())
                    return
                if(channelMode == "get")
                {
                    val channelCategory = tokenizer.remainingTextAsToken.tokenValue
                    when(channelCategory)
                    {
                        "rules" -> joinedGuilds[sourceMessage.guild]!!.rulesChannel?.id?.run {MessageBuilder("<#$this>")} ?: MessageBuilder("none")
                        "welcomeMessage" -> joinedGuilds[sourceMessage.guild]!!.welcomeMessageChannel?.id?.run {MessageBuilder("<#$this>")} ?: MessageBuilder("none")
                        "userLeft" -> joinedGuilds[sourceMessage.guild]!!.userLeaveChannel?.id?.run {MessageBuilder("<#$this>")} ?: MessageBuilder("none")
                        "userBanned" -> joinedGuilds[sourceMessage.guild]!!.userBannedChannel?.id?.run {MessageBuilder("<#$this>")} ?: MessageBuilder("none")
                        "botLog" -> joinedGuilds[sourceMessage.guild]!!.botLogChannel?.id?.run {MessageBuilder("<#$this>")} ?: MessageBuilder("none")
                        "music" -> joinedGuilds[sourceMessage.guild]!!.musicChannel?.id?.run {MessageBuilder("<#$this>")} ?: MessageBuilder("none")
                        "welcome" -> joinedGuilds[sourceMessage.guild]!!.welcomeChannel?.id?.run {MessageBuilder("<#$this>")} ?: MessageBuilder("none")
                        else -> MessageBuilder("Invalid channel")
                    }.sendTo(sourceMessage.channel).complete()
                }
                else if(channelMode == "set")
                {
                    val channelCategory = tokenizer.next().tokenValue
                    if(!tokenizer.hasNext())
                        return
                    
                    val newChannel = tokenizer.remainingTextAsToken.tokenValue
                    when(channelCategory)
                    {
                        "rules" -> sourceMessage.guild.getTextChannelById(newChannel)?.run {joinedGuilds[sourceMessage.guild]!!.rulesChannel = if(newChannel == "none") null else this}
                        "welcomeMessage" -> sourceMessage.guild.getTextChannelById(newChannel)?.run {joinedGuilds[sourceMessage.guild]!!.welcomeMessageChannel = if(newChannel == "none") null else this}
                        "userLeft" -> sourceMessage.guild.getTextChannelById(newChannel)?.run {joinedGuilds[sourceMessage.guild]!!.userLeaveChannel = if(newChannel == "none") null else this}
                        "userBanned" -> sourceMessage.guild.getTextChannelById(newChannel)?.run {joinedGuilds[sourceMessage.guild]!!.userBannedChannel = if(newChannel == "none") null else this}
                        "botLog" -> sourceMessage.guild.getTextChannelById(newChannel)?.run {joinedGuilds[sourceMessage.guild]!!.botLogChannel = if(newChannel == "none") null else this}
                        "music" -> sourceMessage.guild.getTextChannelById(newChannel)?.run {joinedGuilds[sourceMessage.guild]!!.musicChannel = if(newChannel == "none") null else this}
                        "welcome" -> sourceMessage.guild.getTextChannelById(newChannel)?.run {joinedGuilds[sourceMessage.guild]!!.welcomeChannel = if(newChannel == "none") null else this}
                        else -> null
                    } ?: MessageBuilder("Invalid channel").sendTo(sourceMessage.channel).complete()
                    save()
                }
            }
        }
        "admin" -> {
            if(isServerAdmin(sourceMessage.member) && tokenizer.hasNext())
            {
                val adminMode = tokenizer.next().tokenValue
                when(adminMode)
                {
                    "list" -> {
                        if(joinedGuilds[sourceMessage.guild]!!.serverAdminRoles.isNotEmpty())
                            MessageBuilder(joinedGuilds[sourceMessage.guild]!!.serverAdminRoles.joinToString(" ") {it.asMention}).sendTo(sourceMessage.channel).complete()
                        else
                            MessageBuilder("No roles are registered as a bot admin").sendTo(sourceMessage.channel).complete()
                    }
                    "add" -> {
                        if(tokenizer.hasNext())
                        {
                            joinedGuilds[sourceMessage.guild]!!.serverAdminRoles.addAll(tokenizer.asSequence().filter {it.tokenType == TokenType.ROLE}.map {sourceMessage.guild.getRoleById(it.tokenValue)})
                            save()
                        }
                    }
                    "remove" -> {
                        if(joinedGuilds[sourceMessage.guild]!!.serverAdminRoles.removeAll(tokenizer.asSequence().filter {it.tokenType == TokenType.ROLE}.map {sourceMessage.guild.getRoleById(it.tokenValue)}))
                            save()
                    }
                }
            }
        }
        "help" -> {
            if(!tokenizer.hasNext())
            {
                if(isServerAdmin(sourceMessage.member))
                    MessageBuilder("Known commands: `help, whoami, saved, play, restart, queue, pause, stop, skip, volume, say, initialRole, channel, admin`").sendTo(sourceMessage.channel).complete()
                else
                    MessageBuilder("Known commands: `help, whoami, saved, play, restart, queue, pause, stop, skip, volume`").sendTo(sourceMessage.channel).complete()
            }
            else
            {
                val commandToQuery = tokenizer.remainingTextAsToken.tokenValue
                when(commandToQuery)
                {
                    "whoami" -> MessageBuilder("```whoami \n\tTells you who you are```")
                    "saved" -> MessageBuilder("```saved (set|get|remove|list|play) [key] [text] \n\tSets the saved key to the given text, gets what's saved with the key, removes what's saved with the key, lists all saved keys and associated text, or plays what's saved with the key```")
                    "play" -> MessageBuilder("```play [URL] \n\tPlays the song at the url or unpauses the player if not url is given```")
                    "restart" -> MessageBuilder("```restart \n\tRestarts the currently playing song```")
                    "queue" -> MessageBuilder("```queue [duration] \n\tLists the currently queued songs or the duration of the songs in the queue```")
                    "pause" -> MessageBuilder("```pause \n\tPauses the player```")
                    "stop" -> MessageBuilder("```stop \n\tStops playing music and clears the queue")
                    "skip" -> MessageBuilder("```skip (index|range)* \n\tSkips the currently playing song or removes the songs at the indices and ranges which can be obtained from the queue command. If -1 is used as the index, then the last added song is removed```")
                    "volume" -> MessageBuilder("```volume [0-200] \n\tDisplays or sets the volume of the bot```")
                    "say" -> MessageBuilder("```say MESSAGE [!tts] \n\tMakes the bot say MESSAGE (with tts if the message ends with !tts) \n\tRequires admin```")
                    "initialRole" -> MessageBuilder("```initialRole [role] \n\tSets the role that a user gets upon joining a guild once they react to the welcome message in the welcome channel```")
                    "channel" -> MessageBuilder("```channel (get|set) (rules|welcomeMessage|userLeft|userBanned|botLog|music|welcome) (NEW_CHANNEL|none) \n\tGets or sets the channel used by the bot for the given purpose```")
                    "admin" -> MessageBuilder("```admin (list|add|remove) ROLE* \n\tLists, adds, or removes the roles that have bot admin rights```")
                    "help" -> MessageBuilder("```help \n\tDisplays the known commands```")
                    else -> MessageBuilder("```Unknown command: $commandToQuery```")
                }.sendTo(sourceMessage.channel).complete()
            }
        }
    }
}

private fun splitAt2000(text: String): List<String>
{
    if(text.length <= 2000)
        return listOf(text)
    val splitIndex = text.lastIndexOf('\n', 2000)
    return if(splitIndex < 0)
        listOf(text.substring(0, 2000)) + splitAt2000(text.substring(2000))
    else
        listOf(text.substring(0, splitIndex)) + splitAt2000(text.substring(splitIndex))
}

fun loadAndPlay(sourceMessage: Message, trackUrl: String?)
{
    val musicManager = joinedGuilds[sourceMessage.guild]!!.musicManager
    val musicChannel = joinedGuilds[sourceMessage.guild]!!.musicChannel
    
    if(sourceMessage.member.voiceState.channel == null)
    {
        sourceMessage.channel.sendMessage("You need to be in a voice channel to play music").complete()
        return
    }
    
    if(trackUrl != null)
    {
        @Suppress("NAME_SHADOWING")
        var trackUrl = trackUrl
        if(!urlRegex.matches(trackUrl))
        {
            val searchRequest = youtube.search().list("snippet")
            searchRequest.maxResults = 10
            searchRequest.q = trackUrl
            searchRequest.type = "video"
            val response = searchRequest.execute()
            trackUrl = "$youtubeBaseUrl${response.items[0].id.videoId}"
        }
        playerManager.loadItemOrdered(musicManager, trackUrl, object : AudioLoadResultHandler
        {
            /**
             * Called when loading an item failed with an exception.
             * @param exception The exception that was thrown
             */
            override fun loadFailed(exception: FriendlyException)
            {
                musicChannel?.sendMessage("Could not play song from url: $trackUrl")?.queue()
            }
        
            /**
             * Called when the requested item is a track and it was successfully loaded.
             * @param track The loaded track
             */
            override fun trackLoaded(track: AudioTrack)
            {
                musicChannel?.sendMessage("Adding to queue: ${track.info.title}")?.queue()
                play(sourceMessage.member.voiceState.channel, musicManager, track)
            }
        
            /**
             * Called when there were no items found by the specified identifier.
             */
            override fun noMatches()
            {
                musicChannel?.sendMessage("Could not find anything for url: $trackUrl")?.queue()
            }
        
            /**
             * Called when the requested item is a playlist and it was successfully loaded.
             * @param playlist The loaded playlist
             */
            override fun playlistLoaded(playlist: AudioPlaylist)
            {
                if(playlist.tracks.isEmpty())
                {
                    musicChannel?.sendMessage("Playlist is empty")?.queue()
                }
                else if(playlist.selectedTrack != null)
                {
                    trackLoaded(playlist.selectedTrack)
                }
                else
                {
                    musicChannel?.sendMessage("Adding to queue ${playlist.name}")?.queue()
                    playlist.tracks.forEach {play(sourceMessage.member.voiceState.channel, musicManager, it)}
                }
            }
        })
    }
    else
    {
        musicManager.scheduler.resume()
    }
}

private fun sanitize(text: String) = if(urlRegex.matches(text)) "<$text>" else text

fun play(voiceChannel: VoiceChannel, musicManager: GuildMusicManager, track: AudioTrack)
{
    voiceChannel.guild.audioManager.openAudioConnection(voiceChannel)
    
    musicManager.scheduler.queue(track)
}

fun stopMusic(guild: Guild)
{
    joinedGuilds[guild]!!.musicManager.scheduler.stop()
    joinedGuilds[guild]!!.musicChannel?.sendMessage("Stopping music")?.queue()
}

fun skipTrack(guild: Guild, trackIndex: Int, suppressMessage: Boolean = false)
{
    if(!suppressMessage)
        joinedGuilds[guild]!!.musicChannel?.sendMessage("Skipping track(s)")?.queue()
    
    val musicManager = joinedGuilds[guild]!!.musicManager
    if(trackIndex == -1)
        musicManager.scheduler.nextTrack()
    else
        musicManager.scheduler.remove(trackIndex)
}