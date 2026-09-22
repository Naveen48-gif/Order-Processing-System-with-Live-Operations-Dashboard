package com.ordermanagement.exception;

/**
 * A deliberately injected transient fault (operational failure drill).
 *
 * <p>Deriving from {@link TransientProcessingException} is the whole point: the pipeline treats it
 * exactly like a real lock timeout, so a drill exercises the genuine retry, backoff and DLQ code paths
 * rather than a mock of them.
 */
public class InjectedFaultException extends TransientProcessingException {

    public InjectedFaultException(String message) {
        super(message);
    }
}