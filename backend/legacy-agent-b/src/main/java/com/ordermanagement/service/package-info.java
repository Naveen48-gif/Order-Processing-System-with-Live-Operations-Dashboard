/**
 * Business services: order lifecycle, inventory reservation, retry policy, dead-letter handling.
 *
 * <p>Rules for this package:
 * <ul>
 *   <li>Transactional boundaries live here. The inventory check-and-decrement runs inside a single
 *       short transaction holding a pessimistic row lock.</li>
 *   <li>Business failures (insufficient stock) are separated from technical failures (transient I/O,
 *       broker or database hiccups) because only technical failures are retried.</li>
 *   <li>Services publish dashboard events after the transaction commits, never before.</li>
 * </ul>
 */
package com.ordermanagement.service;
