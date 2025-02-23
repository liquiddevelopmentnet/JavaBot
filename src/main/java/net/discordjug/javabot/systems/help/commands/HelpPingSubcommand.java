package net.discordjug.javabot.systems.help.commands;

import net.discordjug.javabot.annotations.AutoDetectableComponentHandler;
import net.discordjug.javabot.data.config.BotConfig;
import net.discordjug.javabot.data.config.GuildConfig;
import net.discordjug.javabot.data.config.guild.HelpConfig;
import net.discordjug.javabot.util.Pair;
import net.discordjug.javabot.util.Responses;
import net.discordjug.javabot.util.StringUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.entities.emoji.EmojiUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.utils.TimeFormat;

import org.jetbrains.annotations.NotNull;
import xyz.dynxsty.dih4jda.interactions.commands.application.SlashCommand;
import xyz.dynxsty.dih4jda.interactions.components.ButtonHandler;
import xyz.dynxsty.dih4jda.util.ComponentIdBuilder;

import java.awt.Color;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Handler for the /help ping sub-command that allows users to occasionally ping
 * helpers.
 */
@AutoDetectableComponentHandler("help-ping")
public class HelpPingSubcommand extends SlashCommand.Subcommand implements ButtonHandler {
	private static final String WRONG_CHANNEL_MSG = "This command can only be used in **help forum posts**";
	private static final long CACHE_CLEANUP_DELAY = 60L;

	private final Map<Long, Pair<Long, Guild>> lastPingTimes;
	private final BotConfig botConfig;

	/**
	 * Constructor that initializes and handles the cooldown map.
	 * @param asyncPool The thread pool for asynchronous operations
	 * @param botConfig The main configuration of the bot
	 */
	public HelpPingSubcommand(BotConfig botConfig, ScheduledExecutorService asyncPool) {
		setCommandData(new SubcommandData("ping", "Notify potential helpers that your question is urgent.")
				.addOption(OptionType.STRING, "comment", "Optionally enter the reason you used this to be seen by helpers (e.g. 'no response')", false));
		lastPingTimes = new ConcurrentHashMap<>();
		this.botConfig = botConfig;
		asyncPool.scheduleWithFixedDelay(this::cleanTimeoutCache, CACHE_CLEANUP_DELAY, CACHE_CLEANUP_DELAY, TimeUnit.SECONDS);
	}

	@Override
	public void execute(@NotNull SlashCommandInteractionEvent event) {
		Guild guild = event.getGuild();
		if (guild == null) {
			Responses.warning(event, WRONG_CHANNEL_MSG).queue();
			return;
		}
		GuildConfig config = botConfig.get(guild);
		ThreadChannel post = event.getChannel().asThreadChannel();
		if (post.getParentChannel().getType() != ChannelType.FORUM ||
				post.getParentChannel().getIdLong() != config.getHelpConfig().getHelpForumChannelId()
		) {
			Responses.error(event, WRONG_CHANNEL_MSG).queue();
			return;
		}
		Member member = event.getMember();
		if (member == null) {
			Responses.warning(event, "No member information was available for this event.").queue();
			return;
		}
		if (isHelpPingForbiddenForMember(post.getOwnerIdLong(), member, config)) {
			Responses.warning(event, "Sorry, but only the person who reserved this channel, or staff and helpers, may use this command.").queue();
			return;
		}
		if (post.getTimeCreated().isAfter(OffsetDateTime.now().minusSeconds(config.getHelpConfig().getHelpPingTimeoutSeconds()))) {
			Responses.warning(event, "Sorry, this command cannot be used directly after a post has been created.").queue();
			return;
		}

		String comment = event.getOption("comment", null, OptionMapping::getAsString);

		if (isHelpPingTimeoutElapsed(member.getIdLong(), config)) {
			lastPingTimes.put(event.getMember().getIdLong(), new Pair<>(System.currentTimeMillis(), config.getGuild()));
			TextChannel notifChannel = config.getHelpConfig().getHelpNotificationChannel();

			notifChannel.sendMessageEmbeds(createHelpEmbed(comment, post, member))
				.addActionRow(createAcknowledgementButton(post.getId()))
				.queue();
			event.reply("""
					Successfully requested help.

					Note that this does NOT gurantee that anybody here has the time and knowledge to help you.
					Abusing this command might result in moderative action taken against you.
					""")
			.setEphemeral(true)
			.queue();
		} else {
			Responses.warning(event, "Sorry, but you can only use this command occasionally. Please try again later.").queue();
		}
	}

	private MessageEmbed createHelpEmbed(String comment, ThreadChannel post, Member member) {
		EmbedBuilder eb = createBasicHelpEmbedBuilder(post, member);
		appendComment(eb, comment);
		appendTags(eb, post);
		eb.appendDescription("\n\n[Click to view]("+post.getJumpUrl()+")");
		return eb.build();
	}

	private EmbedBuilder createBasicHelpEmbedBuilder(ThreadChannel post, Member member) {
		EmbedBuilder eb = new EmbedBuilder()
				.setDescription("%s requested help in %s"
					.formatted(
							member.getAsMention(),
							post.getAsMention()
					))
				.setAuthor(member.getEffectiveName(), null, member.getEffectiveAvatarUrl())
				.setFooter(member.getId())
				.setColor(Color.YELLOW);
		return eb;
	}

	private void appendTags(EmbedBuilder eb, ThreadChannel post) {
		List<ForumTag> tags = post.getAppliedTags();
		if (!tags.isEmpty()) {
			String text = tags
					.stream()
					.map(this::getForumTagText)
					.map(tag -> "- " + tag)
					.collect(Collectors.joining("\n"));
			eb.appendDescription("\n\nTags:\n")
			.appendDescription(text);
		}
	}

	private void appendComment(EmbedBuilder eb, String comment) {
		if (comment != null) {
			eb
				.appendDescription("\n\ncomment:\n")
				.appendDescription("```\n"+StringUtils.standardSanitizer().compute(comment) + "\n```");
		}
	}

	private Button createAcknowledgementButton(String postId) {
		return Button.of(ButtonStyle.SECONDARY, ComponentIdBuilder.build("help-ping", "acknowledge", postId), "Mark as acknowledged");
	}

	private Button createUndoAcknowledgementButton(String postId) {
		return Button.of(ButtonStyle.SECONDARY, ComponentIdBuilder.build("help-ping", "unacknowledge", postId), "Mark as unacknowledged");
	}

	/**
	 * Determines if a user is forbidden from sending a help-ping command due
	 * to their status in the server.
	 *
	 * @param postOwnerId The posts' owner id.
	 * @param member      The member.
	 * @param config      The guild config.
	 * @return True if the user is forbidden from sending the command.
	 */
	private boolean isHelpPingForbiddenForMember(long postOwnerId, @NotNull Member member, @NotNull GuildConfig config) {
		Set<Role> allowedRoles = Set.of(config.getModerationConfig().getStaffRole(), config.getHelpConfig().getHelperRole());
		return !(
				postOwnerId == member.getUser().getIdLong() ||
						member.getRoles().stream().anyMatch(allowedRoles::contains) ||
						member.isOwner()
		);
	}

	/**
	 * Determines if the user's timeout has elapsed (or doesn't exist), which
	 * implies that it's fine for the user to send the command.
	 *
	 * @param memberId The members' id.
	 * @param config   The guild config.
	 * @return True if the user's timeout has elapsed or doesn't exist, or
	 * false if the user should NOT send the command because of their timeout.
	 */
	private boolean isHelpPingTimeoutElapsed(long memberId, GuildConfig config) {
		Pair<Long, Guild> lastPing = lastPingTimes.get(memberId);
		return lastPing == null ||
				lastPing.first() + config.getHelpConfig().getHelpPingTimeoutSeconds() * 1000L < System.currentTimeMillis();
	}

	/**
	 * Method that cleans out any entries from the list of last ping times if
	 * their timeout is no longer valid.
	 */
	private void cleanTimeoutCache() {
		// Find the list of members whose last ping time was old enough that they should be removed from the cache.
		List<Long> memberIdsToRemove = lastPingTimes.entrySet().stream().filter(entry -> {
			HelpConfig config = botConfig.get(entry.getValue().second()).getHelpConfig();
			long timeoutMillis = config.getHelpPingTimeoutSeconds() * 1000L;
			return entry.getValue().first() + timeoutMillis < System.currentTimeMillis();
		}).map(Map.Entry::getKey).toList();
		// Remove each member from the map.
		for (Long memberId : memberIdsToRemove) {
			lastPingTimes.remove(memberId);
		}
	}

	@Override
	public void handleButton(ButtonInteractionEvent event, Button button) {
		String[] id = ComponentIdBuilder.split(event.getComponentId());
		String postId = "";
		if (id.length > 2) {
			postId = id[2];
		}
		switch(id[1]) {
		case "acknowledge" ->
			acknowledgeChangeAction(event, postId, true);
		case "unacknowledge" ->
			handleUnacknowledge(event, postId);
		default -> event.reply("Unknown button").setEphemeral(true).queue();
		}
	}

	private void handleUnacknowledge(ButtonInteractionEvent event, String postId) {
		if (postId.isEmpty() || isPostOpen(event.getGuild(), postId)) {
			acknowledgeChangeAction(event, postId, false);
		} else {
			Responses.error(event, """
					This post is closed or dormant.
					If this post is dormant, you can mark it as active by sending a message in the post.
					If it is closed and you still need help, please create a new post.
					""").queue();
		}
	}

	private boolean isPostOpen(Guild guild, String postId) {
		ThreadChannel post = guild.getThreadChannelById(postId);
		return post != null &&
				post.getParentChannel().getIdLong() == botConfig.get(guild).getHelpConfig().getHelpForumChannelId() &&
				!post.isArchived() &&
				!post.isLocked();
	}

	private void acknowledgeChangeAction(ButtonInteractionEvent event, String postId, boolean acknowledged) {
		event.editMessageEmbeds(
			event.getMessage()
			.getEmbeds()
			.stream()
			.map(e->new EmbedBuilder(e)
					.setColor(acknowledged ? Color.GRAY : Color.YELLOW)
					.addField("marked as " + (acknowledged?"acknowledged":"needs help"),
							"by " + event.getUser().getAsMention()+" "+getCurrentFormattedTimestamp(), false))
			.map(this::removeOldField)
			.map(EmbedBuilder::build)
			.toList())
		.setActionRow(acknowledged?createUndoAcknowledgementButton(postId):createAcknowledgementButton(postId))
		.queue();
	}

	private String getCurrentFormattedTimestamp() {
		return TimeFormat.RELATIVE.format(Instant.now().toEpochMilli());
	}

	private String getForumTagText(ForumTag tag) {
		EmojiUnion emoji = tag.getEmoji();
		StringBuilder sb=new StringBuilder();
		if(emoji!=null) {
			sb
				.append(emoji.getFormatted())
				.append(" ");
		}
		sb.append(tag.getName());

		return sb.toString();
	}

	private EmbedBuilder removeOldField(EmbedBuilder eb) {
		if(eb.getFields().size()>5) {
			eb.getFields().remove(0);
		}
		return eb;
	}
}
