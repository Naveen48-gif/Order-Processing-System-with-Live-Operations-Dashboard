/**
 * Concurrency infrastructure: the bounded thread pool and its executor bean.
 *
 * <p>The pool is intentionally conservative about rejection: once the bounded queue is full, the
 * submission is rejected (with a caller-side signal) rather than growing the pool indefinitely or
 * buffering without limit. That converts overload into a visible, bounded failure instead of a
 * slow-motion outage.
 *
 * <p>Note: a Java-level {@code synchronized} block or {@code ReentrantLock} protects a single JVM
 * only. With multiple application instances behind a load balancer, each instance holds its own
 * monitor and the same stock row could still be decremented twice. Correctness therefore comes from
 * the database row lock inside the transaction, not from JVM-level mutual exclusion.
 */
package com.ordermanagement.concurrency;
