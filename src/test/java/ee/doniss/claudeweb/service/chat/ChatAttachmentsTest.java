package ee.doniss.claudeweb.service.chat;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.support.BadRequestException;
import ee.doniss.claudeweb.web.dto.ChatRequest.Attachment;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAttachmentsTest {

    private final ClaudeProperties.Chat chat = new ClaudeProperties.Chat();

    @Test
    void nullOrEmptyListIsFine() {
        assertDoesNotThrow(() -> ChatAttachments.validate(null, chat));
        assertDoesNotThrow(() -> ChatAttachments.validate(List.of(), chat));
    }

    @Test
    void validPngPasses() {
        assertDoesNotThrow(() -> ChatAttachments.validate(
                List.of(new Attachment("image/png", "aGVsbG8=")), chat));
    }

    @Test
    void rejectsTooMany() {
        List<Attachment> five = Collections.nCopies(5, new Attachment("image/png", "aGVsbG8="));
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> ChatAttachments.validate(five, chat));
        assertTrue(e.getMessage().contains("too many"));
    }

    @Test
    void rejectsUnsupportedMediaType() {
        assertThrows(BadRequestException.class, () -> ChatAttachments.validate(
                List.of(new Attachment("image/bmp", "aGVsbG8=")), chat));
        assertThrows(BadRequestException.class, () -> ChatAttachments.validate(
                List.of(new Attachment(null, "aGVsbG8=")), chat));
    }

    @Test
    void rejectsOversizedImage() {
        chat.setImageMaxBytes(8);
        assertThrows(BadRequestException.class, () -> ChatAttachments.validate(
                List.of(new Attachment("image/png", "QUFBQUFBQUFBQUFBQUFBQQ==")), chat));
    }

    @Test
    void rejectsNonBase64Data() {
        assertThrows(BadRequestException.class, () -> ChatAttachments.validate(
                List.of(new Attachment("image/png", "not base64 🙂")), chat));
        assertThrows(BadRequestException.class, () -> ChatAttachments.validate(
                List.of(new Attachment("image/png", "")), chat));
    }
}
