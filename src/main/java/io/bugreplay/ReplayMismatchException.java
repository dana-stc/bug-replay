package io.bugreplay;

/**
 * The code under test did something different from the recording. It is an {@link AssertionError}
 * so that a broad {@code catch (Exception e)} in the code under test cannot swallow it.
 */
public final class ReplayMismatchException extends AssertionError {

    public ReplayMismatchException(String message) {
        super(message);
    }
}
