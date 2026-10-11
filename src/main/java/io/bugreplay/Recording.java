package io.bugreplay;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** The masked, serializable capture of one request: its dependency calls and how it failed. */
public record Recording(
        String id,
        String scenario,
        Instant recordedAt,
        List<Interaction> interactions,
        Failure failure) {

    /** The exception that ended the recorded request, if any. */
    public record Failure(String type, String message) {
    }

    public void save(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Recording load(Path file) {
        try {
            return Json.MAPPER.readValue(file.toFile(), Recording.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads a recording from the classpath, e.g. {@code "/bugreplay/3f9a1c2b.json"}. */
    public static Recording fromResource(String resource) {
        try (InputStream in = Recording.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Recording not found on classpath: " + resource);
            }
            return Json.MAPPER.readValue(in, Recording.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
