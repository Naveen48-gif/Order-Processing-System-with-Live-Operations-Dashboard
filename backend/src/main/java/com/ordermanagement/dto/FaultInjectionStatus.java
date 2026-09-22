package com.ordermanagement.dto;

import java.util.Map;

/**
 * Answer to "is the failure drill available, and what is currently armed?".
 *
 * <p>The dashboard calls this once on load: a missing endpoint (404) means the capability is switched
 * off, and the drill controls are hidden instead of being rendered as buttons that cannot work.
 *
 * @param faultInjection always {@code true} while the endpoint exists, so the client has one stable
 *                       field to branch on
 * @param armedFaults    remaining injected failures per product id
 */
public record FaultInjectionStatus(
        boolean faultInjection,
        Map<Long, Integer> armedFaults
) {
}