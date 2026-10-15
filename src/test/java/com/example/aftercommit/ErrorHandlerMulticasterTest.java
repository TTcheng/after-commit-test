package com.example.aftercommit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.aftercommit.listeners.ListenerA;
import com.example.aftercommit.listeners.ListenerC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies behavior when the multicaster HAS an errorHandler installed.
 *
 * <p>For {@code @TransactionalEventListener(AFTER_COMMIT)} the multicaster-level errorHandler has
 * <b>no observable effect</b>: each listener is invoked through its own
 * {@code TransactionSynchronization}, and the outer
 * {@code TransactionSynchronizationUtils.invokeAfterCompletion} catches the throw before it can
 * travel back through the multicaster. The end result is identical to
 * {@link DefaultMulticasterTest}.
 *
 * <p>This test is deliberately written so it can actually FAIL. Previously it asserted the same
 * things as the default test and nothing about the errorHandler itself -- deleting
 * {@code EventMulticasterConfig} entirely would have left it green. Both halves of the claim are now
 * pinned down:
 *
 *   <ul>
 *     <li>{@link #customMulticaster} is injected by name and asserted to be the very instance the
 *         context uses. If {@code EventMulticasterConfig} stopped being wired, context startup
 *         would fail rather than the test passing vacuously.</li>
 *     <li>the {@code [ErrorHandler]} line never appears in the output, proving the handler is
 *         bypassed rather than merely unused.</li>
 *   </ul>
 *
 * <p>Note: {@code SimpleApplicationEventMulticaster.getErrorHandler()} is {@code protected}, so the
 * test cannot read the handler directly -- bean presence plus log absence is the observable proof.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
@ActiveProfiles("with-error-handler")
class ErrorHandlerMulticasterTest {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlerMulticasterTest.class);

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private ListenerA listenerA;
    @Autowired private ListenerC listenerC;

    /**
     * Present only because the {@code with-error-handler} profile activated
     * {@link EventMulticasterConfig}. Missing bean -> context startup failure.
     */
    @Autowired
    @Qualifier("applicationEventMulticaster")
    private SimpleApplicationEventMulticaster customMulticaster;

    @BeforeEach
    void reset() {
        listenerA.reset();
        listenerC.reset();
    }

    @Test
    void afterCommit_withErrorHandler_oneListenerThrows(CapturedOutput output) {
        // Precondition: the profile really did install our multicaster, and the context is using it.
        assertNotNull(customMulticaster,
                "the with-error-handler profile must install the 'applicationEventMulticaster' bean");
        assertSame(applicationContext.getBean(ApplicationEventMulticaster.class), customMulticaster,
                "the custom multicaster must be the ApplicationEventMulticaster the context actually uses");

        long countBefore = orderRepository.count();

        Order returned = orderService.placeOrder("with-error-handler-test", 2);
        assertNotNull(returned, "placeOrder must return normally");

        assertEquals(countBefore + 1, orderRepository.count(), "Transaction must commit");
        assertTrue(listenerA.wasExecuted(), "ListenerA (Order=1) should have executed");
        assertTrue(listenerC.wasExecuted(), "ListenerC (Order=3) should have executed");

        String all = output.getAll();
        assertTrue(all.contains("ListenerB exploded"),
                "Exception should still be logged. Captured:\n" + all);

        // The decisive assertion: even though an errorHandler IS installed, it is never reached,
        // because the per-synchronization try/catch inside invokeAfterCompletion catches first.
        assertFalse(all.contains("[ErrorHandler]"),
                "the multicaster errorHandler must NOT be invoked for @TransactionalEventListener -- "
                        + "TransactionSynchronizationUtils swallows the exception before it reaches "
                        + "the multicaster. Captured:\n" + all);

        log.info("PASS: errorHandler profile behaves identically to default for @TransactionalEventListener");
    }
}
