package io.bugreplay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes personal data from recorded values.
 *
 * <p>Masking is deterministic: the same input always yields the same masked output, so
 * equality between values is preserved and a recording can still be replayed. Masked
 * emails stay valid emails ({@code 1a2b3c4d@masked.invalid}) and masked card numbers keep
 * their length.
 *
 * <p>The recording side and the replay side must use the same masker configuration.
 */
public final class Masker {

    private static final Set<String> DEFAULT_FIELDS = Set.of("password", "passwd", "secret", "token",
            "accesstoken", "refreshtoken", "apikey", "authorization", "ssn", "cnp", "cardnumber", "cvv",
            "iban", "pin", "phone", "phonenumber");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern DIGITS = Pattern.compile("\\b\\d{13,19}\\b");

    private final Set<String> sensitiveFields;
    private final String salt;

    private Masker(Set<String> sensitiveFields, String salt) {
        this.sensitiveFields = sensitiveFields;
        this.salt = salt;
    }

    public static Masker defaults() {
        return new Masker(DEFAULT_FIELDS, "bugreplay");
    }

    /** Returns a masker that additionally masks the values of these field names. */
    public Masker withSensitiveFields(String... names) {
        Set<String> all = new HashSet<>(sensitiveFields);
        for (String name : names) {
            all.add(normalize(name));
        }
        return new Masker(Set.copyOf(all), salt);
    }

    /** Returns a masker with a different salt. Use a secret salt if recordings leave your network. */
    public Masker withSalt(String newSalt) {
        return new Masker(sensitiveFields, newSalt);
    }

    public JsonNode mask(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(maskText(node.textValue()));
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), maskField(e.getKey(), e.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(mask(child)));
            return out;
        }
        return node;
    }

    private JsonNode maskField(String name, JsonNode value) {
        if (sensitiveFields.contains(normalize(name))) {
            if (value.isTextual()) {
                return TextNode.valueOf("masked-" + hash8(value.textValue()));
            }
            if (value.isNumber()) {
                return IntNode.valueOf(0);
            }
        }
        return mask(value);
    }

    private String maskText(String text) {
        String withoutEmails = EMAIL.matcher(text).replaceAll(m -> hash8(m.group()) + "@masked.invalid");
        return DIGITS.matcher(withoutEmails).replaceAll(m -> luhnValid(m.group()) ? fakeDigits(m.group()) : m.group());
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alternate) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private String fakeDigits(String original) {
        byte[] digest = sha256(salt + ":" + original);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < original.length(); i++) {
            sb.append((digest[i % digest.length] & 0xff) % 10);
        }
        return sb.toString();
    }

    private String hash8(String value) {
        byte[] digest = sha256(salt + ":" + value);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
