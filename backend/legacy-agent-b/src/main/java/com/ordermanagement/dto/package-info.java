/**
 * Request/response DTOs (Java records) for the REST API and the WebSocket feed.
 *
 * <p>DTOs exist so the wire contract is explicit and stable: entity changes must never silently
 * change an API response, and clients never depend on persistence details such as database ids of
 * internal tables or lazy associations.
 */
package com.ordermanagement.dto;
