package eu.avalanche7.paradigm.modules.discord.client;

import java.util.List;
import java.util.Map;

public record DiscordInboundMessage(
        String messageId,
        String channelId,
        String guildId,
        String authorId,
        String authorDisplayName,
        boolean bot,
        boolean webhook,
        boolean system,
        String content,
        List<String> attachmentUrls,
        Map<String, String> mentionNames,
        String replyAuthorName,
        String replyContent,
        List<Attachment> attachments,
        List<EmbedSummary> embeds,
        List<String> stickerNames,
        Integer authorColorRgb) {
    public record Attachment(String url, String filename) {
    }

    public record EmbedSummary(String authorName, String title, String description, String imageUrl) {
        public boolean isEmpty() {
            return blank(authorName) && blank(title) && blank(description) && blank(imageUrl);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public DiscordInboundMessage {
        attachmentUrls = attachmentUrls == null ? List.of() : List.copyOf(attachmentUrls);
        mentionNames = mentionNames == null ? Map.of() : Map.copyOf(mentionNames);
        embeds = embeds == null ? List.of() : List.copyOf(embeds);
        stickerNames = stickerNames == null ? List.of() : List.copyOf(stickerNames);
        content = content == null ? "" : content;
        if (attachments == null || attachments.isEmpty()) {
            attachments = attachmentUrls.stream()
                    .map(url -> new Attachment(url, "attachment"))
                    .toList();
        } else {
            attachments = List.copyOf(attachments);
        }
    }

    public DiscordInboundMessage(String messageId, String channelId, String guildId, String authorId,
                                 String authorDisplayName, boolean bot, boolean webhook, boolean system,
                                 String content, List<String> attachmentUrls, Map<String, String> mentionNames) {
        this(messageId, channelId, guildId, authorId, authorDisplayName, bot, webhook, system, content,
                attachmentUrls, mentionNames, null, null, null, null, null, null);
    }

    public DiscordInboundMessage(String messageId, String channelId, String guildId, String authorId,
                                 String authorDisplayName, boolean bot, boolean webhook, boolean system,
                                 String content, List<String> attachmentUrls, Map<String, String> mentionNames,
                                 String replyAuthorName, String replyContent) {
        this(messageId, channelId, guildId, authorId, authorDisplayName, bot, webhook, system, content,
                attachmentUrls, mentionNames, replyAuthorName, replyContent, null, null, null, null);
    }

    public boolean hasReply() {
        return replyAuthorName != null && !replyAuthorName.isBlank();
    }

    public boolean hasContent() {
        return !content.isBlank();
    }

    public boolean hasAttachments() {
        return !attachmentUrls.isEmpty();
    }

    public boolean hasRichContent() {
        return !attachments.isEmpty() || !stickerNames.isEmpty()
                || embeds.stream().anyMatch(embed -> !embed.isEmpty());
    }
}
