/**
 * REST entry points. Controllers translate HTTP to service calls and back to DTOs.
 *
 * <p>Rules for this package:
 * <ul>
 *   <li>Never return or accept JPA entities - DTOs only, so the persistence model can evolve.</li>
 *   <li>Never contain business rules: validation annotations plus delegation to a service.</li>
 *   <li>Return meaningful status codes (201 on create, 409 on invalid state transitions,
 *       422/400 for business rejections, 404 for missing resources).</li>
 * </ul>
 */
package com.ordermanagement.controller;
