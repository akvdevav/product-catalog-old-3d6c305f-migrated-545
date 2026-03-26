package com.example.productcatalog.messaging;

import com.example.productcatalog.controller.ProductEventSseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final ProductEventSseController sseController;

    public ProductEventListener(ProductEventSseController sseController) {
        this.sseController = sseController;
    }

    @RabbitListener(queuesToDeclare = @org.springframework.amqp.rabbit.annotation.Queue(name = "product-events"))
    public void onProductEvent(String message) {
        log.info("Received product event: {}", message);
        sseController.broadcast(message);
    }
}