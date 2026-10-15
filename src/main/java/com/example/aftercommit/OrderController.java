package com.example.aftercommit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /orders?product=...&quantity=...}
 *
 * <p>The try/catch below will NOT catch exceptions thrown by AFTER_COMMIT listeners: Spring has
 * already swallowed them (inside {@code processCommit}) and only logged them at ERROR. This catch
 * still matters for ordinary failures -- constraint violations, validation, downstream errors --
 * which do surface here and correctly produce a 500.
 *
 * <p>The two paths are covered separately:
 * <ul>
 *   <li>{@code OrderControllerWebTest} -- AFTER_COMMIT listener throws -> still HTTP 200.</li>
 *   <li>{@code OrderControllerWebTest#badRequestIsNotSwallowed} -> request-level failure -> HTTP 400.</li>
 * </ul>
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestParam String product, @RequestParam int quantity) {
        try {
            Order o = orderService.placeOrder(product, quantity);
            log.info("[Controller] returning 200 for order id={}", o.getId());
            return ResponseEntity.ok(o);
        } catch (Exception e) {
            // AFTER_COMMIT listener exceptions never reach here -- see the class javadoc.
            log.error("[Controller] caught non-listener exception, returning 500", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}
