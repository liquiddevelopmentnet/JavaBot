package net.javadiscord.javabot.systems.staff_commands.embeds;

import com.dynxsty.dih4jda.interactions.ComponentIdBuilder;
import com.dynxsty.dih4jda.interactions.commands.SlashCommand;
import com.dynxsty.dih4jda.interactions.components.ModalHandler;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.Modal;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.javadiscord.javabot.util.Checks;
import net.javadiscord.javabot.util.Responses;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.List;

/**
 * This class represents the `/embed create` command.
 */
public class CreateEmbedSubcommand extends SlashCommand.Subcommand implements ModalHandler {
	/**
	 * The constructor of this class, which sets the corresponding {@link SubcommandData}.
	 */
	public CreateEmbedSubcommand() {
		setSubcommandData(new SubcommandData("create", "Creates a new basic embed message.")
				.addOptions(
						new OptionData(OptionType.CHANNEL, "channel", "What channel should the embed be sent to?", false)
								.setChannelTypes(ChannelType.TEXT, ChannelType.VOICE, ChannelType.GUILD_PRIVATE_THREAD, ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_NEWS_THREAD)
				)
		);
	}

	@Override
	public void execute(@NotNull SlashCommandInteractionEvent event) {
		if (event.getGuild() == null || event.getMember() == null) {
			Responses.replyGuildOnly(event).queue();
			return;
		}
		if (!Checks.hasStaffRole(event.getGuild(), event.getMember())) {
			Responses.replyStaffOnly(event, event.getGuild()).queue();
			return;
		}
		GuildMessageChannel channel = event.getOption("channel", event.getChannel().asGuildMessageChannel(), m -> m.getAsChannel().asGuildMessageChannel());
		if (!event.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_SEND, Permission.VIEW_CHANNEL)) {
			Responses.replyInsufficientPermissions(event, Permission.MESSAGE_SEND, Permission.VIEW_CHANNEL).queue();
			return;
		}
		event.replyModal(buildBasicEmbedCreateModal(channel)).queue();
	}

	@Override
	public void handleModal(@NotNull ModalInteractionEvent event, @NotNull List<ModalMapping> values) {
		event.deferReply(true).queue();
		if (event.getGuild() == null) {
			Responses.replyGuildOnly(event.getHook()).queue();
			return;
		}
		String[] id = ComponentIdBuilder.split(event.getModalId());
		TextChannel channel = event.getGuild().getTextChannelById(id[1]);
		if (channel == null) {
			Responses.error(event.getHook(), "Please provide a valid text channel.").queue();
			return;
		}
		EmbedBuilder builder = buildBasicEmbed(event);
		if (builder.isEmpty() || !builder.isValidLength()) {
			Responses.error(event.getHook(), "You've provided an invalid embed!").queue();
			return;
		}
		channel.sendMessageEmbeds(builder.build()).queue(
				s -> event.getHook().sendMessage("Done!").addActionRow(Button.link(s.getJumpUrl(), "Jump to Embed")).queue(),
				e -> Responses.error(event.getHook(), "Could not send embed: %s", e.getMessage()).queue()
		);
	}

	private @NotNull Modal buildBasicEmbedCreateModal(@NotNull Channel channel) {
		TextInput titleInput = TextInput.create("title", "Title", TextInputStyle.SHORT)
				.setPlaceholder(String.format("Choose a fitting title. (max. %s chars)", MessageEmbed.TITLE_MAX_LENGTH))
				.setMaxLength(MessageEmbed.TITLE_MAX_LENGTH)
				.setRequired(false)
				.build();
		TextInput descriptionInput = TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
				.setPlaceholder("Choose a description for your embed.")
				.setRequired(false)
				.build();
		TextInput colorInput = TextInput.create("color", "Hex Color (optional)", TextInputStyle.SHORT)
				.setPlaceholder("#FFFFFF")
				.setMaxLength(7)
				.setRequired(false)
				.build();
		TextInput imageInput = TextInput.create("image", "Image URL (optional)", TextInputStyle.SHORT)
				.setPlaceholder("https://example.com/example.png")
				.setRequired(false)
				.build();
		return Modal.create(ComponentIdBuilder.build("embed-create", channel.getIdLong()), "Create an Embed Message")
				.addActionRows(ActionRow.of(titleInput), ActionRow.of(descriptionInput), ActionRow.of(colorInput), ActionRow.of(imageInput))
				.build();
	}

	private @NotNull EmbedBuilder buildBasicEmbed(@NotNull ModalInteractionEvent event) {
		EmbedBuilder builder = new EmbedBuilder();
		ModalMapping titleMapping = event.getValue("title");
		ModalMapping descriptionMapping = event.getValue("description");
		ModalMapping colorMapping = event.getValue("color");
		ModalMapping imageMapping = event.getValue("image");
		if (titleMapping != null) {
			builder.setTitle(titleMapping.getAsString());
		}
		if (descriptionMapping != null) {
			builder.setDescription(descriptionMapping.getAsString());
		}
		if (colorMapping != null && Checks.checkHexColor(colorMapping.getAsString())) {
			builder.setColor(Color.decode(colorMapping.getAsString()));
		}
		if (imageMapping != null && Checks.checkImageUrl(imageMapping.getAsString())) {
			builder.setImage(imageMapping.getAsString());
		}
		return builder;
	}
}
