package com.example.aftercommit;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The transactional method that commits the change and publishes the event.
     * AFTER_COMMIT listeners fire AFTER this method returns successfully.
     */
    @Transactional
    public Order placeOrder(String product, int quantity) {
        Order order = new Order(product, quantity);
        Order saved = orderRepository.save(order);
        System.out.println("[Service] committed-order id=" + saved.getId() + ", publishing event " + saved.getId());
        eventPublisher.publishEvent(new DemoEvent("order-" + saved.getId()));
        System.out.println("[Service] publishEvent returned, exiting @Transactional method");
        return saved;
    }
}