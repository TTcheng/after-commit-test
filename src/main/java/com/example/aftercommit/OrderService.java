package com.example.aftercommit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Commits the change and publishes the event.
     *
     * <p><b>Timing note:</b> {@code publishEvent} does <b>not</b> run the AFTER_COMMIT listeners.
     * At this point each listener only <i>registers</i> a
     * {@code TransactionalApplicationListenerSynchronization}. The actual invocations happen later,
     * when the {@code @Transactional} proxy commits -- i.e. after this method returns.
     *
     * <p>The id is already populated here because {@code GenerationType.IDENTITY} forces an immediate
     * INSERT, but the row is <b>still uncommitted</b> and could still roll back at this point.
     */
    @Transactional
    public Order placeOrder(String product, int quantity) {
        Order order = new Order(product, quantity);
        Order saved = orderRepository.save(order);
        log.info("[Service] saved (NOT yet committed) order id={}, publishing event {}", saved.getId(), saved.getId());
        eventPublisher.publishEvent(new DemoEvent("order-" + saved.getId()));
        log.info("[Service] publishEvent returned -- listeners only registered, commit still pending");
        return saved;
    }
}
