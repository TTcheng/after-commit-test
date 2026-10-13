package com.example.aftercommit.listeners;

import com.example.aftercommit.DemoEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * First listener (Order=1). Should execute before ListenerB.
 */
@Component
public class ListenerA {

    private final AtomicBoolean executed = new AtomicBoolean(false);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Order(1)
    public void handle(DemoEvent event) {
        System.out.println("[ListenerA] BEFORE_COMMIT? phase=AFTER_COMMIT, event=" + event.getName());
        executed.set(true);
        System.out.println("[ListenerA] DONE event=" + event.getName());
    }

    public boolean wasExecuted() {
        return executed.get();
    }

    public void reset() {
        executed.set(false);
    }
}