package io.bugreplay;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** One recorded call to a dependency: arguments in, and either a result or an exception out. */
public record Interaction(
        String dependency,
        String method,
        List<String> paramTypes,
        List<JsonNode> args,
        JsonNode result,
        String thrownType,
        String thrownMessage) {

    public boolean threw() {
        return thrownType != null;
    }
}
