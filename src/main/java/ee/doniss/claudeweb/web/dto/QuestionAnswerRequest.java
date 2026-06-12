package ee.doniss.claudeweb.web.dto;

import java.util.List;

/**
 * The browser's in-turn answer to a pending {@code AskUserQuestion} card ({@code sdk} engine):
 * one list of selected option labels per question, in question order.
 */
public record QuestionAnswerRequest(String requestId, List<List<String>> answers) {
}
