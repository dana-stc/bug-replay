package io.bugreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class BugReplayTest {

    public record Receipt(String id, BigDecimal amount, String email) {
    }

    public interface Billing {
        Receipt charge(String email, long cents);

        String lookup(Map<String, Object> filter);

        void audit(String message);
    }

    /** A real implementation standing in for production code. */
    static class RealBilling implements Billing {
        @Override
        public Receipt charge(String email, long cents) {
            if (cents > 100_000) {
                throw new IllegalStateException("card declined");
            }
            return new Receipt("r1", new BigDecimal("10.50"), email);
        }

        @Override
        public String lookup(Map<String, Object> filter) {
            return "found:" + filter.get("q");
        }

        @Override
        public void audit(String message) {
        }
    }

    private static String masked(String text) {
        return Masker.defaults().mask(TextNode.valueOf(text)).textValue();
    }

    @Test
    void replayReturnsRecordedResults() {
        Session session = Session.start("happy path");
        Billing billing = session.record(Billing.class, new RealBilling());
        billing.charge("ana@example.com", 1050L);
        billing.audit("charged");

        Billing mock = Replay.of(session.finish()).mock(Billing.class);
        Receipt receipt = mock.charge("ana@example.com", 1050L);
        mock.audit("charged");

        assertEquals("r1", receipt.id());
        assertEquals(0, new BigDecimal("10.50").compareTo(receipt.amount()));
        assertEquals(masked("ana@example.com"), receipt.email());
    }

    @Test
    void recordedExceptionsAreRethrownOnReplay() {
        Session session = Session.start("declined");
        Billing billing = session.record(Billing.class, new RealBilling());
        assertThrows(IllegalStateException.class, () -> billing.charge("ana@example.com", 999_999L));

        Billing mock = Replay.of(session.finish()).mock(Billing.class);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mock.charge("ana@example.com", 999_999L));
        assertEquals("card declined", e.getMessage());
    }

    @Test
    void recordingFileContainsNoRawPersonalData(@TempDir Path dir) throws IOException {
        Session session = Session.start("pii");
        Billing billing = session.record(Billing.class, new RealBilling());
        billing.charge("ana@example.com", 1050L);
        billing.lookup(Map.of("q", "order-7", "password", "hunter2", "card", "4111111111111111"));

        Path file = dir.resolve("rec.json");
        session.finish().save(file);
        String json = Files.readString(file);

        assertFalse(json.contains("ana@example.com"));
        assertFalse(json.contains("hunter2"));
        assertFalse(json.contains("4111111111111111"));
        assertTrue(json.contains("order-7"), "non-sensitive values must be kept");

        // the masked file can still be replayed with the original (unmasked) arguments
        Billing mock = Replay.of(Recording.load(file)).mock(Billing.class);
        mock.charge("ana@example.com", 1050L);
        assertEquals("found:order-7",
                mock.lookup(Map.of("q", "order-7", "password", "hunter2", "card", "4111111111111111")));
    }

    @Test
    void numbersThatAreNotCardsAreLeftAlone() {
        // 13 digits but fails the Luhn check, like an epoch-millis timestamp
        assertEquals("1700000000000", masked("1700000000000"));
        assertFalse(masked("4111111111111111").equals("4111111111111111"));
        assertEquals(16, masked("4111111111111111").length());
    }

    @Test
    void differentArgumentsFailWithAReadableDiff() {
        Session session = Session.start("args");
        session.record(Billing.class, new RealBilling()).charge("ana@example.com", 1050L);

        Billing mock = Replay.of(session.finish()).mock(Billing.class);
        ReplayMismatchException e = assertThrows(ReplayMismatchException.class,
                () -> mock.charge("ana@example.com", 2000L));
        assertTrue(e.getMessage().contains("expected: Billing.charge"));
        assertTrue(e.getMessage().contains("actual:"));
    }

    @Test
    void unexpectedExtraCallFails() {
        Session session = Session.start("extra");
        session.record(Billing.class, new RealBilling()).audit("once");

        Billing mock = Replay.of(session.finish()).mock(Billing.class);
        mock.audit("once");
        assertThrows(ReplayMismatchException.class, () -> mock.audit("once"));
    }

    @Test
    void verifyAllConsumedFailsWhenRecordedCallsWereSkipped() {
        Session session = Session.start("skipped");
        Billing billing = session.record(Billing.class, new RealBilling());
        billing.audit("one");
        billing.audit("two");

        Replay replay = Replay.of(session.finish());
        replay.mock(Billing.class).audit("one");
        ReplayMismatchException e = assertThrows(ReplayMismatchException.class, replay::verifyAllConsumed);
        assertTrue(e.getMessage().contains("two"));
    }

    @Test
    void generatedTestCompilesAndMentionsTheFailure(@TempDir Path dir) throws IOException {
        Session session = Session.start("checkout 500");
        Billing billing = session.record(Billing.class, new RealBilling());
        try {
            billing.charge("ana@example.com", 999_999L);
        } catch (IllegalStateException e) {
            session.failed(e);
        }
        Recording recording = session.finish();

        Path src = dir.resolve("src");
        Path res = dir.resolve("res");
        GeneratedTest test = TestGenerator.write(recording, "com.acme.bugs", src, res);

        assertTrue(test.source().contains("Billing billing = replay.mock("));
        assertTrue(test.source().contains("IllegalStateException: card declined"));
        assertTrue(Files.exists(res.resolve("bugreplay/" + recording.id() + ".json")));

        Path javaFile = src.resolve("com/acme/bugs/" + test.className() + ".java");
        Path out = Files.createDirectories(dir.resolve("out"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int code = compiler.run(null, null, null, "-proc:none", "-d", out.toString(), "-classpath",
                System.getProperty("java.class.path"), javaFile.toString());
        assertEquals(0, code, "generated test must compile");
    }
}
