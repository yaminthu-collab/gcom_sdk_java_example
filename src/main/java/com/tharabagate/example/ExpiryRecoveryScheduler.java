package com.tharabagate.example;
import com.tharabagate.payment.PaymentClient;
import java.time.Instant;
import org.slf4j.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class ExpiryRecoveryScheduler {
  private static final Logger log=LoggerFactory.getLogger(ExpiryRecoveryScheduler.class);
  private final OrderRepository orders; private final MerchantService service; private final PaymentClient payments;
  public ExpiryRecoveryScheduler(OrderRepository orders, MerchantService service, PaymentClient payments) {
    this.orders=orders; this.service=service; this.payments=payments;
  }
  @Scheduled(fixedDelayString="${merchant.recovery-delay-ms}", initialDelayString="${merchant.recovery-delay-ms}")
  public void recover() {
    for (String id:orders.recoveryCandidates(Instant.now(),PageRequest.of(0,100))) {
      try {
        String paymentId=service.claimRecovery(id);
        // Network I/O outside DB transaction; webhook may finish while this call runs.
        if (paymentId!=null) service.recovered(id,paymentId,payments.get(paymentId));
      } catch (RuntimeException ex) { log.warn("Expiry recovery failed for order {} ({})",id,ex.getClass().getSimpleName()); }
    }
  }
}
