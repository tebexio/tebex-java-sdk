# Tebex Java SDK

The platform-agnostic Java SDK for [Tebex](https://tebex.io) - a merchant-of-record platform that enables monetization of your game, website, or server.

This repository contains mappings for various Tebex APIs: **Checkout**, **Headless**, and **Plugin**, as well as our **Webhooks** to allow integration in a wide variety of situations.  

See [Tebex-Minecraft](https://github.com/tebexio/Tebex-Minecraft) for its use in a game server plugin. It is intended to be reusable by any JVM integration.

## Importing into your Project

This library is published on Maven Central, simply add `io.tebex:tbx` to your `pom.xml`:

```xml
<dependency>
    <groupId>io.tebex</groupId>
    <artifactId>tbx</artifactId>
    <version>1.0.1</version>
</dependency>
```

Alternatively, you may close this repository to build the SDK locally with `./gradlew build`

## Consuming as a submodule

If you'd rather track this repo as a submodule in your project, do the following:
```bash
git submodule add https://github.com/tebexio/tebex-java-sdk.git tebex-java-sdk
```

Then add this into the consuming Gradle build as a composite build:

```kotlin
// settings.gradle.kts
includeBuild("tebex-java-sdk")
```

```kotlin
// build.gradle.kts of the consuming module
dependencies {
    implementation("io.tebex:tbx:1.0.1")
}
```

Gradle substitutes the coordinate with the included build's `:tbx` project, so the submodule is built from source rather than resolved from a repository.

## Code example: Using the Headless API

Every Headless API URL is scoped to your store's **public token** (`https://headless.tebex.io/api/accounts/{token}/...`).
Construct `io.tebex.http.HeadlessApi` with that token; the generated operations then take no token argument:

```java
import io.tebex.http.HeadlessApi;
import io.tebex.headless.model.AddBasketPackageRequest;
import io.tebex.headless.model.BasketResponse;
import io.tebex.headless.model.CreateBasketRequest;

HeadlessApi headless = new HeadlessApi("your-public-token");

// GET /api/accounts/{token}
headless.Headless.getWebstore();

// POST /api/accounts/{token}/baskets
BasketResponse basket = headless.Headless.createBasket(new CreateBasketRequest()
        .completeUrl("https://example.com/complete")
        .cancelUrl("https://example.com/cancel"));

// POST /api/baskets/{basketIdent}/packages
headless.Baskets.addBasketPackage(basket.getData().getIdent(), new AddBasketPackageRequest()
        .packageId("123456")
        .quantity(1));
```

If the token isn't known at construction time, use `new HeadlessApi()` and call `headless.setToken(publicToken)` before making any requests.

For Checkout API, use a similar flow with the `CheckoutApi` class.

## Webhooks

See `checkout-webhooks-integration` for an example project utilizing Tebex Webhooks. You can run it as a test as follows:
```bash
./gradlew :checkout-webhooks-integration:test
```
Test payloads live in `checkout-webhooks-integration/src/test/resources/webhooks/`

A test fails if a payload has a property that `apis/checkout-api.yaml` doesn't define.

## Modules

| Module                          | Description                                                                                                                                                                                                                                                                                   |
|---------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `tbx`                           | The published SDK: store/package models, the plugin API client, the command queue and the platform hook interfaces, plus the generated Headless and Checkout clients, physically merged into `tbx`'s own jar (see below).                                                                     |
| `headless-api`                  | The [Headless API](https://docs.tebex.io/developers/headless-api/overview) client, generated from `apis/headless-api.yaml` by the OpenAPI generator. Do not edit its sources by hand. Internal only — never published on its own; exists solely as the generator's isolated output directory. |
| `checkout-api`                  | The [Checkout API](https://docs.tebex.io/developers/checkout-api/overview) client, generated from `apis/checkout-api.yaml` by the OpenAPI generator. Do not edit its sources by hand. Internal only — never published on its own; exists solely as the generator's isolated output directory. |
 | `checkout-integration`          | A selection of integration tests for Checkout API. See this for code examples.                                                                                                                                                                                                                |
| `checkout-webhooks-integration` | Integration tests for our webhooks, see for code examples.                                                                                                                                                                                                                                    |
| `headless-integration`          | Integration tests against Headless API., see for code examples                                                                                                                                                                                                                                |

`io.tebex:tbx` is a single, self-contained artifact containing `headless-api` and `checkout-api`.

## Build

```bash
./gradlew build     # compile all modules
./gradlew test      # run the requirement suite
```

### Note: Generated API clients

The `headless-api` and `checkout-api` sources are each generated from their OpenAPI contract, which is the source of truth for every operation and schema.

They are committed after generation to this repo. Modifications to the OpenAPI schema will require rebuilding the projects before they are propagated to the SDK:
```bash
docker compose run --rm headless-api-generator
docker compose run --rm checkout-api-generator
```

## Testing

### Live Headless API tests

`headless-integration` tests the generated Headless client against the real API and a real store. 

Every test is skipped unless a public token is set. It can bet set via environment variables:

```bash
TEBEX_IT_PUBLIC_TOKEN=your-public-token ./gradlew :headless-integration:test
```

### Live Checkout API tests

`checkout-integration` does the same for the generated Checkout client, against `https://checkout.tebex.io/api`. The
store must have the Checkout API enabled. Every test is skipped unless its credentials are set:

```bash
TEBEX_IT_CHECKOUT_PUBLIC_TOKEN=your-public-token TEBEX_IT_CHECKOUT_PRIVATE_KEY=your-private-key \
  ./gradlew :checkout-integration:test
```

### Contract drift / additionalProperties

Tebex APIs are constantly updating. Both live test suites look for properties in each response that the contract doesn't define. 

**Contract drift doesn't fail the build**, the client accepts them into `additionalProperties`, so an API addition doesn't
break an integration. 

If the API has updated since the latest SDK update, you may see warnings such as: `add property `currencyCode` to components/schemas/PriceDetails (string, e.g. "USD")`

New properties are available by accessing the `additionalProperties` parameter, if present.

This behaviour comes from `disallowAdditionalPropertiesIfNotPresent=false` in `docker-compose.yml`; keep it when
changing the generator options, or any new API field will make parsing fail for released SDK versions.

## License

See [LICENSE](LICENSE).