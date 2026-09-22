/**
 * Cross-cutting Spring configuration: bounded thread pool, broker topology properties, WebSocket
 * broker setup, CORS for the dashboard, and Jackson/clock beans.
 *
 * <p>Rule for this package: configuration classes only. No business logic, so the ordering rules
 * stay testable outside the framework.
 */
package com.ordermanagement.config;
