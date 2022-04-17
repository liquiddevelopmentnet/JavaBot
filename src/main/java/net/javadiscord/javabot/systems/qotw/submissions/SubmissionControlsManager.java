package net.javadiscord.javabot.systems.qotw.submissions;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.SelectMenuInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectMenu;
import net.javadiscord.javabot.Bot;
import net.javadiscord.javabot.command.Responses;
import net.javadiscord.javabot.data.config.guild.QOTWConfig;
import net.javadiscord.javabot.data.h2db.DbHelper;
import net.javadiscord.javabot.systems.qotw.subcommands.qotw_points.IncrementSubcommand;
import net.javadiscord.javabot.systems.qotw.submissions.dao.QOTWSubmissionRepository;
import net.javadiscord.javabot.systems.qotw.submissions.model.QOTWSubmission;
import net.javadiscord.javabot.util.GuildUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Handles and manages Submission controls.
 */
@Slf4j
public class SubmissionControlsManager {
	private final String SUBMISSION_ACCEPTED = "\u2705";
	private final String SUBMISSION_DECLINED = "\u274C";
	private final String SUBMISSION_PENDING = "\uD83D\uDD52";

	private final Guild guild;
	private final QOTWConfig config;
	private final QOTWSubmission submission;

	/**
	 * The constructor of this class.
	 *
	 * @param guild      The current {@link Guild}.
	 * @param submission The {@link QOTWSubmission}.
	 */
	public SubmissionControlsManager(Guild guild, QOTWSubmission submission) {
		this.guild = guild;
		this.submission = submission;
		this.config = Bot.config.get(guild).getQotw();
	}

	/**
	 * The constructor of this class.
	 *
	 * @param guild   The current {@link Guild}.
	 * @param channel The {@link ThreadChannel}, which is used to retrieve the corresponding {@link QOTWSubmission}.
	 */
	public SubmissionControlsManager(Guild guild, ThreadChannel channel) {
		QOTWSubmission submission = null;
		try (Connection con = Bot.dataSource.getConnection()) {
			QOTWSubmissionRepository repo = new QOTWSubmissionRepository(con);
			Optional<QOTWSubmission> submissionOptional = repo.getSubmissionByThreadId(channel.getIdLong());
			if (submissionOptional.isEmpty()) {
				log.error("Could not retrieve Submission from Thread: " + channel.getId());
			} else {
				submission = submissionOptional.get();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		this.guild = guild;
		this.submission = submission;
		this.config = Bot.config.get(guild).getQotw();
	}

	/**
	 * Sends an embed in the submission's thread channel that allows QOTW-Reviewers to perform various actions.
	 */
	public void sendControls() {
		ThreadChannel thread = this.guild.getThreadChannelById(this.submission.getThreadId());
		if (thread == null) return;
		thread.getManager().setName(String.format("%s %s", SUBMISSION_PENDING, thread.getName())).queue();
		thread.sendMessage(config.getQOTWReviewRole().getAsMention())
				.setEmbeds(this.buildSubmissionControlEmbed())
				.setActionRows(this.buildInteractionControls()).queue();
		this.removeThreadOwner(thread.getJDA(), thread);
		log.info("Sent Submission Controls to thread {}", thread.getName());

	}

	private void removeThreadOwner(JDA jda, ThreadChannel thread) {
		DbHelper.doDaoAction(QOTWSubmissionRepository::new, dao -> {
			Optional<QOTWSubmission> submissionOptional = dao.getSubmissionByThreadId(thread.getIdLong());
			submissionOptional.ifPresent(submission -> jda.retrieveUserById(submission.getAuthorId()).queue(
					author -> thread.removeThreadMember(author).queue()
			));
		});
	}

	/**
	 * Accepts the current submission.
	 *
	 * @param event The {@link ButtonInteractionEvent} that was fired.
	 * @param thread The submission's {@link ThreadChannel}.
	 */
	protected void acceptSubmission(ButtonInteractionEvent event, ThreadChannel thread) {
		DbHelper.doDaoAction(QOTWSubmissionRepository::new, dao -> {
			Optional<QOTWSubmission> submissionOptional = dao.getSubmissionByThreadId(thread.getIdLong());
			if (submissionOptional.isEmpty()) return;
			QOTWSubmission submission = submissionOptional.get();
			dao.markReviewed(submission);
			dao.markAccepted(submission);
			event.getGuild().retrieveMemberById(submission.getAuthorId()).queue(
					member -> {
						if (member == null) {
							Responses.error(event.getHook(), "Cannot accept a submission of a user who is not a member of this server");
							return;
						}
						IncrementSubcommand.correct(member, true);
						thread.getManager().setName(SUBMISSION_ACCEPTED + thread.getName().substring(1)).queueAfter(5, TimeUnit.SECONDS);
						log.info("{} accepted {}'s submission", event.getUser().getAsTag(), member.getUser().getAsTag());
						GuildUtils.getLogChannel(event.getGuild()).sendMessageFormat("%s\n%s accepted %s's submission", thread.getAsMention(), event.getUser().getAsTag(), member.getUser().getAsTag()).queue();
						this.disableControls(String.format("Accepted by %s", event.getUser().getAsTag()), event.getMessage());
						Responses.success(event.getHook(), "Submission Accepted", "Successfully accepted submission by " + member.getAsMention()).queue();
					}
			);
		});
	}

	/**
	 * Declines the current submission.
	 *
	 * @param event The {@link ButtonInteractionEvent} that was fired.
	 * @param thread The submission's {@link ThreadChannel}.
	 */
	protected void declineSelectSubmission(SelectMenuInteractionEvent event, ThreadChannel thread) {
		String reasons = String.join(", ", event.getValues());
		DbHelper.doDaoAction(QOTWSubmissionRepository::new, dao -> {
			Optional<QOTWSubmission> submissionOptional = dao.getSubmissionByThreadId(thread.getIdLong());
			if (submissionOptional.isEmpty()) return;
			QOTWSubmission submission = submissionOptional.get();
			dao.markReviewed(submission);
			event.getGuild().retrieveMemberById(submission.getAuthorId()).queue(
					member -> {
						if (member == null) {
							Responses.error(event.getHook(), "Cannot accept a submission of a user who is not a member of this server");
							return;
						}
						member.getUser().openPrivateChannel().queue(c -> c.sendMessageEmbeds(buildSubmissionDeclinedEmbed(member.getUser(), reasons)).queue());
						thread.getManager().setName(SUBMISSION_DECLINED + thread.getName().substring(1)).queueAfter(5, TimeUnit.SECONDS);
						log.info("{} declined {}'s submission for: {}", event.getUser().getAsTag(), member.getUser().getAsTag(), reasons);
						GuildUtils.getLogChannel(event.getGuild()).sendMessageFormat("%s\n%s declined %s's submission for: `%s`", thread.getAsMention(), event.getUser().getAsTag(), member.getUser().getAsTag(), reasons).queue();
						this.disableControls(String.format("Declined by %s", event.getUser().getAsTag()), event.getMessage());
						Responses.success(event.getHook(), "Submission Declined",
								String.format("Successfully declined submission by %s for the following reasons:\n`%s`", member.getAsMention(), reasons)).queue();
					}
			);
		});
	}

	/**
	 * Deletes the current submission.
	 *
	 * @param event The {@link ButtonInteractionEvent} that was fired.
	 * @param thread The submission's {@link ThreadChannel}.
	 */
	protected void deleteSubmission(ButtonInteractionEvent event, ThreadChannel thread) {
		thread.delete().queueAfter(10, TimeUnit.SECONDS);
		log.info("{} deleted submission thread {}", event.getUser().getAsTag(), thread.getName());
		GuildUtils.getLogChannel(event.getGuild()).sendMessageFormat("%s deleted submission thread `%s`", event.getUser().getAsTag(), thread.getName()).queue();
		this.disableControls(String.format("Deleted by %s", event.getUser().getAsTag()), event.getMessage());
		DbHelper.doDaoAction(QOTWSubmissionRepository::new, dao -> dao.removeSubmission(thread.getIdLong()));
		event.getHook().sendMessage("This Submission will be deleted in 10 seconds.").setEphemeral(true).queue();
	}

	protected void declineButtonSubmission(ButtonInteractionEvent event) {
		event.getMessage().editMessageComponents(ActionRow.of(this.buildDeclineMenu())).queue();
	}

	private void disableControls(String buttonLabel, Message message) {
		message.editMessageComponents(ActionRow.of(Button.secondary("qotw-submission:controls:dummy", buttonLabel).asDisabled())).queue();
	}

	private MessageEmbed buildSubmissionDeclinedEmbed(User createdBy, String reasons) {
		return new EmbedBuilder()
				.setAuthor(createdBy.getAsTag(), null, createdBy.getEffectiveAvatarUrl())
				.setTitle("QOTW Notification")
				.setColor(Bot.config.get(config.getGuild()).getSlashCommand().getErrorColor())
				.setDescription(String.format("""
								Hey %s,
								Your QOTW-Submission was **declined** for the following reasons:
								**`%s`**

								However, you can try your luck again next week!""",
						createdBy.getAsMention(), reasons))
				.setTimestamp(Instant.now())
				.build();
	}

	private List<ActionRow> buildInteractionControls() {
		return List.of(ActionRow.of(
				Button.success("qotw-submission:controls:accept", "Accept"),
				Button.danger("qotw-submission:controls:decline", "Decline"),
				Button.secondary("qotw-submission:controls:delete", "🗑️")));

	}

	private SelectMenu buildDeclineMenu() {
		return SelectMenu.create("qotw-submission-select:decline")
				.setPlaceholder("Select a reason for declining this submission.")
				.setRequiredRange(1, 3)
				.addOption("Wrong Answer", "Wrong Answer", "The content of the submission was not correct.")
				.addOption("Incomplete Answer", "Incomplete Answer", "The submission was missing some important things and was overall incomplete.")
				.addOption("Too short", "Too short", "The submission was way too short in comparison to other submissions.")
				.build();
	}

	private MessageEmbed buildSubmissionControlEmbed() {
		return new EmbedBuilder()
				.setTitle("Submission Controls")
				.setDescription("Please choose an action for this Submission.")
				.setTimestamp(Instant.now())
				.build();
	}
}
