// JSDoc typedefs mirroring the DTOs frozen in AGENT.md §4 — kept in sync manually since this is a
// plain-JS Vite project (no TypeScript compiler to enforce it).

/**
 * @typedef {'PENDING'|'PROCESSING'|'COMPLETED'|'OUT_OF_STOCK'|'FAILED'|'DLQ'} OrderStatus
 */

/**
 * @typedef {object} OrderResponse
 * @property {number} id
 * @property {number} productId
 * @property {string} productName
 * @property {number} quantity
 * @property {OrderStatus} status
 * @property {number} retryCount
 * @property {string|null} failureReason
 * @property {string} createdAt
 * @property {string} updatedAt
 */

/**
 * @typedef {'AVAILABLE'|'LOW_STOCK'|'OUT_OF_STOCK'} StockStatus
 */

/**
 * @typedef {object} InventoryResponse
 * @property {number} productId
 * @property {string} productName
 * @property {number} quantity
 * @property {StockStatus} stockStatus
 */

/**
 * @typedef {object} ProductResponse
 * @property {number} id
 * @property {string} name
 * @property {number} price
 * @property {string} createdAt
 */

/**
 * @typedef {object} DashboardSummaryResponse
 * @property {number} totalOrders
 * @property {number} pending
 * @property {number} processing
 * @property {number} completed
 * @property {number} outOfStock
 * @property {number} failed
 * @property {number} dlq
 * @property {InventoryResponse[]} inventory
 */

/**
 * @typedef {object} DlqOrderResponse
 * @property {number} orderId
 * @property {number} productId
 * @property {number} quantity
 * @property {number} retryCount
 * @property {string} failureReason
 * @property {string} deadLetteredAt
 */

export {};
