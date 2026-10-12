package com.tharabagate.example;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProcessedWebhookRepository extends JpaRepository<ProcessedWebhookEvent,String> {}
