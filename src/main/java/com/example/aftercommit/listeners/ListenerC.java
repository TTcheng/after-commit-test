package com.example.aftercommit.listeners;

import com.example.aftercommit.DemoEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Last listener (Order=3). Will it run after ListenerB throws?
 * Default SimpleApplicationEventMulticaster (no errorHandler) -> NO.
 * With errorHandler configured -> YES.
 */
@Component
public class ListenerC {

    private final AtomicBoolean executed = new AtomicBoolean(false);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Order(3)
    public void handle(DemoEvent event) {
        System.out.println("[ListenerC] START event=" + event.getName());
        executed.set(true);
        System.out.println("[ListenerC] DONE event=" + event.getName());
    }

    public boolean wasExecuted() {
        return executed.get();
    }

    public void reset() {
        executed.set(false);
    }
}