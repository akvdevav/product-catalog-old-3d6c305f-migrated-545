package com.example.productcatalog.messaging;

import com.example.productcatalog.model.Product;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProductEventPublisher {

    private static final String QUEUE = "product-events";
    private final JmsTemplate jmsTemplate;

    public ProductEventPublisher(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void publishCreated(Product product) {
        jmsTemplate.convertAndSend(QUEUE, "CREATED:" + product.getId() + ":" + product.getName());
    }

    public void publishDeleted(Long id) {
        jmsTemplate.convertAndSend(QUEUE, "DELETED:" + id);
    }
}
