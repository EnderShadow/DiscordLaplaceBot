package matt.bot.discord.laplace

import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import net.dv8tion.jda.core.AccountType
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.JDABuilder
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.ChannelType
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.User
import net.dv8tion.jda.core.events.*
import net.dv8tion.jda.core.events.guild.GuildBanEvent
import net.dv8tion.jda.core.events.guild.GuildJoinEvent
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent
import net.dv8tion.jda.core.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.core.events.guild.member.GuildMemberLeaveEvent
import net.dv8tion.jda.core.events.guild.member.GuildMemberRoleAddEvent
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.events.message.guild.GuildMessageDeleteEvent
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.core.events.message.guild.GuildMessageUpdateEvent
import net.dv8tion.jda.core.events.message.guild.react.GuildMessageReactionAddEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import java.io.File
import java.time.LocalDateTime

val jsonFactory: JacksonFactory = JacksonFactory.getDefaultInstance()
lateinit var bot: JDA
    private set

const val youtubeBaseUrl = "https://www.youtube.com/watch?v="
const val botPrefix = "l!"
val mentionSpammers = mutableMapOf<Member, Pair<Int, Long>>()

@Suppress("UNCHECKED_CAST")
val savedUserText: MutableMap<String, MutableMap<String, String>> = try {jsonFactory.fromString(File("savedUserText.json").readText(), LinkedHashMap::class.java) as MutableMap<String, MutableMap<String, String>>} catch(e: Exception) {mutableMapOf()}

val playerManager = DefaultAudioPlayerManager()
val joinedGuilds = mutableMapOf<Guild, GuildInfo>()

val youtube = getYoutubeService()

var shutdownMode = ExitMode.SHUTDOWN

fun getYoutubeService(): YouTube
{
    val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
    val secret = GoogleClientSecrets.load(jsonFactory, File("client_secret.json").reader())
    val flow = GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, secret, listOf(YouTubeScopes.YOUTUBE_READONLY)).setDataStoreFactory(FileDataStoreFactory(File("credentials/youtube_api"))).setAccessType("offline").build()
    val credential = AuthorizationCodeInstalledApp(flow, LocalServerReceiver()).authorize("user")
    return YouTube.Builder(httpTransport, jsonFactory, credential).setApplicationName("Laplace Discord Bot").build()
}

fun main(args: Array<String>)
{
    val token = File("token").readText()
    bot = JDABuilder(AccountType.BOT)
            .setToken(token)
            .addEventListener(UtilityListener(), MessageListener())
            .buildBlocking()
    
    while(true)
    {
        try
        {
            commandLine(bot)
        }
        catch(e: Exception)
        {
            e.printStackTrace()
        }
    }
}

fun save()
{
    val guildSaveData = joinedGuilds.map {(guild, guildInfo) ->
        "${guild.id}:${guildInfo.serverAdminRoles.joinToString(",") {it.id}}:${guildInfo.initialRole?.id}:${guildInfo.rulesChannel?.id},${guildInfo.welcomeMessageChannel?.id},${guildInfo.userLeaveChannel?.id},${guildInfo.userBannedChannel?.id},${guildInfo.botLogChannel?.id},${guildInfo.musicChannel?.id},${guildInfo.welcomeChannel?.id}"
    }.joinToString("\n")
    File("guildSaveData.txt").writeText(guildSaveData)
    File("savedUserText.json").writeText(jsonFactory.toPrettyString(savedUserText))
}

fun clearWelcomeReactionsBy(guild: Guild, user: User)
{
    joinedGuilds[guild]!!.welcomeChannel?.iterableHistory?.forEach {it.reactions.forEach {it.removeReaction(user).queue()}}
}

class UtilityListener: ListenerAdapter()
{
    override fun onReady(event: ReadyEvent)
    {
        event.jda.isAutoReconnect = true
        println("Logged in as ${event.jda.selfUser.name}\n${event.jda.selfUser.id}\n-----------------------------")
    
        // init audio system
        AudioSourceManagers.registerRemoteSources(playerManager)
        AudioSourceManagers.registerLocalSource(playerManager)
        
        // loads saved guild info
        event.jda.guilds.forEach {joinedGuilds.putIfAbsent(it, GuildInfo(it))}
        
        File("guildSaveData.txt").readLines().asSequence().map {it.split(":")}.filter {it[0] in joinedGuilds.keys.map {it.id}}.toList().forEach {
            val guildInfo = joinedGuilds[event.jda.getGuildById(it[0])]!!
            guildInfo.serverAdminRoles.addAll(it[1].split(",").asSequence().filter {it.isNotBlank()}.map {guildInfo.guild.getRoleById(it)}.filter {it != null}.toList())
            if(it[2] != "null")
                guildInfo.initialRole = guildInfo.guild.getRoleById(it[2])
            val messageChannels = it[3].split(",").map {if(it != "null") guildInfo.guild.getTextChannelById(it) else null}
            guildInfo.rulesChannel = messageChannels[0]
            guildInfo.welcomeMessageChannel = messageChannels[1]
            guildInfo.userLeaveChannel = messageChannels[2]
            guildInfo.userBannedChannel = messageChannels[3]
            guildInfo.botLogChannel = messageChannels[4]
            guildInfo.musicChannel = messageChannels[5]
            guildInfo.welcomeChannel = messageChannels[6]
        }
    }
    
    override fun onGuildJoin(event: GuildJoinEvent)
    {
        joinedGuilds[event.guild] = GuildInfo(event.guild)
    }
    
    override fun onGuildLeave(event: GuildLeaveEvent)
    {
        joinedGuilds.remove(event.guild)
    }
    
    override fun onDisconnect(event: DisconnectEvent)
    {
        println("[${LocalDateTime.now()}] Bot has disconnected. Attempting to reconnect")
    }
    
    override fun onReconnect(event: ReconnectedEvent)
    {
        println("[${LocalDateTime.now()}] Bot has reconnected")
    }
    
    override fun onShutdown(event: ShutdownEvent)
    {
        save()
        System.exit(shutdownMode.ordinal)
    }
    
    override fun onGuildMemberJoin(event: GuildMemberJoinEvent)
    {
        val guildInfo = joinedGuilds[event.guild]!!
        val sendToChannel = guildInfo.welcomeMessageChannel
        val rulesChannel = guildInfo.rulesChannel
        if(sendToChannel == null)
            return
        if(guildInfo.initialRole != null)
            return
        
        if(rulesChannel == null)
            MessageBuilder("Welcome ${event.member.asMention} to my dominion!").sendTo(sendToChannel).complete()
        else
            MessageBuilder("Welcome ${event.member.asMention} to my dominion! Make sure you read <#${rulesChannel.id}>").sendTo(sendToChannel).complete()
    }
    
    override fun onGuildMemberLeave(event: GuildMemberLeaveEvent)
    {
        val sendToChannel = joinedGuilds[event.guild]!!.userLeaveChannel
        val initialRole = joinedGuilds[event.guild]!!.initialRole
        if(sendToChannel != null && (initialRole == null || event.member.roles.contains(initialRole)))
            MessageBuilder("Goodbye ${event.user.name}.\nAnnouncer: ${event.user.name} deleted.").sendTo(sendToChannel).complete()
        
        clearWelcomeReactionsBy(event.guild, event.user)
    }
    
    override fun onGuildBan(event: GuildBanEvent)
    {
        val sendToChannel = joinedGuilds[event.guild]!!.userBannedChannel
        if(sendToChannel != null)
            MessageBuilder("${event.user.name} has been banned.").sendTo(sendToChannel).complete()
    
        clearWelcomeReactionsBy(event.guild, event.user)
    }
    
    override fun onGuildMessageDelete(event: GuildMessageDeleteEvent)
    {
        val guildInfo = joinedGuilds[event.guild]!!
        val messageBuffer = guildInfo.messageBuffer
        val messages = messageBuffer.asList().filter {it.id == event.messageId}
        if(messages.isEmpty())
            return
        
        //val lastDeletionEvent = event.guild.auditLogs.type(ActionType.MESSAGE_DELETE).complete().first()
        //val deleter = lastDeletionEvent.user
        //val deleteTime = lastDeletionEvent.creationTime.toLocalDateTime()
        val message = messages.first()
        
        if(message.contentRaw.isNotBlank())
            guildInfo.botLogChannel?.sendMessage("A message by ${message.author.asMention} in ${message.textChannel.asMention} was deleted. It's contents were:\n\n${message.contentRaw}")?.complete()
    }
    
    override fun onGuildMessageReceived(event: GuildMessageReceivedEvent)
    {
        if(!event.author.isBot)
            joinedGuilds[event.guild]!!.messageBuffer.add(event.message)
    }
    
    override fun onGuildMessageUpdate(event: GuildMessageUpdateEvent)
    {
        if(!event.author.isBot)
            joinedGuilds[event.guild]!!.messageBuffer.update(event.message) {msg1, msg2 -> msg1.id == msg2.id}
    }
    
    override fun onGuildMessageReactionAdd(event: GuildMessageReactionAddEvent)
    {
        if(event.channel.id == joinedGuilds[event.guild]!!.welcomeChannel?.id && event.reactionEmote.name == "✅")
        {
            val role = joinedGuilds[event.guild]!!.initialRole
            if(role != null)
                event.guild.controller.addSingleRoleToMember(event.member, role).complete()
        }
    }
    
    override fun onGuildMemberRoleAdd(event: GuildMemberRoleAddEvent)
    {
        val role = joinedGuilds[event.guild]!!.initialRole
        if(role in event.roles)
            joinedGuilds[event.guild]!!.welcomeMessageChannel?.sendMessage("Welcome ${event.member.asMention} to my dominion!")?.complete()
    }
}

class MessageListener: ListenerAdapter()
{
    override fun onMessageReceived(event: MessageReceivedEvent)
    {
        if(event.author.isBot)
            return
        
        // Checks for users that are spamming mentions
        if(countMentions(event.message) >= 5 || event.message.mentionsEveryone())
        {
            var (count, lastSpamTime) = mentionSpammers.getOrDefault(event.member, Pair(0, 0L))
            val timeDiff = System.currentTimeMillis() - lastSpamTime
            if(timeDiff < 10 * 1000)
                count += 1
            else
                count = 1
            
            // bans the user if they spammed mentions for a fifth (or more) time within the last 10 seconds
            if(count >= 5)
            {
                event.guild.controller.ban(event.member, 1, "Spamming mentions")
                mentionSpammers.remove(event.member)
            }
            else
            {
                // clears users from the list of users that spammed if it's been more than 10 seconds and then adds the user that just spammed to the list
                mentionSpammers.keys.toList().forEach {
                    if(System.currentTimeMillis() - mentionSpammers[it]!!.second > 10 * 1000)
                        mentionSpammers.remove(it)
                }
                mentionSpammers[event.member] = Pair(count, System.currentTimeMillis())
            }
        }
        
        val tokenizer = Tokenizer(event.message.contentRaw)
        if(!tokenizer.hasNext())
            return
        
        val firstToken = tokenizer.next()
        if(firstToken.tokenType == TokenType.COMMAND)
        {
            runCommand(firstToken.tokenValue, tokenizer, event.message)
            return
        }
        
        // Checks if the message is an element symbol from the periodic table of elements
        if(isElementSymbol(firstToken.tokenValue) && !tokenizer.hasNext())
        {
            MessageBuilder(getElementNameBySymbol(firstToken.tokenValue)).sendTo(event.channel).complete()
            return
        }
        
        if(event.message.isMentioned(event.jda.selfUser) || event.channelType == ChannelType.PRIVATE)
        {
            if(event.message.contentRaw.toLowerCase().matches(Regex(".*?sudo.+?make\\s+me\\s+a\\s+sandwich.*?")))
            {
                if(Math.random() < 0.8)
                    event.channel.sendMessage("Ok, here's a sandwich.\nhttps://i.imgur.com/dlmkz6v.jpg").complete()
                else
                    event.channel.sendMessage("${event.author.asMention} is not in the sudoers file. This incident will be reported.").complete()
            }
            else if(event.message.contentRaw.toLowerCase().matches(Regex(".*?sudo.+?make\\s+me\\s+a\\s+furry\\s+sandwich.*?")))
            {
                event.channel.sendMessage("https://i.imgur.com/CmVjtSV.jpg").complete()
            }
            else if(event.message.contentRaw.toLowerCase().matches(Regex(".*?make\\s+me\\s+a\\s+sandwich.*?")))
            {
                event.channel.sendMessage("Go make it yourself.").complete()
            }
        }
    }
}