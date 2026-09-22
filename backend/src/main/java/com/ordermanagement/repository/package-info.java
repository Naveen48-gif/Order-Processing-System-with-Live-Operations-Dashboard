/**
 * Spring Data JPA repositories. Locking is declared here so it is impossible to forget it:
 * the inventory repository exposes a dedicated "find for update" method annotated with
 * {@code @Lock(LockModeType.PESSIMISTIC_WRITE)}, which translates to {@code SELECT ... FOR UPDATE}
 * on PostgreSQL and to an InnoDB row lock on MySQL.
 *
 * <p>Rules for this package: derived queries or JPQL only - no business logic, and money/stock
 * mutations must go through the locked read-modify-write path.
 */
package com.ordermanagement.repository;
