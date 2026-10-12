package com.tharabagate.example;
import com.tharabagate.payment.PaymentFlow;
import com.tharabagate.webhook.TharabaGateWebhook;
import com.tharabagate.exception.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
public class MerchantController {
  private final MerchantService service; private final String secret;
  public MerchantController(MerchantService service,@Value("${merchant.webhook-secret}") String secret) { this.service=service; this.secret=secret; }
  public record NewOrder(@NotBlank @Size(max=100) String orderNumber,
      @NotBlank @Size(max=500) String description,
      @NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount,
      @NotNull @Pattern(regexp="[A-Z]{3}") String currency) {}
  @PostMapping("/api/orders") @ResponseStatus(HttpStatus.CREATED)
  public Order create(@Valid @RequestBody NewOrder request) { return service.create(request); }
  @GetMapping("/api/orders/{id}") public Order get(@PathVariable String id) { return service.get(id); }
  @PostMapping("/api/orders/{id}/payments/hosted") public Map<String,Object> hosted(@PathVariable String id) { return service.start(id,PaymentFlow.HOSTED); }
  @PostMapping("/api/orders/{id}/payments/qr") public Map<String,Object> qr(@PathVariable String id) { return service.start(id,PaymentFlow.DIRECT_QR); }
  @GetMapping("/payment/result") public Order result(@RequestParam String orderId) {
    // redirect != proof of payment success. Only display persisted merchant state.
    return service.get(orderId);
  }
  @PostMapping("/webhooks/tharabagate") public ResponseEntity<Void> webhook(@RequestBody byte[] raw,@RequestHeader HttpHeaders headers) {
    if (secret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure webhook secret");
    Map<String,String> single=new HashMap<>();
    headers.forEach((name,values) -> {
      if (name.equalsIgnoreCase("X-Webhook-Signature") && values.size()!=1)
        throw new InvalidWebhookSignatureException("Ambiguous signature header");
      single.put(name,values.get(0));
    });
    service.webhook(TharabaGateWebhook.verifyAndParse(raw,single,secret));
    return ResponseEntity.ok().build();
  }
  @ExceptionHandler(InvalidWebhookSignatureException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public Map<String,String> invalidSignature() { return Map.of("error","Invalid webhook signature"); }
  @ExceptionHandler(ValidationException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String,String> invalidPayload() { return Map.of("error","Invalid payment or webhook payload"); }
  @ExceptionHandler(DataIntegrityViolationException.class) @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String,String> conflict() { return Map.of("error","Duplicate or invalid merchant record"); }
  @ExceptionHandler(TharabaGateException.class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
  public Map<String,String> gatewayError() { return Map.of("error","Gateway call failed"); }
}
