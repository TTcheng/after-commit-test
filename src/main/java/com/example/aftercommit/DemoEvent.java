package com.example.aftercommit;

/**
 * Plain application event published from inside the @Transactional method.
 */
public class DemoEvent {

    private final String name;

    public DemoEvent(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "DemoEvent{" + name + '}';
    }
}