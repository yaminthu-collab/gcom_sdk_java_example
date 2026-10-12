package com.tharabagate.example;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(name="processed_webhook_events")
public class ProcessedWebhookEvent {
  @Id @Column(length=255) public String eventId;
  @Column(nullable=false) public Instant processedAt;
  protected ProcessedWebhookEvent() {}
  public ProcessedWebhookEvent(String id) { eventId=id; processedAt=Instant.now(); }
}
