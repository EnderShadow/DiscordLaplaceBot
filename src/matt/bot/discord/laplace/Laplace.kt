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
import net.dv8tion.jda.core.entities.*
import net.dv8tion.jda.core.events.DisconnectEvent
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.ReconnectedEvent
import net.dv8tion.jda.core.events.ShutdownEvent
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
import org.apache.commons.codec.digest.DigestUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
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

const val logIndent = "                                                         "

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
    File("logs").mkdir()
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
    val guildSaveData = JSONArray(joinedGuilds.values.map {guildInfo ->
        val guildJson = JSONObject()
        
        guildJson.put("id", guildInfo.guild.id)
        guildJson.put("adminRoles", JSONArray(guildInfo.serverAdminRoles.map {it.id}))
        guildJson.put("initialRole", guildInfo.initialRole?.id ?: JSONObject.NULL)
        guildJson.put("rulesChannel", guildInfo.rulesChannel?.id ?: JSONObject.NULL)
        guildJson.put("welcomeMessageChannel", guildInfo.welcomeMessageChannel?.id ?: JSONObject.NULL)
        guildJson.put("userLeaveChannel", guildInfo.userLeaveChannel?.id ?: JSONObject.NULL)
        guildJson.put("userBannedChannel", guildInfo.userBannedChannel?.id ?: JSONObject.NULL)
        guildJson.put("botLogChannel", guildInfo.botLogChannel?.id ?: JSONObject.NULL)
        guildJson.put("musicChannel", guildInfo.musicChannel?.id ?: JSONObject.NULL)
        guildJson.put("welcomeChannel", guildInfo.welcomeChannel?.id ?: JSONObject.NULL)
        guildJson.put("disabledCommands", JSONArray(guildInfo.disabledCommands))
        guildJson.put("blockedUsers", JSONArray(guildInfo.blockedUsers.map {it.id}))
        guildJson.put("volume", guildInfo.volume)
        guildJson.put("volumeMultipliers", JSONObject(guildInfo.volumeMultipliers))
        guildJson.put("funStuff", guildInfo.funStuff)
        guildJson.put("displayDeleted", guildInfo.displayDeleted)
        guildJson.put("displayModified", guildInfo.displayModified)
        
        guildJson
    })
    
    File("guildSaveData.json").writeText(guildSaveData.toString(4))
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
        
        val guildSavedData = JSONArray(File("guildSaveData.json").readText())
        guildSavedData.forEach {
            val guildData = it as JSONObject
            val guild = event.jda.getGuildById(guildData.getString("id"))
            if(guild != null)
            {
                val guildInfo = joinedGuilds[guild]!!
                guildInfo.serverAdminRoles.addAll(guildData.getJSONArray("adminRoles").mapNotNull {guildInfo.guild.getRoleById(it as String)})
                guildInfo.initialRole = guildData.getStringOrNull("initialRole")?.let {guildInfo.guild.getRoleById(it)}
                guildInfo.rulesChannel = guildData.getStringOrNull("rulesChannel")?.let {guildInfo.guild.getTextChannelById(it)}
                guildInfo.welcomeMessageChannel = guildData.getStringOrNull("welcomeMessageChannel")?.let {guildInfo.guild.getTextChannelById(it)}
                guildInfo.userLeaveChannel = guildData.getStringOrNull("userLeaveChannel")?.let {guildInfo.guild.getTextChannelById(it)}
                guildInfo.userBannedChannel = guildData.getStringOrNull("userBannedChannel")?.let {guildInfo.guild.getTextChannelById(it)}
                guildInfo.botLogChannel = guildData.getStringOrNull("botLogChannel")?.let {guildInfo.guild.getTextChannelById(it)}
                guildInfo.musicChannel = guildData.getStringOrNull("musicChannel")?.let {guildInfo.guild.getTextChannelById(it)}
                guildInfo.welcomeChannel = guildData.getStringOrNull("welcomeChannel")?.let {guildInfo.guild.getTextChannelById(it)}
                @Suppress("UNCHECKED_CAST")
                guildInfo.disabledCommands.addAll(guildData.getJSONArray("disabledCommands").toList() as List<String>)
                guildInfo.blockedUsers.addAll(guildData.getJSONArray("blockedUsers").map {event.jda.getUserById(it as String)})
                guildInfo.volume = guildData.getInt("volume")
                @Suppress("UNCHECKED_CAST")
                guildInfo.volumeMultipliers.putAll(guildData.getJSONObject("volumeMultipliers").toMap() as Map<String, Double>)
                guildInfo.funStuff = guildData.getBoolean("funStuff")
                guildInfo.displayDeleted = guildData.getBoolean("displayDeleted")
                guildInfo.displayModified = guildData.getBoolean("displayModified")
            }
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
            MessageBuilder("Welcome ${event.member.asMention} to my dominion!").sendTo(sendToChannel).queue()
        else
            MessageBuilder("Welcome ${event.member.asMention} to my dominion! Make sure you read <#${rulesChannel.id}>").sendTo(sendToChannel).queue()
    }
    
    override fun onGuildMemberLeave(event: GuildMemberLeaveEvent)
    {
        val sendToChannel = joinedGuilds[event.guild]!!.userLeaveChannel
        val initialRole = joinedGuilds[event.guild]!!.initialRole
        if(sendToChannel != null && (initialRole == null || event.member.roles.contains(initialRole)))
            MessageBuilder("Goodbye ${event.user.name}.\nAnnouncer: ${event.user.name} deleted.").sendTo(sendToChannel).queue()
        
        clearWelcomeReactionsBy(event.guild, event.user)
        
        // Remove invites created by bots that join and then leave.
        event.guild.invites.queue {
            it.forEach {invite ->
                if(invite.inviter?.name.isNullOrBlank())
                {
                    //println("Deleted a webhook invite to #${it.channel.name} when ${event.user.name} left")
                    invite.delete().queue()
                }
            }
        }
    }
    
    override fun onGuildBan(event: GuildBanEvent)
    {
        val sendToChannel = joinedGuilds[event.guild]!!.userBannedChannel
        if(sendToChannel != null)
            MessageBuilder("${event.user.name} has been banned.").sendTo(sendToChannel).queue()
    
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
        
        if(message.contentRaw.isNotBlank() && event.guild.banList.complete().none {it.user == message.author})
        {
            if(guildInfo.displayDeleted)
            {
                splitAt2000("A message by ${message.author.name} in ${message.textChannel.asMention} was deleted. It's contents were:\n\n${message.contentRaw}").forEach {
                    guildInfo.botLogChannel?.sendMessage(it)?.queue()
                }
            }
            
            synchronized(guildInfo)
            {
                val textLog = File("logs/${event.guild.id}.log")
                if(!textLog.exists())
                    textLog.writeText("${event.guild.name}\n")
                textLog.appendText("${message.creationTime.toLocalDateTime().toString().padEnd(28, ' ')}#${message.channel.name.padEnd(28, ' ')}${message.contentDisplay.lines().joinToString("\n$logIndent")}\n")
            }
        }
    }
    
    override fun onGuildMessageReceived(event: GuildMessageReceivedEvent)
    {
        if(!event.author.isBot)
            joinedGuilds[event.guild]!!.messageBuffer.add(event.message)
    }
    
    override fun onGuildMessageUpdate(event: GuildMessageUpdateEvent)
    {
        val guildInfo = joinedGuilds[event.guild]!!
        val messageBuffer = guildInfo.messageBuffer
        if(!event.author.isBot && event.message in messageBuffer)
        {
            val oldMessage = messageBuffer.update(event.message) {msg1, msg2 -> msg1.id == msg2.id}
            if(guildInfo.displayModified)
            {
                splitAt2000("A message by ${event.author.name} in ${event.message.textChannel.asMention} was Edited.\nPrevious: ${oldMessage.contentRaw}\nCurrent: ${event.message.contentRaw}").forEach {
                    guildInfo.botLogChannel?.sendMessage(it)?.queue()
                }
            }
        }
    }
    
    override fun onGuildMessageReactionAdd(event: GuildMessageReactionAddEvent)
    {
        if(event.channel.id == joinedGuilds[event.guild]!!.welcomeChannel?.id && event.reactionEmote.name == "✅")
        {
            val role = joinedGuilds[event.guild]!!.initialRole
            if(role != null)
                event.guild.controller.addSingleRoleToMember(event.member, role).queue()
        }
    }
    
    override fun onGuildMemberRoleAdd(event: GuildMemberRoleAddEvent)
    {
        val role = joinedGuilds[event.guild]!!.initialRole
        if(role in event.roles)
            joinedGuilds[event.guild]!!.welcomeMessageChannel?.sendMessage("Welcome ${event.member.asMention} to my dominion!")?.queue()
    }
}

class MessageListener: ListenerAdapter()
{
    override fun onMessageReceived(event: MessageReceivedEvent)
    {
        if(event.author.isBot)
            return
        
        // Spam checking
        checkForSpam(event)
        
        if(event.author in joinedGuilds[event.guild]!!.blockedUsers)
            return
        
        val tokenizer = Tokenizer(event.message.contentRaw)
        if(!tokenizer.hasNext())
            return
        
        val firstToken = tokenizer.next()
        if(firstToken.tokenType == TokenType.COMMAND)
        {
            runCommand(firstToken.tokenValue, tokenizer, event.message)
            return
        }
        
        if(!joinedGuilds[event.guild]!!.funStuff)
            return
        
        // Checks if the message is an element symbol from the periodic table of elements
        if(isElementSymbol(firstToken.tokenValue) && !tokenizer.hasNext())
        {
            MessageBuilder(getElementNameBySymbol(firstToken.tokenValue)).sendTo(event.channel).queue()
            return
        }
        
        if(event.message.isMentioned(event.jda.selfUser) || event.channelType == ChannelType.PRIVATE)
        {
            if(event.message.contentRaw.toLowerCase().matches(Regex(".*?sudo.+?make\\s+me\\s+a\\s+sandwich.*?")))
            {
                if(Math.random() < 0.8)
                    event.channel.sendMessage("Ok, here's a sandwich.\nhttps://i.imgur.com/dlmkz6v.jpg").queue()
                else
                    event.channel.sendMessage("${event.author.asMention} is not in the sudoers file. This incident will be reported.").queue()
            }
            else if(event.message.contentRaw.toLowerCase().matches(Regex(".*?sudo.+?make\\s+me\\s+a\\s+furry\\s+sandwich.*?")))
            {
                event.channel.sendMessage("https://i.imgur.com/CmVjtSV.jpg").queue()
            }
            else if(event.message.contentRaw.toLowerCase().matches(Regex(".*?make\\s+me\\s+a\\s+sandwich.*?")))
            {
                event.channel.sendMessage("Go make it yourself.").queue()
            }
        }
    }
}

//                                        hash     count  lastMessageTimeStamp
val spamMap = mutableMapOf<Member, Triple<ByteArray, Int, Long>>()

fun checkForSpam(event: MessageReceivedEvent)
{
    if(event.channelType == ChannelType.PRIVATE || event.channelType == ChannelType.GROUP || event.isWebhookMessage)
        return
    
    // Checks for users that are spamming mentions
    if(countMentions(event.message) >= 5 || event.message.mentionsEveryone())
    {
        var (count, lastSpamTime) = mentionSpammers.getOrDefault(event.member, Pair(0, 0L))
        val timeDiff = System.currentTimeMillis() - lastSpamTime
        if(timeDiff < 10_000)
            count += 1
        else
            count = 1
        
        // bans the user if they spammed mentions for a fifth (or more) time within the last 10 seconds
        if(count >= 5)
        {
            takeActionAgainstUser(event.member, true, "Spamming mentions")
            mentionSpammers.remove(event.member)
        }
        else
        {
            // clears users from the list of users that spammed if it's been more than 10 seconds and then adds the user that just spammed to the list
            mentionSpammers.entries.toList().forEach {(key, value) ->
                if(System.currentTimeMillis() - value.second > 10_000)
                    mentionSpammers.remove(key)
            }
            mentionSpammers[event.member] = Pair(count, System.currentTimeMillis())
        }
    }
    
    // Checks for users that are spamming the same stuff over and over again
    val messageInfo = spamMap[event.member]
    if(messageInfo == null)
    {
        spamMap[event.member] = Triple(hashMessage(event.message), 1, System.currentTimeMillis())
    }
    else
    {
        val messageHash = hashMessage(event.message)
        if(messageInfo.first.contentEquals(messageHash) && System.currentTimeMillis() - messageInfo.third < 5_000)
        {
            if(messageInfo.second == 9)
            {
                // This message makes it the 10th
                takeActionAgainstUser(event.member, true, "Spamming")
                spamMap.remove(event.member)
            }
            else
            {
                spamMap[event.member] = Triple(messageHash, messageInfo.second + 1, System.currentTimeMillis())
            }
        }
        else
        {
            spamMap[event.member] = Triple(hashMessage(event.message), 1, System.currentTimeMillis())
            spamMap.entries.toList().forEach {(key, value) ->
                if(System.currentTimeMillis() - value.third > 5_000)
                    spamMap.remove(key)
            }
        }
    }
}

fun takeActionAgainstUser(member: Member, ban: Boolean, reason: String)
{
    if(member.guild.id == "174678310868090880")
    {
        val actionRole = member.guild.getRolesByName("Detention", true)[0]
        if(actionRole !in member.roles)
        {
            member.guild.controller.addSingleRoleToMember(member, actionRole).queue()
    
            val adminRoles = member.guild.getRolesByName("Moderator", true) + member.guild.getRolesByName("Admin", true)
            member.guild.getTextChannelById("270733523952861186").sendMessage(adminRoles.joinToString(" ", postfix = "\n${member.asMention} has been detected spamming and was given ${actionRole.asMention}") {it.asMention}).queue()
        }
    }
    else
    {
        if(ban)
            member.guild.controller.ban(member, 1, reason).queue()
        else
            member.guild.controller.kick(member, reason).queue()
    }
}

fun hashMessage(message: Message): ByteArray
{
    val messageDigest = DigestUtils.getSha256Digest()
    messageDigest.update(message.contentRaw.toByteArray())
    message.attachments.forEach {messageDigest.update(it.inputStream.retry(10, InputStream::toByteArray))}
    return messageDigest.digest()
}

fun InputStream.toByteArray(): ByteArray
{
    if(this !is ByteArrayInputStream)
        return this.readBytes()
    
    val field = ByteArrayInputStream::class.java.getDeclaredField("buf")
    field.isAccessible = true
    return field.get(this) as ByteArray
}