package com.ordermanagement.event;

import com.ordermanagement.dto.InventoryResponse;

/** Published whenever a product's stock level changes, for the after-commit STOMP broadcast. */
public record InventoryChangedEvent(InventoryResponse inventory) {
}
