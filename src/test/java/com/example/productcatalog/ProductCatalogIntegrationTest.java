package com.example.productcatalog;

import com.example.productcatalog.model.Product;
import com.example.productcatalog.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductCatalogIntegrationTest {

    @Autowired
    ProductService service;

    @Test
    void createReadCacheDeleteTest() throws InterruptedException {
        Product p = new Product();
        p.setName("Widget");
        p.setDescription("A test widget");
        p.setPrice(new BigDecimal("9.99"));
        p.setStock(100);
        Product saved = service.createProduct(p);
        assertThat(saved.getId()).isNotNull();

        Product first  = service.getProduct(saved.getId());
        Product second = service.getProduct(saved.getId());
        assertThat(first.getName()).isEqualTo("Widget");
        assertThat(second.getName()).isEqualTo("Widget");

        service.deleteProduct(saved.getId());

        Thread.sleep(200);
    }
}
