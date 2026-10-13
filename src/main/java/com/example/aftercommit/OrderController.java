package com.example.aftercommit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Default behavior note:
 *
 * The try-catch here will NOT catch exceptions thrown by AFTER_COMMIT listeners --
 * those are swallowed by Spring at the TransactionSynchronization level (logged only).
 * This catch is still useful for non-listener exceptions (DB errors, validation, etc.).
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestParam String product, @RequestParam int quantity) {
        try {
            Order o = orderService.placeOrder(product, quantity);
            System.out.println("[Controller] returned OK for order id=" + o.getId());
            return ResponseEntity.ok(o);
        } catch (Exception e) {
            // This branch triggers for non-listener exceptions. AFTER_COMMIT listener
            // exceptions are swallowed by Spring and never reach here.
            System.out.println("[Controller] caught non-listener exception: " + e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}