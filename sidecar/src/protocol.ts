// The NDJSON protocol between the Spring backend and this sidecar (one JSON object per line).
// Java-side mirror: ee.doniss.claudeweb.service.chat.sdk.SidecarProtocol.

/** Spring -> sidecar (stdin). */
export type InMessage =
  | {
      type: 'init'
      /** Pre-generated UUID for new sessions, or the existing id to resume. */
      sessionId: string
      /** true = resume an existing session, false = create a new one under sessionId. */
      resume: boolean
      cwd: string
      permissionMode: string
      /** The system `claude` executable (keeps versions consistent with terminal sessions). */
      executable: string
    }
  | {
      type: 'user_turn'
      turnId: string
      text: string
      permissionMode?: string
      model?: string
      /** Pasted images: whitelisted media type + bare base64 (no data: prefix). Omitted when none. */
      images?: { mediaType: string; data: string }[]
    }
  /** `always: true` (allow only) also persists the SDK's suggested permission rules. */
  | { type: 'permission_response'; requestId: string; behavior: 'allow' | 'deny'; message?: string; always?: boolean }
  | { type: 'question_answer'; requestId: string; answers: string[][] }
  | { type: 'interrupt' }
  | { type: 'shutdown' }

export interface QuestionOption {
  label: string
  description: string
}

export interface QuestionSpec {
  question: string
  header: string
  multiSelect: boolean
  options: QuestionOption[]
}

/** Sidecar -> Spring (stdout). */
export type OutMessage =
  | { type: 'ready'; sessionId: string; sdkVersion: string }
  | { type: 'turn_started'; turnId: string }
  | { type: 'text_delta'; turnId: string; text: string }
  | { type: 'thinking_delta'; turnId: string; text: string }
  | { type: 'tool'; turnId: string; toolUseId: string; title: string }
  | { type: 'tool_result'; turnId: string; toolUseId: string; body: string }
  /** `suggestions` = the SDK's PermissionUpdate[] enabling an "always allow" persisted rule. */
  | { type: 'permission_request'; requestId: string; turnId: string; toolName: string; input: unknown; suggestions: unknown[] }
  | { type: 'question'; requestId: string; turnId: string; questions: QuestionSpec[] }
  | { type: 'turn_done'; turnId: string }
  | { type: 'turn_error'; turnId: string; message: string }
  | { type: 'fatal'; message: string }
