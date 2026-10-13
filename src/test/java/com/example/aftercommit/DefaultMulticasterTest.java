package com.example.aftercommit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.aftercommit.listeners.ListenerA;
import com.example.aftercommit.listeners.ListenerB;
import com.example.aftercommit.listeners.ListenerC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the DEFAULT behavior of Spring's transaction synchronization when no
 * errorHandler is configured.
 *
 * Key empirical facts about @TransactionalEventListener(AFTER_COMMIT) when a listener throws:
 *
 *   1. The transaction has ALREADY committed (the order row is persisted).
 *   2. The exception thrown by ListenerB is SWALLOWED -- logged at ERROR by
 *      TransactionSynchronizationUtils and never propagated.
 *      => placeOrder() returns NORMALLY.
 *      => The HTTP request returns 200 (not 500).
 *   3. CRUCIAL: each @TransactionalEventListener method gets its OWN
 *      TransactionSynchronization. The outer invokeAfterCompletion wraps each
 *      synchronization in a try/catch, so one listener throwing does NOT cascade.
 *      => ListenerC (after B) DOES execute, even under default behavior.
 *   4. The exception is captured and logged by Spring (TransactionSynchronizationUtils).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
class DefaultMulticasterTest {

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ListenerA listenerA;
    @Autowired private ListenerB listenerB;
    @Autowired private ListenerC listenerC;

    @BeforeEach
    void reset() {
        listenerA.reset();
        listenerC.reset();
    }

    @Test
    void afterCommit_defaultBehavior_oneListenerThrows(CapturedOutput output) throws Exception {
        long countBefore = orderRepository.count();

        // placeOrder returns normally -- exception is swallowed.
        Order returned = orderService.placeOrder("default-test", 1);
        assertNotNull(returned, "placeOrder must return normally -- AFTER_COMMIT exceptions are swallowed");
        assertNotNull(returned.getId());

        Thread.sleep(300);

        // 1) Transaction is COMMITTED (data persisted)
        assertEquals(countBefore + 1, orderRepository.count(),
                "Transaction should already be committed; order row must be persisted");

        // 2) ListenerA ran (before B)
        assertTrue(listenerA.wasExecuted(), "ListenerA (Order=1) should have executed");

        // 3) ListenerC ALSO ran -- each @TransactionalEventListener has its OWN
        //    TransactionSynchronization; one throwing does not block the others.
        assertTrue(listenerC.wasExecuted(),
                "ListenerC (Order=3) DOES execute even after ListenerB throws, because each " +
                "@TransactionalEventListener has its own TransactionSynchronization, and the " +
                "outer invokeAfterCompletion wraps each in try/catch.");

        // 4) The exception was swallowed and logged -- find it in the captured log output
        String all = output.getAll();
        assertTrue(all.contains("ListenerB exploded"),
                "The exception from ListenerB must have been logged (swallowed by Spring). Captured:\n" + all);
        assertTrue(all.contains("TransactionSynchronization.afterCompletion threw exception"),
                "Spring's TransactionSynchronizationUtils must have logged the swallowed exception.");

        System.out.println("[DefaultMulticasterTest] PASS: default behavior confirmed " +
                "(exception swallowed, ALL listeners ran, data persisted)");
    }
}