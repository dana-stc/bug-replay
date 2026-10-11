# bug-replay

Record what a failing request did to its dependencies (databases, HTTP clients, queues),
mask personal data, and turn the recording into a JUnit test that reproduces the bug
on your machine.

```
production request  ->  Session records dependency calls (PII masked)
                    ->  recording.json
                    ->  generated JUnit test + Replay mocks  ->  fails locally, same way
```

## Why

"It works on my machine" usually means "I don't have the production data and the
third-party responses". The dependency calls of one failed request are exactly that data.
`bug-replay` captures them once and replays them deterministically.

## Quick start

### 1. Add the dependency

```xml
<dependency>
  <groupId>io.bugreplay</groupId>
  <artifactId>bug-replay</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Until it is published, run `mvn install` in this directory. Requires JDK 17+.

### 2. Record a request

Wrap each dependency **interface** you want to capture. Only interfaces are supported,
which is what you get with Spring Data repositories, Feign/Retrofit clients, and
hand-written ports.

```java
Session session = Session.start("checkout-500");
PaymentGateway gateway = session.record(PaymentGateway.class, realGateway);
CustomerRepo repo      = session.record(CustomerRepo.class, realRepo);

try {
    checkoutService.handle(request, gateway, repo);
} catch (Throwable t) {
    session.failed(t);
    throw t;
} finally {
    session.finish().save(Path.of("recordings/checkout-500.json"));
}
```

You decide when to record: always on a canary, on a sampled percentage, or only when a
header such as `X-Debug-Record` is present. Recording adds the cost of serializing each
call to JSON, so avoid enabling it for all traffic on hot paths.

### 3. Generate the test

```java
Recording recording = Recording.load(Path.of("recordings/checkout-500.json"));
TestGenerator.write(recording,
        "com.acme.checkout",                    // package of the generated test
        Path.of("src/test/java"),
        Path.of("src/test/resources"));
```

This writes two files:

- `src/test/resources/bugreplay/<id>.json`: the masked recording
- `src/test/java/com/acme/checkout/Bug_checkout_500_<id>Test.java`: the test skeleton

### 4. Fill in the test

The generated test already creates a replaying mock for every recorded dependency:

```java
@Test
void reproduces_checkout_500() throws Exception {
    Recording recording = Recording.fromResource("/bugreplay/3f9a1c2b.json");
    Replay replay = Replay.of(recording);

    PaymentGateway paymentGateway = replay.mock(PaymentGateway.class);
    CustomerRepo customerRepo     = replay.mock(CustomerRepo.class);

    // TODO: build the system under test with the mocks above and call it with the
    // original input (the recording holds the dependency calls, not the request itself).
    // Recorded failure: java.lang.IllegalStateException: card declined
    // assertThrows(java.lang.IllegalStateException.class, () -> sut.handle(request));

    replay.verifyAllConsumed();
}
```

Create your service with the mocks, call it with the request, and uncomment the
assertion. The test now fails (or throws) exactly as production did. Fix the bug, keep
the test as a regression test.

The request itself is not recorded on purpose: it usually contains the most personal
data. Rebuild it by hand from the ticket or logs, using the same values the recording
shows (masked values match masked arguments automatically, see below).

## How replay works

- Calls are matched **per dependency, in recorded order**, by method name, parameter
  types, and arguments.
- A call that differs from the recording throws `ReplayMismatchException` with an
  expected-versus-actual diff:

  ```
  Call differs from the recording.
    expected: PaymentGateway.charge["masked-1a2b3c4d", 1050]
    actual:   PaymentGateway.charge["masked-1a2b3c4d", 2000]
  ```
- `ReplayMismatchException` is an `AssertionError`, so a `catch (Exception e)` in your
  code cannot hide it.
- Recorded exceptions are recreated by class and message. If the class is not on the
  classpath, or a checked exception is not declared by the method, a `ReplayedException`
  (unchecked) is thrown instead, carrying the original type name.
- `replay.verifyAllConsumed()` fails if the code under test skipped recorded calls,
  which usually means the code path changed.

## Personal data masking

Every argument and result is masked **before** it is stored. Raw values never reach the
recording, in memory or on disk.

| What                                           | Becomes                                      |
|------------------------------------------------|----------------------------------------------|
| Values of fields named `password`, `token`, `secret`, `apiKey`, `authorization`, `ssn`, `cnp`, `cardNumber`, `cvv`, `iban`, `pin`, `phone`, `phoneNumber` (case, `_` and `-` ignored) | `masked-<hash>`; numbers become `0` |
| Emails anywhere in any string                  | `<hash>@masked.invalid`                      |
| 13 to 19 digit numbers that pass the Luhn check (payment cards) | Digits of the same length  |

Masking is **deterministic**: the same input always gives the same output. That keeps
equality between values and lets you pass the *original* values to a replayed mock, which
masks them the same way before comparing.

Add your own sensitive fields, and use a secret salt if recordings leave your network:

```java
Masker masker = Masker.defaults()
        .withSensitiveFields("customerName", "street")
        .withSalt(System.getenv("BUGREPLAY_SALT"));

Session session = Session.start("checkout-500", masker);
Replay replay   = Replay.of(recording, masker);   // use the same masker on replay
```

**Masking is best effort, not a guarantee.** Free text such as names in a comment field
is not detected. Review a recording before sharing it, and add field names for anything
specific to your domain.

## API summary

| Class                | Purpose                                                              |
|----------------------|----------------------------------------------------------------------|
| `Session`            | Start a recording, wrap dependencies with `record`, mark `failed`, `finish` |
| `Recording`          | The serializable result; `save`, `load`, `fromResource`              |
| `Replay`             | `mock(Class)` returns a replaying proxy; `verifyAllConsumed()`       |
| `TestGenerator`      | `generate` (source as string) or `write` (files into test roots)     |
| `Masker`             | Deterministic PII masking; `withSensitiveFields`, `withSalt`         |

## Limitations

- Only interface-typed dependencies can be wrapped. Concrete classes and static calls
  are not captured.
- Values must be serializable by Jackson (records, POJOs, collections, `java.time`
  types, `BigDecimal`). `Optional` and streams are not supported yet.
- Matching is by order and argument equality, so code that calls a dependency
  non-deterministically (random IDs, current time as argument) will not match until
  you fix those inputs in the test.
- No automatic capture of the incoming request, system clock, or randomness.
- Automatic instrumentation (a Java agent that records without wrapping code) is a
  possible future step.

## Building from source

```bash
mvn test      # records, masks, replays and compiles a generated test
mvn install   # installs the artifact into your local repository
```
