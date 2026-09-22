package com.ordermanagement.config;

import com.ordermanagement.model.Product;
import com.ordermanagement.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// Seeds a handful of demo products on first startup so the dashboard has data immediately.
@Component
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;

    public DataSeeder(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) {
            return;
        }
        productRepository.save(new Product("Wireless Mouse", "WM-100", 50));
        productRepository.save(new Product("Mechanical Keyboard", "MK-200", 30));
        productRepository.save(new Product("USB-C Hub", "UCH-300", 20));
        productRepository.save(new Product("27in Monitor", "MON-400", 10));
        productRepository.save(new Product("Webcam 1080p", "CAM-500", 5));
    }
}
