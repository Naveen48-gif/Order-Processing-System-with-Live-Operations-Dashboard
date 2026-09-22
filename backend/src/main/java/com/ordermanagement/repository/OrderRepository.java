package com.ordermanagement.repository;

import com.ordermanagement.entity.Order;
import com.ordermanagement.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Idempotency lookup: lets a replayed submission return the original order instead of creating a
     * duplicate that would reserve stock a second time.
     */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /** Single-row fetch for API responses; the product is fetched eagerly to build the DTO in one query. */
    @Query("select o from Order o join fetch o.product where o.id = :id")
    Optional<Order> findByIdWithProduct(@Param("id") Long id);

    /**
     * Locks the order row for the reservation transaction.
     *
     * <p>Two things depend on this lock:
     * <ol>
     *   <li><strong>Idempotency.</strong> If the same order is delivered twice (broker redelivery, an
     *       operator replaying a dead letter, a retry racing the original attempt), the second worker
     *       blocks until the first commits and then sees a terminal status instead of reserving stock a
     *       second time.</li>
     *   <li><strong>Short, ordered lock chain.</strong> Every worker takes the order row first and the
     *       inventory row second, in the same order, which is what keeps deadlocks out of the picture.</li>
     * </ol>
     *
     * <p>The product association is deliberately not fetched: only its identifier is needed, and
     * reading that from the lazy proxy costs no query, whereas a join would extend the lock to the
     * product row for no benefit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /** Live orders table feed, most recently updated first. */
    @Query("select o from Order o join fetch o.product order by o.updatedAt desc")
    List<Order> findRecentWithProduct(Pageable pageable);

    List<Order> findByStatusOrderByCreatedAtAsc(OrderStatus status);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    @Query("select o from Order o join fetch o.product where o.status = :status order by o.updatedAt desc")
    List<Order> findByStatusWithProduct(@Param("status") OrderStatus status);

    long countByStatus(OrderStatus status);

    /**
     * Product-scoped counters: let a test (or an operator drill-down) reason about one product without
     * depending on unrelated rows elsewhere in the table.
     */
    long countByProduct_IdAndStatus(Long productId, OrderStatus status);

    long countByProduct_Id(Long productId);

    /** One grouped query behind all dashboard counters, instead of loading orders into memory. */
    @Query("select o.status as status, count(o) as total from Order o group by o.status")
    List<StatusCountView> countGroupedByStatus();

    /** Candidate orders for the admin replay flow. */
    List<Order> findByStatusAndRetryCountGreaterThanEqualOrderByUpdatedAtAsc(OrderStatus status, int retryCount);
}
