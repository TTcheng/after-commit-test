package com.example.aftercommit.listeners;

import com.example.aftercommit.DemoEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Middle listener (Order=2). Intentionally throws to verify what happens.
 */
@Component
public class ListenerB {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Order(2)
    public void handle(DemoEvent event) {
        System.out.println("[ListenerB] START event=" + event.getName() + " -- about to throw");
        throw new RuntimeException("ListenerB exploded on " + event.getName());
    }
}