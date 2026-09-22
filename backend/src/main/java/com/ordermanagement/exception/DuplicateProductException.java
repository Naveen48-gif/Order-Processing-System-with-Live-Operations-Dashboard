package com.ordermanagement.exception;

/** A product with the same name already exists (the catalogue keeps names unique). */
public class DuplicateProductException extends BusinessRuleViolationException {

    private final String name;

    public DuplicateProductException(String name) {
        super("A product named '%s' already exists".formatted(name));
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
