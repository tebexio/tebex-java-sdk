package io.tebex.checkout.integration;

import static io.tebex.checkout.integration.LiveCheckout.checkContract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.tebex.checkout.invoker.ApiException;
import io.tebex.checkout.model.Basket;
import io.tebex.checkout.model.CheckoutItem;
import io.tebex.checkout.model.CheckoutRequest;
import io.tebex.checkout.model.CheckoutRequestBasket;
import io.tebex.http.CheckoutApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The single-call checkout flow: basket, items, and payment link in one request. */
class CheckoutTest {

    private CheckoutApi checkout;

    @BeforeEach
    void connect() {
        checkout = LiveCheckout.client();
    }

    @Test
    @DisplayName("checkout creates a basket holding the items and returns its checkout link")
    void checkout() throws ApiException {
        Basket basket = checkout.Checkout.checkout(new CheckoutRequest()
                .basket(new CheckoutRequestBasket()
                        .firstName("Integration")
                        .lastName("Test")
                        .email(LiveCheckout.CUSTOMER_EMAIL)
                        .returnUrl(LiveCheckout.RETURN_URL + "/return")
                        .completeUrl(LiveCheckout.RETURN_URL + "/complete"))
                .addItemsItem(new CheckoutItem()
                        ._package(LiveCheckout.oneOffPackage())
                        .qty(2)));

        assertNotNull(basket.getIdent(), "basket ident");
        assertEquals(Boolean.FALSE, basket.getComplete());
        assertNotNull(basket.getLinks().getCheckout(), "checkout link");
        assertEquals(1, basket.getRows().size(), "one row for the item");
        // The contract warns a qty nested inside the package is silently ignored;
        // this is the check that qty sent beside it is honoured.
        assertEquals(Integer.valueOf(2), basket.getRows().get(0).getQuantity());
        checkContract(basket);
    }
}
