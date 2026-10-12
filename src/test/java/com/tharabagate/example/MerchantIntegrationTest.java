package com.tharabagate.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tharabagate.payment.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:merchant;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop","merchant.webhook-secret=test-secret","merchant.recovery-delay-ms=3600000"})
@AutoConfigureMockMvc
class MerchantIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired MerchantService service;
  @Autowired OrderRepository orders;
  @Autowired ProcessedWebhookRepository events;
  @Autowired ExpiryRecoveryScheduler scheduler;
  @MockitoBean PaymentClient payments;
  @BeforeEach void resetDb() { events.deleteAll(); orders.deleteAll(); reset(payments); }
  Order order() { return service.create(new MerchantController.NewOrder("ORDER-"+UUID.randomUUID(),"Demo",new BigDecimal("10000.00"),"MMK")); }
  Order qr(boolean expired) {
    Order o=order();
    when(payments.create(any(),anyString())).thenReturn(new DirectQrCreatePaymentResponse("pay-"+o.id,
        PaymentStatus.PENDING,"PENDING","qr-payload",LocalDateTime.now(ZoneId.of("Asia/Rangoon")).plusMinutes(expired?-1:10)));
    service.start(o.id,PaymentFlow.DIRECT_QR); return service.get(o.id);
  }
  Payment snapshot(String id,PaymentStatus status) {
    Payment p=mock(Payment.class); when(p.getPaymentId()).thenReturn(id); when(p.getStatus()).thenReturn(status);
    when(p.getRawStatus()).thenReturn(status.name()); return p;
  }
  byte[] body(Order o,String eventId,PaymentStatus status) throws Exception {
    return json.writeValueAsBytes(Map.of("id",eventId,"type","payment."+status.name().toLowerCase(Locale.ROOT),
        "data",Map.of("payment_id",o.paymentId,"merchant_order_id",o.orderNumber,"status",status.name())));
  }
  String sign(byte[] body) throws Exception {
    // Test fixture only: application verification is exclusively delegated to the SDK.
    Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body));
  }
  void send(byte[] body) throws Exception {
    mvc.perform(post("/webhooks/tharabagate").contentType("application/json").header("X-Webhook-Signature",sign(body)).content(body)).andExpect(status().isOk());
  }
  @Test void createOrderEndpointAndValidation() throws Exception {
    mvc.perform(post("/api/orders").contentType("application/json").content("""
        {"orderNumber":"ORDER-10001","description":"Demo order","amount":"10000.00","currency":"MMK"}
        """)).andExpect(status().isCreated()).andExpect(jsonPath("orderStatus").value("PENDING"));
    assertThat(orders.findByOrderNumber("ORDER-10001").orElseThrow().amount).isEqualByComparingTo("10000.00");
    mvc.perform(post("/api/orders").contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
  }
  @Test void hostedPersistsAndRepeatedCreateUsesSamePayment() throws Exception {
    Order o=order(); HostedCreatePaymentResponse response=mock(HostedCreatePaymentResponse.class);
    when(response.getPaymentId()).thenReturn("hosted-1"); when(response.getPaymentFlow()).thenReturn(PaymentFlow.HOSTED);
    when(response.getStatus()).thenReturn(PaymentStatus.PENDING); when(response.getCheckoutUrl()).thenReturn("https://checkout.example/test");
    when(payments.create(any(),anyString())).thenReturn(response);
    for(int i=0;i<2;i++) mvc.perform(post("/api/orders/"+o.id+"/payments/hosted")).andExpect(status().isOk())
        .andExpect(jsonPath("checkoutUrl").value("https://checkout.example/test"));
    assertThat(service.get(o.id).paymentId).isEqualTo("hosted-1");
    verify(payments).create(argThat(r -> r.getPaymentFlow()==PaymentFlow.HOSTED && r.getRedirectUrl().contains(o.id)
        && r.getAmount().compareTo(o.amount)==0),eq("order:"+o.orderNumber+":HOSTED"));
  }
  @Test void qrEndpointPersistsPayloadAndExpiry() throws Exception {
    Order o=qr(false);
    mvc.perform(post("/api/orders/"+o.id+"/payments/qr")).andExpect(status().isOk())
        .andExpect(jsonPath("qrPayload").value("qr-payload")).andExpect(jsonPath("paymentId").value(o.paymentId));
    assertThat(o.qrExpiresAt).isAfter(Instant.now());
    mvc.perform(get("/api/orders/"+o.id)).andExpect(status().isOk()).andExpect(jsonPath("paymentFlow").value("DIRECT_QR"));
  }
  @Test void succeededWebhookAndDuplicateReturn200() throws Exception {
    Order o=qr(false); byte[] bytes=body(o,"evt-1",PaymentStatus.SUCCEEDED);
    send(bytes); Instant first=service.get(o.id).webhookReceivedAt; send(bytes);
    assertThat(service.get(o.id).orderStatus).isEqualTo(OrderStatus.PAID);
    assertThat(service.get(o.id).webhookReceivedAt).isEqualTo(first); assertThat(events.count()).isEqualTo(1);
  }
  @Test void invalidSignatureReturns401() throws Exception {
    mvc.perform(post("/webhooks/tharabagate").contentType("application/json").header("X-Webhook-Signature","0".repeat(64)).content("{}"))
        .andExpect(status().isUnauthorized()); assertThat(events.count()).isZero();
  }
  @Test void expiredQrGetsPaymentAndBecomesPaid() {
    Order o=qr(true); Payment response=snapshot(o.paymentId,PaymentStatus.SUCCEEDED);
    when(payments.get(o.paymentId)).thenReturn(response);
    scheduler.recover(); scheduler.recover();
    verify(payments,times(1)).get(o.paymentId); assertThat(service.get(o.id).orderStatus).isEqualTo(OrderStatus.PAID);
  }
  @Test void activeQrIsNotPolledAndPendingRecoveryBacksOff() {
    Order active=qr(false); scheduler.recover(); verify(payments,never()).get(anyString());
    Order expired=qr(true); Payment response=snapshot(expired.paymentId,PaymentStatus.PENDING);
    when(payments.get(expired.paymentId)).thenReturn(response);
    scheduler.recover(); scheduler.recover(); verify(payments,times(1)).get(expired.paymentId);
    assertThat(service.get(active.id).orderStatus).isEqualTo(OrderStatus.PAYMENT_PENDING);
  }
  @Test void webhookWinsWhileRecoveryRequestIsInFlight() throws Exception {
    Order o=qr(true); CountDownLatch requested=new CountDownLatch(1), release=new CountDownLatch(1);
    when(payments.get(o.paymentId)).thenAnswer(call -> {
      requested.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
      return snapshot(o.paymentId,PaymentStatus.PENDING);
    });
    ExecutorService executor=Executors.newSingleThreadExecutor();
    try {
      Future<?> recovery=executor.submit(scheduler::recover);
      assertThat(requested.await(10,TimeUnit.SECONDS)).isTrue(); send(body(o,"race",PaymentStatus.SUCCEEDED));
      release.countDown(); recovery.get(10,TimeUnit.SECONDS);
      assertThat(service.get(o.id).paymentStatus).isEqualTo(PaymentStatus.SUCCEEDED);
    } finally { release.countDown(); executor.shutdownNow(); }
  }
  @Test void concurrentDuplicateDeliveriesCommitOnce() throws Exception {
    Order o=qr(false); byte[] bytes=body(o,"parallel",PaymentStatus.SUCCEEDED);
    ExecutorService executor=Executors.newFixedThreadPool(2);
    try {
      List<Callable<Void>> tasks=List.of(() -> { send(bytes); return null; }, () -> { send(bytes); return null; });
      for(Future<Void> f:executor.invokeAll(tasks)) f.get(10,TimeUnit.SECONDS);
      assertThat(events.count()).isEqualTo(1); assertThat(service.get(o.id).orderStatus).isEqualTo(OrderStatus.PAID);
    } finally { executor.shutdownNow(); }
  }
  @Test void redirectDoesNotMarkPaid() throws Exception {
    Order o=qr(false);
    mvc.perform(get("/payment/result").param("orderId",o.id)).andExpect(status().isOk())
        .andExpect(jsonPath("orderStatus").value("PAYMENT_PENDING")); verify(payments,never()).get(anyString());
  }
  @Test void unmatchedWebhookRemainsRetryable() throws Exception {
    Order o=qr(false); o.paymentId="unknown"; o.orderNumber="unknown";
    byte[] bytes=body(o,"unmatched",PaymentStatus.SUCCEEDED);
    mvc.perform(post("/webhooks/tharabagate").contentType("application/json").header("X-Webhook-Signature",sign(bytes)).content(bytes))
        .andExpect(status().isServiceUnavailable()); assertThat(events.count()).isZero();
  }
  @Test void terminalMappingsAndStaleSnapshots() {
    for(PaymentStatus status:List.of(PaymentStatus.FAILED,PaymentStatus.CANCELLED,PaymentStatus.EXPIRED)) {
      Order o=new Order(); MerchantService.apply(o,status,status.name()); MerchantService.apply(o,PaymentStatus.PENDING,"PENDING");
      assertThat(o.paymentStatus).isEqualTo(status);
      assertThat(o.orderStatus).isEqualTo(status==PaymentStatus.CANCELLED?OrderStatus.CANCELLED:OrderStatus.PAYMENT_FAILED);
    }
  }
}
