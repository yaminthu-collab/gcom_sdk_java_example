# Tharaba Gate merchant example

A standalone Java 17+ Spring Boot merchant application using the existing Java SDK in `../java`. It demonstrates HOSTED and DIRECT_QR payments, raw-body SDK webhook verification, merchant persistence, duplicate delivery protection and expiry recovery. This is an integration reference, not production merchant business logic.

## Prerequisites and SDK dependency

Install JDK 17+ and Maven 3.9+. No PostgreSQL installation or real credentials are required to start the application, create local orders or run tests. Payment creation requires TEST gateway credentials and a reachable TEST API (or a local mock API).

From this folder, install the existing SDK, test, and run:

```sh
mvn -f ../java/pom.xml install -DskipTests -Dmaven.javadoc.skip=true
mvn test
mvn spring-boot:run
```

The dependency is `com.tharabagate:tharabagate-java:1.0.0-SNAPSHOT`; no SDK source is copied into this application. `mvn package` produces `target/merchant-example-1.0.0-SNAPSHOT.jar`, runnable with `java -jar`.

## Environment variables

See `.env.example`. Spring Boot does not automatically load `.env`; export its values in your shell or configure your IDE environment. PowerShell example:

```powershell
$env:THARABA_GATE_API_KEY='your-test-key'
$env:THARABA_GATE_WEBHOOK_SECRET='your-webhook-secret'
$env:THARABA_GATE_ENVIRONMENT='TEST'
$env:THARABA_GATE_BASE_URL='https://your-verified-test-api-host'
$env:THARABA_GATE_QR_ZONE='Asia/Rangoon'
$env:MERCHANT_PUBLIC_URL='http://localhost:8080'
mvn spring-boot:run
```

The singleton `TharabaGateClient` explicitly selects TEST. LIVE is rejected in this example. The SDK has no verified default TEST API URL, so obtain the TEST base URL from Tharaba Gate. The local default `http://localhost:9090` is a placeholder for a mock API, not an included server. The default API key is also a placeholder; no network calls occur at startup. Webhooks return 503 until a secret is configured.

DIRECT_QR SDK expiry is a `LocalDateTime` without an offset. Confirm the gateway/bank timezone and set `THARABA_GATE_QR_ZONE`; this sample defaults to `Asia/Rangoon`. The merchant converts it to an `Instant` for persistence and returns expiry with a UTC offset. Missing expiry means no automatic expiry check is possible; investigate the gateway response rather than inventing an expiration.

## Create order and read merchant state

The following curl commands use a POSIX shell. In PowerShell use `curl.exe` and appropriate JSON quoting (or `Invoke-RestMethod`). Replace `ORDER_ID` with the returned `id`.

```sh
curl -i http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"orderNumber":"ORDER-10001","description":"Demo order","amount":"10000.00","currency":"MMK"}'
curl http://localhost:8080/api/orders/ORDER_ID
```

Amounts use `BigDecimal`, with positive values and at most two decimal places. Order numbers are unique. Use a new order for each payment flow; this reference supports one payment per order.

## Create HOSTED payment

```sh
curl -X POST http://localhost:8080/api/orders/ORDER_ID/payments/hosted
```

The response contains `paymentId`, `status`, and `checkoutUrl`. The merchant frontend redirects the customer's browser to `checkoutUrl`. The backend never follows it. The SDK receives the merchant order number as `merchantOrderId`, and the stable key `order:<orderNumber>:HOSTED` as the create idempotency key. The payment ID and checkout URL are saved; repeating this endpoint returns the saved response instead of creating another payment.

The SDK request has the return URL `/payment/result?orderId=...`. That endpoint displays local state only: **redirect != proof of payment success**. Only verified webhook or SDK payment status updates can mark the order paid. A pending browser return may show pending until a webhook arrives. This example does not implement a hosted recovery job.

## Create DIRECT_QR payment and display QR

Create another order, then:

```sh
curl -X POST http://localhost:8080/api/orders/ORDER_ID/payments/qr
```

The response contains `paymentId`, `status`, `qrPayload`, and `expiresAt`. The frontend can render `qrPayload` with its chosen QR library. Neither this backend nor the SDK generates a QR image. A repeat request returns persisted QR data with the current merchant payment status; the frontend must not display an expired or completed QR as payable.

## Webhook flow and duplicate handling

Configure your gateway webhook destination as `https://your-public-merchant-host/webhooks/tharabagate`. For local development use an HTTPS tunnel and configure the signing secret. The controller receives the original bytes and passes them and the headers to `TharabaGateWebhook.verifyAndParse`. It rejects ambiguous signature headers. No application HMAC implementation is used.

To replay a genuine signed payload (save the exact bytes without formatting or adding a newline):

```sh
curl -i http://localhost:8080/webhooks/tharabagate \
  -H 'Content-Type: application/json' \
  -H "X-Webhook-Signature: $SIGNATURE" \
  --data-binary @webhook.json
```

`SIGNATURE` must be supplied by the gateway for those exact bytes. Invalid signatures return 401. After verification the merchant finds the order by payment ID or merchant order number, locks the order row, rechecks the signed body event ID, validates the references, updates status and `webhookReceivedAt`, and inserts `ProcessedWebhookEvent` in the same transaction. Duplicate valid events return 200 without applying the update again. Unmatched events return 503 without consuming the event ID so delivery can retry; mismatched references return 409. Configure gateway retries and monitor these failures.

SUCCEEDED maps to PAID; FAILED and EXPIRED map to PAYMENT_FAILED; CANCELLED maps to CANCELLED. The gateway payment status is stored independently. First terminal outcome wins in this simplified model, including conflicting terminal notifications. Refund lifecycle and corrections require explicit business policy in a real merchant system; this demo does not provide refund accounting.

## DIRECT_QR expiry fallback

Every 60 seconds, the merchant scheduler selects at most 100 overdue DIRECT_QR records with unresolved payment status and a due recovery time. A non-final webhook does not suppress recovery. Future-expiry payments and terminal payments are never polled.

Each candidate is locked briefly to claim a recovery attempt, with a five-minute backoff persisted in `nextRecoveryAt`. The scheduler then calls SDK `payments().get(paymentId)` outside the transaction and applies the snapshot in a fresh transaction under the same order lock used by webhooks. This prevents a stale non-final result from replacing a final local state. API failures are isolated per order and retried after backoff; a crash during recovery is similarly recoverable. This is expiry/recovery fallback, not continuous active-payment polling. The delay and backoff can be overridden with Spring properties `merchant.recovery-delay-ms` and `merchant.recovery-backoff-seconds`.

## Database responsibilities

H2 stores data in `./data/merchant` by default, surviving restarts. `merchant_orders` and `processed_webhook_events` are merchant-owned tables. JPA uses portable types, decimal money, unique references and transactional pessimistic row locks. For PostgreSQL, set `DATABASE_URL=jdbc:postgresql://localhost:5432/merchant`, `DATABASE_USER`, and `DATABASE_PASSWORD`. The PostgreSQL driver is included; PostgreSQL execution has not been tested here. Replace Hibernate `ddl-auto=update` with reviewed migrations for deployments.

The Tharaba Gate SDK does not create merchant database tables, store payment state, track whether webhooks arrived, or run a scheduler. Those responsibilities belong to the merchant application. The SDK owns API HTTP calls, authentication, API models, API retries and webhook signature verification. It remains stateless.

## Tests

`mvn test` uses in-memory H2 and a mocked SDK `PaymentClient`; real SDK webhook verification is exercised with test-only signed fixtures. Tests cover order creation and validation, both create endpoints, payment persistence, repeat create idempotence, webhook success, sequential and concurrent duplicate deliveries, invalid signatures, unmatched delivery retries, expiry lookup and success, recovery backoff, active payment exclusion, an in-flight scheduler/webhook race, terminal mappings, and browser return behavior. No real credentials or API network calls are needed.

## Security and reference limitations

Never commit real keys, secrets, `.env`, or database files. Use HTTPS and a secret manager for deployments. The example deliberately has no customer authentication or authorization: add ownership checks, authentication, rate limits and request-size limits before exposing order endpoints. The H2 console is disabled. API errors do not expose credentials or raw SDK messages.

The create call holds an order lock across SDK I/O for clarity; production systems may prefer a persisted payment-attempt/outbox design. Reusing the stable idempotency key protects retries after a DB failure, subject to the gateway's idempotency retention contract. Keep order create fields immutable. Processed event IDs are retained indefinitely in this example; define a retention policy consistent with gateway replay behavior. Database locks and recovery claims support multiple instances, but large-scale batching, refunds, reconciliation and operational alerting need additional merchant design.
