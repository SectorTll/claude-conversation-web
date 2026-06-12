package ee.doniss.claudeweb.service.chat;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builders for the simplified browser events both chat engines stream as NDJSON. */
public final class ChatEvents {

    public static final MediaType NDJSON = MediaType.valueOf("application/x-ndjson");

    private ChatEvents() {
    }

    /** An ordered {@code {type, k1: v1, ...}} event map. */
    public static Map<String, Object> event(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Serialize and send one event line; a closed emitter is ignored (the reader stops on the next write). */
    public static void send(ResponseBodyEmitter emitter, ObjectMapper mapper, Map<String, Object> evt) {
        try {
            emitter.send(mapper.writeValueAsString(evt) + "\n", NDJSON);
        } catch (Exception ignore) {
            // emitter closed / client gone
        }
    }
}
