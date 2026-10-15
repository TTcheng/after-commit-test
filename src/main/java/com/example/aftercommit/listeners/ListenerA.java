package com.example.aftercommit.listeners;

import com.example.aftercommit.DemoEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * First listener (Order=1). Runs before ListenerB.
 *
 * <p>Runs on the <b>same thread</b> that committed the transaction, synchronously inside
 * {@code processCommit}. By the time the {@code @Transactional} method returns, every AFTER_COMMIT
 * listener has already finished -- no sleeping or polling is needed in tests to observe them.
 */
@Component
public class ListenerA {

    private static final Logger log = LoggerFactory.getLogger(ListenerA.class);

    private final AtomicBoolean executed = new AtomicBoolean(false);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Order(1)
    public void handle(DemoEvent event) {
        log.info("[ListenerA] START event={} (transaction already committed)", event.getName());
        executed.set(true);
        log.info("[ListenerA] DONE event={}", event.getName());
    }

    public boolean wasExecuted() {
        return executed.get();
    }

    public void reset() {
        executed.set(false);
    }
}
