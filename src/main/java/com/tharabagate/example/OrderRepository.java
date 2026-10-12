package com.tharabagate.example;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
public interface OrderRepository extends JpaRepository<Order,String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from Order o where o.id=:id")
  Optional<Order> lockById(String id);
  Optional<Order> findByPaymentId(String paymentId);
  Optional<Order> findByOrderNumber(String orderNumber);
  @Query("select o.id from Order o where o.paymentFlow=com.tharabagate.payment.PaymentFlow.DIRECT_QR and o.paymentStatus in (com.tharabagate.payment.PaymentStatus.PENDING, com.tharabagate.payment.PaymentStatus.PROCESSING, com.tharabagate.payment.PaymentStatus.AWAITING_PAYMENT_METHOD, com.tharabagate.payment.PaymentStatus.UNKNOWN) and o.qrExpiresAt<=:now and o.nextRecoveryAt<=:now order by o.nextRecoveryAt")
  List<String> recoveryCandidates(Instant now, Pageable page);
}
