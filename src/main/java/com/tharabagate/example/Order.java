package com.tharabagate.example;

import com.tharabagate.payment.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name="merchant_orders", indexes=@Index(name="idx_recovery", columnList="paymentFlow,qrExpiresAt,nextRecoveryAt"))
public class Order {
  @Id @GeneratedValue(strategy=GenerationType.UUID) public String id;
  @Column(nullable=false, unique=true, length=100) public String orderNumber;
  @Column(nullable=false, length=500) public String description;
  @Column(nullable=false, precision=19, scale=2) public BigDecimal amount;
  @Column(nullable=false, length=3) public String currency;
  @Enumerated(EnumType.STRING) @Column(nullable=false) public OrderStatus orderStatus=OrderStatus.PENDING;
  @Column(unique=true) public String paymentId;
  @Enumerated(EnumType.STRING) public PaymentFlow paymentFlow;
  @Enumerated(EnumType.STRING) public PaymentStatus paymentStatus;
  public String rawPaymentStatus;
  @Column(length=2048) public String checkoutUrl;
  @Column(length=8192) public String qrPayload;
  public Instant qrExpiresAt;
  public Instant nextRecoveryAt;
  public Instant webhookReceivedAt;
  @Column(nullable=false) public Instant createdAt;
  @Column(nullable=false) public Instant updatedAt;
  @PrePersist void created() { createdAt=updatedAt=Instant.now(); }
  @PreUpdate void updated() { updatedAt=Instant.now(); }
}
