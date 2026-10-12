package com.tharabagate.example;

import com.tharabagate.payment.*;
import com.tharabagate.webhook.WebhookEvent;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantService {
  private final OrderRepository orders;
  private final ProcessedWebhookRepository events;
  private final PaymentClient payments;
  private final ZoneId qrZone;
  private final String publicUrl;
  private final long backoff;
  public MerchantService(OrderRepository orders, ProcessedWebhookRepository events, PaymentClient payments,
      @Value("${merchant.qr-zone}") String zone, @Value("${merchant.public-url}") String publicUrl,
      @Value("${merchant.recovery-backoff-seconds}") long backoff) {
    this.orders=orders; this.events=events; this.payments=payments;
    this.qrZone=ZoneId.of(zone); this.publicUrl=publicUrl.replaceAll("/+$", ""); this.backoff=backoff;
  }
  @Transactional public Order create(MerchantController.NewOrder request) {
    Order o=new Order(); o.orderNumber=request.orderNumber(); o.description=request.description();
    o.amount=request.amount(); o.currency=request.currency(); return orders.saveAndFlush(o);
  }
  @Transactional(readOnly=true) public Order get(String id) {
    return orders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
  }
  private Order lock(String id) {
    return orders.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
  }
  @Transactional public Map<String,Object> start(String id, PaymentFlow flow) {
    // Holding the merchant lock also serializes concurrent creates for this order.
    Order o=lock(id);
    if (o.paymentFlow!=null && o.paymentFlow!=flow) throw new ResponseStatusException(HttpStatus.CONFLICT,"Order already uses another flow");
    if (o.paymentId==null) {
      var builder=CreatePaymentRequest.builder().paymentFlow(flow).merchantOrderId(o.orderNumber)
          .description(o.description).amount(o.amount).currency(o.currency);
      if (flow==PaymentFlow.HOSTED) builder.redirectUrl(publicUrl+"/payment/result?orderId="+o.id);
      // Stable request and key make a retry after a rolled-back DB transaction safe.
      var response=payments.create(builder.build(), "order:"+o.orderNumber+":"+flow);
      if (response.getPaymentId()==null || response.getPaymentId().isBlank() || response.getStatus()==null || response.getPaymentFlow()!=flow)
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Incomplete SDK payment response");
      o.paymentId=response.getPaymentId(); o.paymentFlow=flow;
      apply(o,response.getStatus(),response.getRawStatus());
      if (response instanceof HostedCreatePaymentResponse hosted) o.checkoutUrl=hosted.getCheckoutUrl();
      if (response instanceof DirectQrCreatePaymentResponse qr) {
        o.qrPayload=qr.getQrPayload();
        o.qrExpiresAt=qr.getExpiresAt()==null ? null : qr.getExpiresAt().atZone(qrZone).toInstant();
        o.nextRecoveryAt=o.qrExpiresAt;
      }
    }
    Map<String,Object> result=new LinkedHashMap<>(); result.put("paymentId",o.paymentId); result.put("status",o.paymentStatus);
    if (flow==PaymentFlow.HOSTED) result.put("checkoutUrl",o.checkoutUrl);
    else { result.put("qrPayload",o.qrPayload); result.put("expiresAt",o.qrExpiresAt); }
    return result;
  }
  static boolean isFinal(PaymentStatus s) {
    return s!=null && EnumSet.of(PaymentStatus.SUCCEEDED,PaymentStatus.FAILED,PaymentStatus.CANCELLED,
        PaymentStatus.EXPIRED,PaymentStatus.PARTIALLY_REFUNDED,PaymentStatus.REFUNDED).contains(s);
  }
  static void apply(Order o, PaymentStatus status, String raw) {
    if (status==null) return;
    // First terminal outcome wins; later stale snapshots cannot regress it.
    if (isFinal(o.paymentStatus)) return;
    o.paymentStatus=status; o.rawPaymentStatus=raw;
    o.orderStatus=switch(status) {
      case SUCCEEDED, PARTIALLY_REFUNDED, REFUNDED -> OrderStatus.PAID;
      case FAILED, EXPIRED -> OrderStatus.PAYMENT_FAILED;
      case CANCELLED -> OrderStatus.CANCELLED;
      default -> OrderStatus.PAYMENT_PENDING;
    };
  }
  @Transactional public void webhook(WebhookEvent event) {
    if (events.existsById(event.getId())) return;
    var data=event.getData();
    Order found=data.getPaymentId()==null ? null : orders.findByPaymentId(data.getPaymentId()).orElse(null);
    if (found==null && data.getMerchantOrderId()!=null) found=orders.findByOrderNumber(data.getMerchantOrderId()).orElse(null);
    // Do not consume unmatched events: gateway delivery can race payment creation.
    if (found==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order not available yet; retry delivery");
    Order o=lock(found.id);
    // Recheck after obtaining the lock for concurrent deliveries of the same event.
    if (events.existsById(event.getId())) return;
    if (o.paymentId==null || !o.paymentId.equals(data.getPaymentId()) ||
        (data.getMerchantOrderId()!=null && !o.orderNumber.equals(data.getMerchantOrderId())))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment reference mismatch");
    apply(o,data.getStatus(),data.getRawStatus()); o.webhookReceivedAt=Instant.now();
    events.saveAndFlush(new ProcessedWebhookEvent(event.getId()));
  }
  @Transactional public String claimRecovery(String id) {
    Order o=lock(id); Instant now=Instant.now();
    if (o.paymentFlow!=PaymentFlow.DIRECT_QR || isFinal(o.paymentStatus) || o.qrExpiresAt==null ||
        o.qrExpiresAt.isAfter(now) || o.nextRecoveryAt==null || o.nextRecoveryAt.isAfter(now)) return null;
    o.nextRecoveryAt=now.plusSeconds(backoff); return o.paymentId;
  }
  @Transactional public void recovered(String id, String paymentId, Payment payment) {
    Order o=lock(id);
    if (!Objects.equals(paymentId,o.paymentId) || !Objects.equals(paymentId,payment.getPaymentId()))
      throw new IllegalStateException("Recovery payment reference mismatch");
    apply(o,payment.getStatus(),payment.getRawStatus());
  }
}
