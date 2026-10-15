package com.example.aftercommit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.aftercommit.listeners.ListenerA;
import com.example.aftercommit.listeners.ListenerC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the DEFAULT behavior of Spring's transaction synchronization when no errorHandler is
 * configured.
 *
 * <p>Empirical facts about {@code @TransactionalEventListener(AFTER_COMMIT)} when one listener throws:
 *
 *   <ol>
 *     <li>The transaction has ALREADY committed (the order row is persisted and cannot be rolled back).</li>
 *     <li>The exception is SWALLOWED -- logged at ERROR by {@code TransactionSynchronizationUtils}
 *         and never propagated. So {@code placeOrder()} returns normally.</li>
 *     <li>Each {@code @TransactionalEventListener} method gets its OWN
 *         {@code TransactionSynchronization}, and {@code invokeAfterCompletion} wraps every
 *         individual {@code afterCompletion} call in its own try/catch -- so one listener throwing
 *         does not block the others. ListenerC DOES run.</li>
 *   </ol>
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
class DefaultMulticasterTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultMulticasterTest.class);

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ListenerA listenerA;
    @Autowired private ListenerC listenerC;

    @BeforeEach
    void reset() {
        listenerA.reset();
        listenerC.reset();
    }

    @Test
    void afterCommit_defaultBehavior_oneListenerThrows(CapturedOutput output) {
        long countBefore = orderRepository.count();

        // placeOrder returns normally -- the listener exception is swallowed.
        Order returned = orderService.placeOrder("default-test", 1);
        assertNotNull(returned, "placeOrder must return normally -- AFTER_COMMIT exceptions are swallowed");
        assertNotNull(returned.getId());

        // 1) Transaction is COMMITTED (data persisted).
        assertEquals(countBefore + 1, orderRepository.count(),
                "Transaction should already be committed; order row must be persisted");

        // 2) Both surviving listeners had ALREADY run by the time placeOrder() returned.
        //    No sleep or polling: this assertion is itself the proof that AFTER_COMMIT listeners
        //    execute synchronously on the committing thread inside processCommit, and are therefore
        //    fully complete before control returns to the caller.
        assertTrue(listenerA.wasExecuted(), "ListenerA (Order=1) should have executed");
        assertTrue(listenerC.wasExecuted(),
                "ListenerC (Order=3) DOES execute even after ListenerB throws, because each "
                        + "@TransactionalEventListener has its own TransactionSynchronization, and the "
                        + "outer invokeAfterCompletion wraps each in try/catch.");

        // 3) The exception was swallowed and logged -- find it in the captured log output.
        String all = output.getAll();
        assertTrue(all.contains("ListenerB exploded"),
                "The exception from ListenerB must have been logged (swallowed by Spring). Captured:\n" + all);
        assertTrue(all.contains("TransactionSynchronization.afterCompletion threw exception"),
                "Spring's TransactionSynchronizationUtils must have logged the swallowed exception.");

        log.info("PASS: default behavior confirmed (exception swallowed, ALL listeners ran, data persisted)");
    }
}
