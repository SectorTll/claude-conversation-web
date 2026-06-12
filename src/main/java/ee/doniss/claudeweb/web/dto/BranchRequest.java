package ee.doniss.claudeweb.web.dto;

/** Fork-from-message request: the uuid of the last message to keep. */
public record BranchRequest(String uuid) {
}
