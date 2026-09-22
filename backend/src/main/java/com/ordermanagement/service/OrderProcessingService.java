package com.ordermanagement.service;

import com.ordermanagement.config.OrderProcessingProperties;
import com.ordermanagement.exception.BusinessRuleViolationException;
import com.ordermanagement.exception.OutOfStockException;
import com.ordermanagement.exception.TransientProcessingException;
import com.ordermanagement.messaging.OrderTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/**
 * The worker pipeline: one call processes one order.
 *
 * <p>Flow for a single order:
 * <pre>
 *   beginProcessing          (own transaction - PROCESSING becomes visible on the dashboard)
 *        |
 *   reserveStockForOrder     (own transaction - order row + inventory row locked, stock decremented)
 *        |
 *   ├── COMPLETED / OUT_OF_STOCK  -> terminal, stop
 *   └── transient fault           -> registerTransientFailure
 *                                     ├── budget left -> re-submit with backoff (bounded retry)
 *                                     └── budget gone -> DLQ (ORDER_DLQ)
 * </pre>
 *
 * <h2>Business versus technical failures</h2>
 * Only {@link TransientProcessingException} and Spring's own {@link TransientDataAccessException}
 * (lock timeouts, deadlock victims, dropped connections) are retried. A
 * {@link BusinessRuleViolationException} is terminal and is never retried - retrying it would only
 * consume worker capacity. It is not simply dropped either: the order is parked in the dead-letter
 * queue with its reason, because leaving it in {@code PROCESSING} would strand it in a state no worker
 * is allowed to pick up, so the dashboard could never show it and an operator could never fix it.
 * An {@link OutOfStockException} is the one business outcome that is *not* a failure: it settles the
 * order as {@code OUT_OF_STOCK} and never reaches the dead-letter queue.
 */
@Service
public class OrderProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessingService.class);

    private final OrderLifecycleService orderLifecycleService;
    private final InventoryReservationService inventoryReservationService;
    private final OrderProcessingProperties processingProperties;
    private final ObjectProvider<OrderTransport> orderTransport;

    public OrderProcessingService(OrderLifecycleService orderLifecycleService,
                                 InventoryReservationService inventoryReservationService,
                                 OrderProcessingProperties processingProperties,
                                 ObjectProvider<OrderTransport> orderTransport) {
        this.orderLifecycleService = orderLifecycleService;
        this.inventoryReservationService = inventoryReservationService;
        this.processingProperties = processingProperties;
        // Resolved lazily on purpose: the transport implementation needs this service to run work, so
        // injecting it eagerly would create a constructor cycle between the two beans.
        this.orderTransport = orderTransport;
    }

    /**
     * Processes a single order. Safe to call more than once for the same order id: every stage
     * re-checks the persisted status before doing anything irreversible.
     */
    public OrderProcessingResult processOrder(Long orderId) {
        try {
            if (!orderLifecycleService.beginProcessing(orderId)) {
                return OrderProcessingResult.skipped(orderId, null);
            }

            OrderProcessingResult result = inventoryReservationService.reserveStockForOrder(orderId);

            if (result.outcome() == OrderProcessingResult.Outcome.OUT_OF_STOCK) {
                log.info("ORDER_SETTLED orderId={} status=OUT_OF_STOCK", orderId);
            }
            return result;

        } catch (OutOfStockException outOfStock) {
            // Defensive path: the reservation service normally records OUT_OF_STOCK and returns a result
            // rather than throwing. If it ever surfaces as an exception the order must still settle as a
            // business rejection - never retried, never dead-lettered.
            log.warn("ORDER_REJECTED orderId={} reason={}", orderId, outOfStock.getMessage());
            return orderLifecycleService.settleOutOfStock(orderId, outOfStock.getMessage());

        } catch (BusinessRuleViolationException businessFailure) {
            // Terminal, not retryable, and not fixable without a human (for example a product with no
            // inventory row). Logging alone would leave the order parked in PROCESSING for ever - a state
            // no worker may pick up again - so it is parked in the dead-letter queue instead, where the
            // dashboard shows the reason and an operator can replay it after fixing the data.
            log.error("ORDER_DEAD_LETTERED orderId={} reason={}", orderId, businessFailure.getMessage());
            return orderLifecycleService.rejectToDeadLetter(orderId, businessFailure.getMessage());

        } catch (TransientProcessingException | TransientDataAccessException transientFailure) {
            return handleTransientFailure(orderId, transientFailure);

        } catch (RuntimeException unexpected) {
            // Unknown faults are treated as transient so they get a bounded number of attempts and then
            // land in the DLQ with a reason, rather than disappearing silently.
            log.error("ORDER_UNEXPECTED_FAILURE orderId={}", orderId, unexpected);
            return handleTransientFailure(orderId, unexpected);
        }
    }

    private OrderProcessingResult handleTransientFailure(Long orderId, Exception failure) {
        String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        OrderProcessingResult result = orderLifecycleService.registerTransientFailure(orderId, reason);

        if (result.outcome() == OrderProcessingResult.Outcome.RETRYABLE_FAILURE) {
            long backoff = processingProperties.getRetryBackoffMs() * Math.max(1, result.retryCount());
            OrderTransport transport = orderTransport.getIfAvailable();
            if (transport != null) {
                transport.submitForRetry(orderId, backoff);
            }
        }
        return result;
    }
}
