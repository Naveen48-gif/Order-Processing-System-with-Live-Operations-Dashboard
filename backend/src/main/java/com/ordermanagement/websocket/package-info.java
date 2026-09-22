/**
 * Real-time push layer (STOMP over WebSocket) that feeds the operations dashboard.
 *
 * <p>Design rule: events are pushed <em>after</em> the database transaction commits. Publishing
 * before commit would let the dashboard display a state that a rollback then erases, which is exactly
 * the kind of inconsistency an operations screen must never show.
 */
package com.ordermanagement.websocket;
