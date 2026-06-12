package ee.doniss.claudeweb.service.chat;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.support.BadRequestException;
import ee.doniss.claudeweb.web.dto.ChatRequest;

import java.util.List;
import java.util.Set;

/**
 * Server-side validation of pasted images. The browser enforces the same limits for UX, but the
 * server is the gate: count, decoded size, the SDK's media-type whitelist, and base64 shape.
 */
public final class ChatAttachments {

    /** The image media types the Anthropic API accepts as base64 image blocks. */
    public static final Set<String> ALLOWED_MEDIA_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private ChatAttachments() {
    }

    /** True when there is at least one attachment to send. */
    public static boolean any(List<ChatRequest.Attachment> attachments) {
        return attachments != null && !attachments.isEmpty();
    }

    /** Validate or throw {@link BadRequestException}. A null/empty list is fine (text-only turn). */
    public static void validate(List<ChatRequest.Attachment> attachments, ClaudeProperties.Chat chat) {
        if (!any(attachments)) {
            return;
        }
        if (attachments.size() > chat.getImageMaxCount()) {
            throw new BadRequestException("too many images (max " + chat.getImageMaxCount() + ")");
        }
        for (ChatRequest.Attachment a : attachments) {
            if (a == null || a.mediaType() == null || !ALLOWED_MEDIA_TYPES.contains(a.mediaType())) {
                throw new BadRequestException("unsupported image type"
                        + (a != null && a.mediaType() != null ? ": " + a.mediaType() : ""));
            }
            String data = a.data();
            if (data == null || data.isBlank()) {
                throw new BadRequestException("empty image data");
            }
            // ~3/4 of the base64 length is the decoded size; precise enough for a limit check.
            long decoded = (long) (data.length() * 0.75);
            if (decoded > chat.getImageMaxBytes()) {
                throw new BadRequestException("image too large (max "
                        + chat.getImageMaxBytes() / (1024 * 1024) + " MB)");
            }
            if (!data.matches("[A-Za-z0-9+/=\\r\\n]+")) {
                throw new BadRequestException("image data is not base64");
            }
        }
    }
}
