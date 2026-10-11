package io.bugreplay;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;

final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Json() {
    }

    /**
     * Converts a value to a tree that went through text, so that a live value and the same
     * value read back from a recording file produce equal nodes (e.g. long 5 vs int 5).
     */
    static JsonNode tree(Object value) {
        try {
            return MAPPER.readTree(MAPPER.writeValueAsBytes(value));
        } catch (IOException e) {
            return TextNode.valueOf(String.valueOf(value));
        }
    }
}
