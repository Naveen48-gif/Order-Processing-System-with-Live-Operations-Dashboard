package com.ordermanagement.service;

import com.ordermanagement.exception.InjectedFaultException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Arms a bounded number of injected transient faults for one product (failure drill).
 *
 * <p>Why this exists: the retry ladder - bounded retry with backoff, then the dead-letter queue, then a
 * manual replay - is the hardest part of the system to trust, because it only runs when something goes
 * wrong. A drill makes "something goes wrong" a deliberate, bounded, reproducible action, so the
 * behaviour can be demonstrated to a reviewer and asserted in an automated test.
 *
 * <p>Design notes:
 * <ul>
 *   <li><strong>Atomic per product.</strong> The counter is a {@link ConcurrentHashMap} of
 *       {@link AtomicInteger}s, so ten workers retrying the same product concurrently inject exactly the
 *       number of faults that was armed - no more, no fewer.</li>
 *   <li><strong>Self-disarming.</strong> The budget reaching zero removes the entry, so a drill cannot
 *       leak into later traffic. The pipeline heals itself without an operator having to remember to
 *       switch it off.</li>
 *   <li><strong>Gated.</strong> The bean only exists when {@code order.ops.fault-injection.enabled=true};
 *       off by default, so production traffic can never be disrupted by it.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "order.ops.fault-injection", name = "enabled", havingValue = "true")
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    /** Upper bound on a single drill, so a mistyped request cannot disable a product indefinitely. */
    public static final int MAX_OCCURRENCES = 50;

    private final Map<Long, AtomicInteger> armedFaults = new ConcurrentHashMap<>();

    /** Arms (or re-arms) {@code occurrences} injected failures for one product. */
    public void arm(Long productId, int occurrences) {
        if (occurrences < 1) {
            armedFaults.remove(productId);
            return;
        }
        int bounded = Math.min(occurrences, MAX_OCCURRENCES);
        armedFaults.put(productId, new AtomicInteger(bounded));
        log.warn("FAULT_ARMED productId={} occurrences={}", productId, bounded);
    }

    /** Disarms any armed fault for a product. Returns whether anything was armed. */
    public boolean clear(Long productId) {
        boolean wasArmed = armedFaults.remove(productId) != null;
        if (wasArmed) {
            log.warn("FAULT_CLEARED productId={}", productId);
        }
        return wasArmed;
    }

    /** Remaining injected failures per product, for the status endpoint and the dashboard. */
    public Map<Long, Integer> armedFaults() {
        return armedFaults.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get(),
                        (left, right) -> left, java.util.LinkedHashMap::new));
    }

    /**
     * Consumes one unit of the armed budget and fails the current reservation.
     *
     * <p>Called from inside the reservation transaction, before any database work, so the injected fault
     * rolls back exactly like a real transient failure would and the order goes through the genuine
     * retry path.
     */
    public void failIfArmed(Long productId) {
        AtomicInteger remaining = armedFaults.get(productId);
        if (remaining == null) {
            return;
        }

        int budgetBefore = remaining.getAndUpdate(value -> value > 0 ? value - 1 : 0);
        if (budgetBefore <= 0) {
            armedFaults.remove(productId, remaining);
            return;
        }

        int left = budgetBefore - 1;
        if (left == 0) {
            // Budget exhausted: disarm so normal traffic resumes immediately.
            armedFaults.remove(productId, remaining);
        }
        log.warn("FAULT_INJECTED productId={} remainingAfterThis={}", productId, left);
        throw new InjectedFaultException(
                "injected transient fault for product %d (%d injected failure(s) still to come)"
                        .formatted(productId, left));
    }
}