package com.example.aftercommit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.aftercommit.listeners.ListenerA;
import com.example.aftercommit.listeners.ListenerC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies behavior when the multicaster has an errorHandler installed.
 *
 * Note: for @TransactionalEventListener(AFTER_COMMIT), the multicaster-level errorHandler
 * has effectively NO observable effect, because each listener is invoked through its own
 * TransactionSynchronization, and the OUTER TransactionSynchronizationUtils.invokeAfterCompletion
 * already wraps each call in try/catch. The end result is identical to the default case:
 *
 *   1. Exception thrown by ListenerB is swallowed (logged by TransactionSynchronizationUtils).
 *   2. placeOrder() returns normally.
 *   3. All listeners run, including ListenerC (each listener has independent isolation).
 *   4. Transaction commits.
 *
 * Compare this to DefaultMulticasterTest -- the only difference is that this test verifies
 * the application boots with a custom SimpleApplicationEventMulticaster bean (the @Profile
 * gate is the actual assertion target).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
@ActiveProfiles("with-error-handler")
class ErrorHandlerMulticasterTest {

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
    void afterCommit_withErrorHandler_oneListenerThrows(CapturedOutput output) throws Exception {
        long countBefore = orderRepository.count();

        Order returned = orderService.placeOrder("with-error-handler-test", 2);
        assertNotNull(returned, "placeOrder must return normally");

        Thread.sleep(300);

        assertTrue(listenerA.wasExecuted(), "ListenerA (Order=1) should have executed");
        assertTrue(listenerC.wasExecuted(),
                "ListenerC (Order=3) should have executed -- errorHandler scenario is the same");
        assertEquals(countBefore + 1, orderRepository.count(), "Transaction must commit");

        // Even with errorHandler, the actual log line that fires is from Spring's
        // TransactionSynchronizationUtils -- the multicaster's errorHandler is bypassed
        // because the per-listener synchronization catches first.
        String all = output.getAll();
        assertTrue(all.contains("ListenerB exploded"),
                "Exception should still be logged. Captured:\n" + all);

        System.out.println("[ErrorHandlerMulticasterTest] PASS: errorHandler profile behaves identically " +
                "to default for @TransactionalEventListener");
    }
}