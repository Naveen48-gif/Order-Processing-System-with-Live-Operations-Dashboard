package com.ordermanagement.repository;

import com.ordermanagement.entity.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * Loads the inventory row for a product while holding a database write lock on it.
     *
     * <p>With {@code @Lock(PESSIMISTIC_WRITE)} Hibernate issues {@code SELECT ... FOR UPDATE}, which
     * on PostgreSQL and on MySQL/InnoDB means: any concurrent transaction touching the same row blocks
     * until this transaction commits. That serialisation is exactly what stops two workers from both
     * reading {@code quantity = 1} and both selling it.
     *
     * <p>Why a JVM lock is not enough: {@code synchronized} or a {@code ReentrantLock} only guards the
     * threads of one JVM. The moment a second application instance runs (scale-out behind a load
     * balancer, or a rolling deploy with both versions live), each process holds its own monitor while
     * both hit the same database row - the stock would be oversold with a perfectly "synchronised"
     * codebase. The database is the only shared point of truth, so the lock must live there.
     *
     * <p>The lock is deliberately taken on the {@code inventory} row only (no join to {@code product}):
     * a narrower lock means less contention, and the parent product row is not being mutated here.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.product.id = :productId")
    Optional<Inventory> findForUpdateByProductId(@Param("productId") Long productId);

    /** Unlocked read used by dashboards and API queries (never by the reservation path). */
    Optional<Inventory> findByProductId(Long productId);

    /** Dashboard listing with the product eagerly fetched to avoid an N+1 query per row. */
    @Query("select i from Inventory i join fetch i.product order by i.product.name asc")
    List<Inventory> findAllWithProduct();

    boolean existsByProductId(Long productId);
}
