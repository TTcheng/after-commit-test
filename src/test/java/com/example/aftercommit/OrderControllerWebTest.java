package com.example.aftercommit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Closes the last gap in the README's claim.
 *
 * <p>The other two tests call {@code OrderService} directly, so "the request still returns 200" was
 * only ever <i>inferred</i> from {@code placeOrder()} returning normally -- {@code OrderController}
 * itself was never exercised. This test drives the real HTTP layer end to end.
 *
 * <p>Note the contrast between the two cases here:
 * <ul>
 *   <li>an AFTER_COMMIT listener throws -> the controller's try/catch never sees it -> <b>200</b>;</li>
 *   <li>the request itself is invalid -> rejected before/inside the controller -> <b>400</b>.</li>
 * </ul>
 * Only the first one is silently swallowed, which is precisely the trap this project documents.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderRepository orderRepository;

    @Test
    void afterCommitListenerThrows_requestStillReturns200(CapturedOutput output) throws Exception {
        long countBefore = orderRepository.count();

        mockMvc.perform(post("/orders").param("product", "web-test").param("quantity", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.product").value("web-test"))
                .andExpect(jsonPath("$.quantity").value(3));

        // The order really was committed, so a 200 here is not hiding a rolled-back transaction.
        assertEquals(countBefore + 1, orderRepository.count(),
                "the row must be persisted -- AFTER_COMMIT fires post-commit and cannot roll back");

        // ...and the whole listener chain really did run during that same request.
        String all = output.getAll();
        assertTrue(all.contains("[ListenerA]"), "ListenerA should have run during the request");
        assertTrue(all.contains("ListenerB]"), "ListenerB should have run during the request");
        assertTrue(all.contains("[ListenerC]"), "ListenerC should have run despite ListenerB throwing");
        assertTrue(all.contains("TransactionSynchronization.afterCompletion threw exception"),
                "the listener failure was swallowed and logged, not propagated");
    }

    @Test
    void invalidRequestIsNotSwallowed() throws Exception {
        // A request-level failure behaves normally (400). Contrast with the listener case above,
        // which is the only one that gets swallowed.
        mockMvc.perform(post("/orders").param("quantity", "1"))
                .andExpect(status().isBadRequest());
    }
}
