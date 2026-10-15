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
 * Last listener (Order=3). The whole point of this project: does it run after ListenerB throws?
 *
 * <p><b>Answer: YES.</b> (The earlier version of this comment claimed "NO without an errorHandler" --
 * that was disproven by the tests; see README "重要事实更正".)
 *
 * <p>Each {@code @TransactionalEventListener} method gets its own
 * {@code TransactionalApplicationListenerSynchronization}, and
 * {@code TransactionSynchronizationUtils.invokeAfterCompletion} wraps every individual
 * {@code afterCompletion} call in its own try/catch. One listener throwing cannot block the next.
 *
 * <p>This holds <b>with or without</b> a multicaster errorHandler -- the errorHandler is bypassed on
 * this path entirely, because the exception never travels back through the multicaster.
 */
@Component
public class ListenerC {

    private static final Logger log = LoggerFactory.getLogger(ListenerC.class);

    private final AtomicBoolean executed = new AtomicBoolean(false);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Order(3)
    public void handle(DemoEvent event) {
        log.info("[ListenerC] START event={}", event.getName());
        executed.set(true);
        log.info("[ListenerC] DONE event={}", event.getName());
    }

    public boolean wasExecuted() {
        return executed.get();
    }

    public void reset() {
        executed.set(false);
    }
}
