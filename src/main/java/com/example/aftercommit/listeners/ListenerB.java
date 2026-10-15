package com.example.aftercommit.listeners;

import com.example.aftercommit.DemoEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Middle listener (Order=2). Intentionally throws, to observe what the framework does with it.
 *
 * <p>Nothing catches this on the way out: {@code TransactionSynchronizationUtils.invokeAfterCompletion}
 * catches {@code Throwable} per synchronization, logs it at ERROR, and continues the loop. So
 * ListenerC still runs and the caller still sees a normal return.
 */
@Component
public class ListenerB {

    private static final Logger log = LoggerFactory.getLogger(ListenerB.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Order(2)
    public void handle(DemoEvent event) {
        RuntimeException failure = new RuntimeException("ListenerB exploded on " + event.getName());
        log.error("[ListenerB] START event={} -- about to throw", event.getName(), failure);
        throw failure;
    }
}
