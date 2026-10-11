package io.bugreplay;

/** Stand-in for a recorded exception whose class could not be recreated locally. */
public final class ReplayedException extends RuntimeException {

    private final String originalType;

    public ReplayedException(String originalType, String message) {
        super(originalType + ": " + message);
        this.originalType = originalType;
    }

    public String originalType() {
        return originalType;
    }
}
