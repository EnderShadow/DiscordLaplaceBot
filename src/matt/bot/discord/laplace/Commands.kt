package matt.bot.discord.laplace

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.*

val urlRegex = Regex("^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]")
val pornhubUrlRegex = Regex("https?://www.pornhub.com/view_video.php\\?(.+&)*viewkey=[a-z0-9]+(&.+)*(#.*)?")

fun runCommand(command: String, tokenizer: Tokenizer, sourceMessage: Message)
{
    if(!sourceMessage.channelType.isGuild)
        Command[command].takeIf {it.allowedInPrivateChannel}?.invoke(tokenizer, sourceMessage)
    else if(command !in joinedGuilds[sourceMessage.guild]!!.disabledCommands)
        Command[command](tokenizer, sourceMessage)
}

@Suppress("unused")
sealed class Command(val prefix: String, val requiresAdmin: Boolean = false, val allowedInPrivateChannel: Boolean = false)
{
    companion object
    {
        private val commands = mutableMapOf<String, Command>()
        private val noopCommand: Command
        
        init
        {
            Command::class.sealedSubclasses.asSequence().map {it.constructors.first().call()}.forEach {commands[it.prefix] = it}
            noopCommand = commands.remove("noop")!!
        }
        
        operator fun get(prefix: String) = commands.getOrDefault(prefix, noopCommand)
    }
    
    abstract fun helpMessage(): String
    abstract operator fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
    
    class NoopCommand: Command("noop", allowedInPrivateChannel = true)
    {
        override fun helpMessage() = ""
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) {}
    }
    
    class WhoAmI: Command("whoami", allowedInPrivateChannel = true)
    {
        override fun helpMessage() = """`l!whoami` __Tells you who you are.__
            |
            |**Usage:** l!whoami
            |
            |**Examples:**
            |`l!whoami` Who are you?
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(sourceMessage.channelType.isGuild)
                sourceMessage.channel.sendMessage("You are ${sourceMessage.member.effectiveName}").queue()
            else
                sourceMessage.channel.sendMessage("You are ${sourceMessage.author.name}").queue()
        }
    }
    
    class Saved: Command("saved", allowedInPrivateChannel = true)
    {
        override fun helpMessage() = """`l!saved` __Saves text so you can recall it at a later time or saves music URLs so you can play them later__
            |
            |**Usage:** l!saved set [key] [text or url]
            |              l!saved get [key]
            |              l!saved remove [key]
            |              l!saved play [key]
            |              l!saved list
            |
            |**Examples:**
            |`l!saved set song http://www.example.com/song.mp3` saves the url with the key 'song'
            |`l!saved get song` tells you what you saved with the key 'song'
            |`l!saved play song` plays what you saved with the key 'song'
            |`l!saved list` lists everything you've saved
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
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
                    sourceMessage.channel.sendMessage("Text saved successfully").queue()
                    save()
                }
                "get" -> {
                    if(tokenizer.hasNext())
                    {
                        val key = tokenizer.remainingTextAsToken.tokenValue
                        savedUserText.getOrDefault(sourceMessage.author.id, null)?.getOrDefault(key, null)?.let {sourceMessage.channel.sendMessage(it).queue()} ?: sourceMessage.channel.sendMessage("Could not find any text saved under: $key").queue()
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
                        sourceMessage.channel.sendMessage("You don't have any text saved").queue()
                    else
                    {
                        val text = splitAt2000("Here is what you have saved\n\n${textMap.entries.joinToString("\n") {"${it.key}: ${sanitize(it.value)}"}}")
                        text.forEach {sourceMessage.channel.sendMessage(it).queue()}
                    }
                }
                "play" -> {
                    if(!tokenizer.hasNext())
                        return
                    
                    if(!sourceMessage.channelType.isGuild)
                    {
                        sourceMessage.channel.sendMessage("Cannot play music in DMs").queue()
                        return
                    }
                    
                    val key = tokenizer.remainingTextAsToken.tokenValue
                    val url = savedUserText.getOrDefault(sourceMessage.author.id, null)?.getOrDefault(key, null)
                    if(url == null)
                        sourceMessage.channel.sendMessage("Could not find any text saved under: $key").queue()
                    else
                        loadAndPlay(sourceMessage, url)
                }
            }
        }
    }
    
    class Play: Command("play")
    {
        override fun helpMessage() = """`l!play` __Plays the song at the provided URL or the first song found on youtube using the provided text in a search. If no song is provided, it unpauses the music.__
            |
            |**Usage:** l!play
            |              l!play [url]
            |              l!play [search text]
            |
            |**Examples:**
            |`l!play` unpauses the music if it's paused
            |`l!play https://www.youtube.com/watch?v=dQw4w9WgXcQ` plays the song at that url
            |`l!play rick roll` plays the first song found by searching 'rick roll' on youtube
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) = loadAndPlay(sourceMessage, if(tokenizer.hasNext()) tokenizer.remainingTextAsToken.tokenValue else null)
    }
    
    class Restart: Command("restart")
    {
        override fun helpMessage() = """`l!restart` __Restarts the currently playing song__
            |
            |**Usage:** l!restart
            |
            |**Examples:**
            |`l!restart` restarts the currently playing song
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) = joinedGuilds[sourceMessage.guild]!!.musicManager.scheduler.restartSong()
    }
    
    class Queue: Command("queue")
    {
        override fun helpMessage() = """`l!queue` __Displays the songs in the queue__
            |
            |**Usage:** l!queue
            |              l!queue time
            |              l!queue length
            |              l!queue link [song index]
            |
            |**Examples:**
            |`l!queue` lists all the songs and their index in the queue
            |`l!queue time` displays the remaining time left in the queue
            |`l!queue length` displays how many songs are in the queue. This does not include the currently playing song
            |`l!queue link 1` displays the url for the next song
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            val musicManager = joinedGuilds[sourceMessage.guild]!!.musicManager
            if(tokenizer.hasNext() && tokenizer.remainingTextAsToken.tokenValue.equals("time", true))
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
                else if(minutes > 1)
                    stringBuilder.append("$minutes minutes ")
                if(stringBuilder.isNotEmpty())
                    stringBuilder.append("and ")
                if(seconds == 1)
                    stringBuilder.append("1 second")
                else
                    stringBuilder.append("$seconds seconds")
                sourceMessage.channel.sendMessage("The queue is $stringBuilder long").queue()
            }
            else if(tokenizer.hasNext() && tokenizer.remainingTextAsToken.tokenValue.equals("length", true))
            {
                sourceMessage.channel.sendMessage("The queue contains ${musicManager.scheduler.numSongs()} songs.").queue()
            }
            else if(tokenizer.hasNext() && tokenizer.remainingTextAsToken.tokenValue.startsWith("link"))
            {
                tokenizer.next()
                val index = if(tokenizer.hasNext()) tokenizer.next().tokenValue.toIntOrNull() ?: 0 else 0
                if(index >= 0 && index <= musicManager.scheduler.numSongs())
                {
                    val song = if(index == 0)
                        musicManager.player.playingTrack
                    else
                        musicManager.scheduler.get(index - 1)
                    sourceMessage.channel.sendMessage("The URL for ${song.info.title} is <${song.info.uri}>").queue()
                }
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
                    sourceMessage.channel.sendMessage("There are no queued songs.").queue()
            }
        }
    }
    
    class Pause: Command("pause")
    {
        override fun helpMessage() = """`l!pause` __Pauses the music that's playing if any music is playing__
            |
            |**Usage:** l!pause
            |
            |**Examples:**
            |`l!pause` pauses the currently playing music if any music is playing
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) = joinedGuilds[sourceMessage.guild]!!.musicManager.scheduler.pause()
    }
    
    class Stop: Command("stop")
    {
        override fun helpMessage() = """`l!stop` __Stops playing music and clears the queue__
            |
            |**Usage:** l!stop
            |
            |**Examples:**
            |`l!stop` stops playing music and clears the queue
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) = stopMusic(sourceMessage.guild)
    }
    
    class Skip: Command("skip")
    {
        override fun helpMessage() = """`l!skip` __skips the currently playing song or the songs at the provided indices and ranges__
            |
            |**Usage:** l!skip
            |              l!skip [index or range] ...
            |
            |**Examples:**
            |`l!skip` skips the currently playing song
            |`l!skip 3` skips the song at index 3 in the queue
            |`l!skip -1` removes the last song from the queue
            |`l!skip 1-3` skips the 3 songs after the currently playing song
            |`l!skip 1-3 5-9` skips the 9 songs after the currently playing song except for the song at index 4
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
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
    }
    
    class Volume: Command("volume")
    {
        override fun helpMessage() = """`l!volume` __Displays the volume of the music or sets it to the provided value__
            |
            |**Usage:** l!volume
            |              l!volume [level]
            |
            |**Examples:**
            |`l!volume` displays volume of the music
            |`l!volume 100` sets the volume to 100 percent
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            val guildInfo = joinedGuilds[sourceMessage.guild]!!
            if(tokenizer.hasNext())
            {
                val volume = tokenizer.remainingTextAsToken.tokenValue.toIntOrNull()?.coerceIn(0, 200)
                if(volume != null)
                {
                    val player = guildInfo.musicManager.player
                    guildInfo.volume = volume
                    if(player.playingTrack != null)
                        player.volume = (volume * guildInfo.volumeMultipliers.getOrDefault(player.playingTrack.info.uri, 1.0)).toInt()
                }
            }
            else
            {
                sourceMessage.channel.sendMessage("Volume is currently set to ${guildInfo.volume}").queue()
            }
        }
    }
    
    class LinkVolume: Command("volumeMultiplier")
    {
        override fun helpMessage() = """`l!volumeMultiplier` __Displays the volume multiplier for the currently playing song or sets it__
            |
            |**Usage:** l!volumeMultiplier
            |              l!volumeMultiplier [multiplier]
            |
            |**Examples:**
            |`l!volumeMultiplier` displays volume multiplier for this link
            |`l!volumeMultiplier 1.5` sets the volume multiplier to 1.5 for this link
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            val guildInfo = joinedGuilds[sourceMessage.guild]!!
            val player = guildInfo.musicManager.player
            if(player.playingTrack == null)
            {
                sourceMessage.channel.sendMessage("A song must be playing to get or set the multiplier")
            }
            else if(tokenizer.hasNext())
            {
                val mult = tokenizer.remainingTextAsToken.tokenValue.toDoubleOrNull()?.coerceIn(0.0, 4.0)
                if(mult != null)
                {
                    guildInfo.volumeMultipliers[player.playingTrack.info.uri] = mult
                    player.volume = (guildInfo.volume * mult).toInt()
                }
            }
            else
            {
                sourceMessage.channel.sendMessage("Volume multiplier is currently set to ${guildInfo.volumeMultipliers.getOrDefault(player.playingTrack.info.uri, 1.0)}").queue()
            }
        }
    }
    
    class RolesWithID: Command("listRolesWithIds", true)
    {
        override fun helpMessage() = """`l!listRolesWithIds` __Makes the bot list all roles with their id__
            |
            |**Usage:** l!listRolesWithIds
            |
            |**Examples:**
            |`l!listRolesWithIds` makes the bot list all roles with their id
        """.trimIndent()
    
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(isServerAdmin(sourceMessage.member))
            {
                val roles = sourceMessage.guild.roles.joinToString("\n") {"`${it.name.padEnd(40, ' ')} ${it.id}`"}
                splitAt2000(roles).forEach {sourceMessage.textChannel.sendMessage(it).queue()}
            }
        }
    }
    
    class Say: Command("say", true, true)
    {
        override fun helpMessage() = """`l!say` __Makes the bot say something__
            |
            |**Usage:** l!say [text]
            |              l!say [text] !tts
            |
            |**Examples:**
            |`l!say hello world` makes the bot say 'hello world'
            |`l!say hello world !tts` makes the bot say 'hello world' with tts
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(!sourceMessage.channelType.isGuild)
            {
                var content = tokenizer.remainingTextAsToken.tokenValue
                val tts = content.endsWith("!tts")
                if(tts)
                    content = content.substring(0, content.length - 4).trim()
                if(content.isNotEmpty())
                {
                    sourceMessage.channel.sendMessage(content).tts(tts).queue()
                    println("${sourceMessage.author.name} made me say \"$content\" in a DM")
                }
                else
                {
                    sourceMessage.channel.sendMessage("I can't say blank messages").queue()
                }
            }
            else if(isServerAdmin(sourceMessage.member))
            {
                var content = tokenizer.remainingTextAsToken.tokenValue
                val tts = content.endsWith("!tts")
                if(tts)
                    content = content.substring(0, content.length - 4).trim()
                if(content.isNotEmpty())
                {
                    sourceMessage.channel.sendMessage(content).tts(tts).queue()
                    joinedGuilds[sourceMessage.guild]!!.messageBuffer.remove(sourceMessage)
                    sourceMessage.delete().queue()
                    println("${sourceMessage.author.name} made me say \"$content\"")
                }
                else
                {
                    sourceMessage.channel.sendMessage("I can't say blank messages").queue()
                }
            }
        }
    }
    
    class InitialRole: Command("initialRole", true)
    {
        override fun helpMessage() = """`l!initialRole` __Gets or sets the initial role for members of the server__
            |
            |**Usage:** l!initialRole
            |              l!initialRole [role]
            |              l!initialRole none
            |
            |**Examples:**
            |`l!initialRole` gets the initial role for the server
            |`l!initialRole @member` sets the initial role for the server to the @member role
            |`l!initialRole none` sets the initial role for the server to no role
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(isServerAdmin(sourceMessage.member))
            {
                if(tokenizer.hasNext())
                {
                    joinedGuilds[sourceMessage.guild]!!.initialRole = if(tokenizer.remainingTextAsToken.tokenValue == "none")
                        null
                    else
                        sourceMessage.guild.getRoleById(tokenizer.remainingTextAsToken.tokenValue)
                    save()
                }
                else
                {
                    sourceMessage.channel.sendMessage("The current initial role is @${joinedGuilds[sourceMessage.guild]!!.initialRole?.name ?: "none"}").queue()
                }
            }
        }
    }
    
    class Channel: Command("channel", true)
    {
        override fun helpMessage() = """`l!channel` __Manages the channels used by the bot__
            |
            |**Available Channels:**
            |`rules` the channel where the server rules are located
            |`welcomeMessage` the channel where the bot should welcome the user with a message
            |`userLeft` the channel where the bot should send a message when a user leaves or is kicked
            |`userBanned` the channel where the bot should send a message when a user is banned
            |`botLog` the channel where the bot should put any information that the moderators or admins might want to know
            |`music` the text channel for music related messages
            |`welcome` the channel used making sure that a user isn't a bot and agrees to the server rules before getting an initial role
            |
            |Any channel can be set to `none` to disable it
            |
            |**Usage:** l!channel set [channel type] [channel]
            |              l!channel get [channel type]
            |              l!channel list
            |
            |**Examples:**
            |`l!channel set rules #rules` sets the rules channel to #rules
            |`l!channel get rules` displays the channel that has the server rules
            |`l!channel list` displays all the available channels as well as the current channel they're set to
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(isServerAdmin(sourceMessage.member) && tokenizer.hasNext())
            {
                val channelMode = tokenizer.next().tokenValue
                
                if(channelMode == "list")
                {
                    val guildInfo = joinedGuilds[sourceMessage.guild]!!
                    val message = """`rules            .`${guildInfo.rulesChannel?.id?.run {"<#$this>"} ?: "none"}
                        |`welcomeMessage   .`${guildInfo.welcomeMessageChannel?.id?.run {"<#$this>"} ?: "none"}
                        |`userLeft         .`${guildInfo.userLeaveChannel?.id?.run {"<#$this>"} ?: "none"}
                        |`userBanned       .`${guildInfo.userBannedChannel?.id?.run {"<#$this>"} ?: "none"}
                        |`botLog           .`${guildInfo.botLogChannel?.id?.run {"<#$this>"} ?: "none"}
                        |`music            .`${guildInfo.musicChannel?.id?.run {"<#$this>"} ?: "none"}
                        |`welcome          .`${guildInfo.welcomeChannel?.id?.run {"<#$this>"} ?: "none"}
                    """.trimMargin()
                    sourceMessage.channel.sendMessage(message).queue()
                    return
                }
                
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
                    }.sendTo(sourceMessage.channel).queue()
                }
                else if(channelMode == "set")
                {
                    val channelCategory = tokenizer.next().tokenValue
                    if(!tokenizer.hasNext())
                        return
                    
                    fun getTextChannelById(id: String): TextChannel?
                    {
                        if(id == "none")
                            return null
                        return sourceMessage.guild.getTextChannelById(id)
                    }
                    
                    val newChannel = tokenizer.remainingTextAsToken.tokenValue
                    when(channelCategory)
                    {
                        "rules" -> joinedGuilds[sourceMessage.guild]!!.rulesChannel = getTextChannelById(newChannel)
                        "welcomeMessage" -> joinedGuilds[sourceMessage.guild]!!.welcomeMessageChannel = getTextChannelById(newChannel)
                        "userLeft" -> joinedGuilds[sourceMessage.guild]!!.userLeaveChannel = getTextChannelById(newChannel)
                        "userBanned" -> joinedGuilds[sourceMessage.guild]!!.userBannedChannel = getTextChannelById(newChannel)
                        "botLog" -> joinedGuilds[sourceMessage.guild]!!.botLogChannel = getTextChannelById(newChannel)
                        "music" -> joinedGuilds[sourceMessage.guild]!!.musicChannel = getTextChannelById(newChannel)
                        "welcome" -> joinedGuilds[sourceMessage.guild]!!.welcomeChannel = getTextChannelById(newChannel)
                        else -> null
                    } ?: MessageBuilder("Invalid channel").sendTo(sourceMessage.channel).queue()
                    save()
                }
            }
        }
    }
    
    class Admin: Command("admin", true)
    {
        override fun helpMessage() = """`l!admin` __Used for managing the roles that can manage the bot__
            |
            |**Usage:** l!admin list
            |              l!admin add [role] ...
            |              l!admin remove [role] ...
            |
            |The server owner can always administrate the bot
            |
            |**Examples:**
            |`l!admin list` lists the roles that can currently manage the bot
            |`l!admin add @Admin @Moderator` adds the @Admin and @Moderator role to the list of roles that can administrate the bot
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(isServerAdmin(sourceMessage.member) && tokenizer.hasNext())
            {
                val adminMode = tokenizer.next().tokenValue
                when(adminMode)
                {
                    "list" -> {
                        if(joinedGuilds[sourceMessage.guild]!!.serverAdminRoles.isNotEmpty())
                            MessageBuilder(joinedGuilds[sourceMessage.guild]!!.serverAdminRoles.joinToString(" ") {it.asMention}).sendTo(sourceMessage.channel).queue()
                        else
                            MessageBuilder("No roles are registered as a bot admin").sendTo(sourceMessage.channel).queue()
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
    }
    
    class CommandControl: Command("command", true)
    {
        override fun helpMessage() = """`l!command` __Used for disabling and enabling commands for this server__
            |
            |**Usage:** l!command enable [command]
            |              l!command disable [command]
            |              l!command status [command]
            |
            |This command cannot be disabled. You can use `*` for the command to perform the action on all commands.
            |
            |**Examples:**
            |`l!command enable help` enables the help command if it's disabled
            |`l!command disable help` disables the help command if it's enabled
            |`l!command status help` tells you whether or not the help command is enabled or disabled
        """.trimMargin()
    
        @Suppress("NAME_SHADOWING")
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(isServerAdmin(sourceMessage.member) && tokenizer.hasNext())
            {
                val mode = tokenizer.next().tokenValue
                val command = tokenizer.remainingTextAsToken.tokenValue
                if(command != "*" && command !in commands)
                {
                    sourceMessage.channel.sendMessage("'$command' is not a valid command").queue()
                    return
                }
                if(command == prefix && mode == "disable")
                {
                    sourceMessage.channel.sendMessage("Cannot disable the command used for enabling and disabling other commands").queue()
                    return
                }
                val disabledCommands = joinedGuilds[sourceMessage.guild]!!.disabledCommands
                if(command == "*")
                {
                    when(mode)
                    {
                        "enable" -> disabledCommands.clear()
                        "disable" -> disabledCommands.addAll(commands.keys.filter {it != prefix})
                        "status" ->
                        {
                            sourceMessage.channel.sendMessage(commands.keys.joinToString("\n") {command ->
                                if(command in disabledCommands)
                                    "The `$command` command is currently disabled"
                                else
                                    "The `$command` command is currently enabled"
                            }).queue()
                        }
                    }
                }
                else
                {
                    when(mode)
                    {
                        "enable" -> disabledCommands.remove(command)
                        "disable" -> disabledCommands.add(command)
                        "status" ->
                        {
                            if(command in disabledCommands)
                                sourceMessage.channel.sendMessage("The $command command is currently disabled").queue()
                            else
                                sourceMessage.channel.sendMessage("The $command command is currently enabled").queue()
                        }
                    }
                }
            }
        }
    }
    
    class Block: Command("block", true)
    {
        override fun helpMessage() = """`l!block` __Used for preventing users from using the bot__
            |
            |**Usage:** l!block yes [user]
            |              l!block no [user]
            |
            |The server owner can always block or unblock users from using the bot
            |
            |**Examples:**
            |`l!block yes @JoeShmoe` prevents @JoeShmoe from using the bot
            |`l!block no @JoeShmoe` allows @JoeShmoe to use the bot if he was previously blocked
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(isServerAdmin(sourceMessage.member) && tokenizer.hasNext())
            {
                val blockMode = tokenizer.next().tokenValue
                if(tokenizer.hasNext())
                {
                    val user = tokenizer.remainingTextAsToken.objValue as User
                    if(sourceMessage.guild.getMember(user)?.isOwner == true)
                        return
                    
                    when(blockMode)
                    {
                        "yes" -> {
                            joinedGuilds[sourceMessage.guild]!!.blockedUsers.add(user)
                            sourceMessage.channel.sendMessage("${user.asMention} has been blocked from using me").queue()
                        }
                        "no" -> {
                            joinedGuilds[sourceMessage.guild]!!.blockedUsers.remove(user)
                            sourceMessage.channel.sendMessage("${user.asMention} has been unblocked from using me").queue()
                        }
                    }
                }
            }
        }
    }
    
    class FunStuff: Command("funStuff", true)
    {
        override fun helpMessage() = """`l!funStuff` __Used for enabling or disabling the fun stuff that the bot does__
            |
            |**Usage:** l!funStuff
            |              l!funStuff [boolean]
            |
            |The fun stuff currently consists of a "make me a sandwich" joke thing and an element thing where the bot responds with the element name when you type the symbol for it
            |
            |**Examples:**
            |`l!funStuff` tells you whether or not the fun stuff is enabled
            |`l!funStuff true` enables the fun stuff
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(isServerAdmin(sourceMessage.member))
            {
                if(tokenizer.hasNext())
                {
                    joinedGuilds[sourceMessage.guild]!!.funStuff = tokenizer.next().tokenValue.toBoolean()
                    save()
                    sourceMessage.channel.sendMessage("Fun stuff is now ${if(joinedGuilds[sourceMessage.guild]!!.funStuff) "enabled" else "disabled"}").queue()
                }
                else
                {
                    sourceMessage.channel.sendMessage("Fun stuff is currently ${if(joinedGuilds[sourceMessage.guild]!!.funStuff) "enabled" else "disabled"}").queue()
                }
            }
        }
    }
    
    class Logging: Command("logging", true)
    {
        override fun helpMessage() = """`l!logging` __Used for managing how the bot logs data__
            |
            |**Usage:** l!logging list
            |              l!logging set [key] [boolean]
            |
            |Note: Nothing will be logged if the botDev channel is set to none
            |
            |**Examples:**
            |`l!logging list` displays everything that can be logged
            |`l!logging set deletion false` disables logging of deleted messages
        """.trimMargin()
    
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(isServerAdmin(sourceMessage.member))
            {
                if(tokenizer.hasNext())
                {
                    val guildInfo = joinedGuilds[sourceMessage.guild]!!
                    val mode = tokenizer.next().tokenValue
                    if(mode == "list")
                    {
                        sourceMessage.channel.sendMessage("Deleted message logging: ${guildInfo.displayDeleted}\nEdited message logging: ${guildInfo.displayModified}").queue()
                    }
                    else if(mode == "set")
                    {
                        if(tokenizer.hasNext())
                        {
                            val key = tokenizer.next().tokenValue
                            if(tokenizer.hasNext())
                            {
                                val value = tokenizer.next().tokenValue.toBoolean()
                                if(key == "deleted")
                                {
                                    guildInfo.displayDeleted = value
                                    sourceMessage.channel.sendMessage("Deleted message logigng is now $value").queue()
                                }
                                else if(key == "edited")
                                {
                                    guildInfo.displayModified = value
                                    sourceMessage.channel.sendMessage("Edited message logigng is now $value").queue()
                                }
                                save()
                            }
                        }
                    }
                }
            }
        }
    }
    
    class Help: Command("help", allowedInPrivateChannel = true)
    {
        override fun helpMessage() = """`l!help` __Displays a list of commands. Provide a command to get its info__
            |
            |**Usage:** l!help [command]
            |
            |**Examples:**
            |`l!help` displays a list of all commands
            |`l!help whoami` displays the help info for the whoami command
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            val (adminCommands, normalCommands) = commands.values.splitAndMap(Command::requiresAdmin) {it.prefix}
            val message = if(!tokenizer.hasNext())
            {
                """```bash
                    |'command List'```
                    |
                    |Use `!help [command]` to get more info on a specific command, for example: `l!help whoami`
                    |
                    |**Standard Commands**
                    |${normalCommands.joinToString(" ") {"`$it`"}}
                    |
                    |**Admin Commands**
                    |${adminCommands.joinToString(" ") {"`$it`"}}
                """.trimMargin()
            }
            else
            {
                val command = tokenizer.next().tokenValue
                commands[command]?.helpMessage() ?: "Command '$command' was not found."
            }
            sourceMessage.channel.sendMessage(message).queue()
        }
    }
}

fun loadAndPlay(sourceMessage: Message, trackUrl: String?)
{
    val musicManager = joinedGuilds[sourceMessage.guild]!!.musicManager
    val musicChannel = joinedGuilds[sourceMessage.guild]!!.musicChannel
    
    if(sourceMessage.member.voiceState.channel == null)
    {
        sourceMessage.channel.sendMessage("You need to be in a voice channel to play music").queue()
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
            if(response.items.isNotEmpty())
            {
                trackUrl = "$youtubeBaseUrl${response.items[0].id.videoId}"
            }
            else
            {
                musicChannel?.sendMessage("Could not find anything for the search: $trackUrl")?.queue()
                return
            }
        }
        else if(pornhubUrlRegex.matches(trackUrl))
        {
            trackUrl = ProcessBuilder("youtube-dl", "-s", "-g", trackUrl).start().inputStream.readFullyToString().trim()
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
                @Suppress("CascadeIf")
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