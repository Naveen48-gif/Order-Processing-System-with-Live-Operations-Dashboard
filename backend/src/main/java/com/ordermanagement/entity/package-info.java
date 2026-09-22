/**
 * JPA entities: Product, Inventory, Order (plus the shared auditing base class).
 *
 * <p>Rules for this package:
 * <ul>
 *   <li>The database is the source of truth. The {@code inventory.quantity >= 0} CHECK constraint is
 *       declared here and duplicated in SQL initialisation scripts - application validation is the
 *       first line of defence, the constraint is the last one.</li>
 *   <li>Inventory carries a {@code @Version} column for optimistic conflict detection on top of the
 *       pessimistic lock used by the reservation path.</li>
 *   <li>Entities are internal. They are never serialised to a REST response.</li>
 * </ul>
 */
package com.ordermanagement.entity;
