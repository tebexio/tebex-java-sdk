package io.tebex.checkout.integration;

import static io.tebex.checkout.integration.LiveCheckout.checkContract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tebex.checkout.invoker.ApiException;
import io.tebex.checkout.model.AddPackageRequest;
import io.tebex.checkout.model.Basket;
import io.tebex.checkout.model.BasketRow;
import io.tebex.checkout.model.CreateBasketRequest;
import io.tebex.checkout.model.Sale;
import io.tebex.checkout.model.UpdateBasketRequest;
import io.tebex.http.CheckoutApi;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The basket lifecycle: create, read, update, add/remove packages, and sales. */
class BasketTest {

    private CheckoutApi checkout;

    @BeforeEach
    void connect() {
        checkout = LiveCheckout.client();
    }

    @Test
    @DisplayName("createBasket returns a new, empty, unpaid basket with a checkout link")
    void createBasket() throws ApiException {
        Basket basket = checkout.Baskets.createBasket(new CreateBasketRequest()
                .returnUrl(LiveCheckout.RETURN_URL + "/return")
                .completeUrl(LiveCheckout.RETURN_URL + "/complete")
                .email(LiveCheckout.CUSTOMER_EMAIL));

        assertNotNull(basket.getIdent(), "basket ident");
        assertEquals(Boolean.FALSE, basket.getComplete());
        assertTrue(basket.getRows() == null || basket.getRows().isEmpty(), "a new basket is empty");
        assertNotNull(basket.getLinks().getCheckout(), "an unpaid basket has a checkout link");
        checkContract(basket);
    }

    @Test
    @DisplayName("getBasketById returns a basket by its ident")
    void getBasketById() throws ApiException {
        String ident = LiveCheckout.newBasket(checkout);

        Basket basket = checkout.Baskets.getBasketById(ident);

        assertEquals(ident, basket.getIdent());
        checkContract(basket);
    }

    @Test
    @DisplayName("updateBasket changes the customer's details on the basket")
    void updateBasket() throws ApiException {
        String ident = LiveCheckout.newBasket(checkout);

        checkout.Baskets.updateBasket(ident, new UpdateBasketRequest()
                .firstName("Integration")
                .lastName("Test")
                .country("US"));

        Basket basket = checkout.Baskets.getBasketById(ident);
        assertEquals("Integration", basket.getAddress().getFirstName());
        assertEquals("Test", basket.getAddress().getLastName());
        checkContract(basket);
    }

    @Test
    @DisplayName("addPackage adds an inline package and returns the updated basket")
    void addPackage() throws ApiException {
        String ident = LiveCheckout.newBasket(checkout);

        Basket basket = checkout.Baskets.addPackage(ident, new AddPackageRequest()
                ._package(LiveCheckout.oneOffPackage())
                .qty(2)
                .type(AddPackageRequest.TypeEnum.SINGLE));

        assertEquals(ident, basket.getIdent());
        assertEquals(1, basket.getRows().size(), "one row for the package");
        BasketRow row = basket.getRows().get(0);
        assertEquals(Integer.valueOf(2), row.getQuantity());
        assertEquals(LiveCheckout.oneOffPackage().getName(), row.getMeta().getName());
        checkContract(basket);
    }

    @Test
    @DisplayName("removeRowFromBasket removes a row from the basket")
    void removeRowFromBasket() throws ApiException {
        String ident = LiveCheckout.basketWithPackage(checkout);
        Integer rowId = checkout.Baskets.getBasketById(ident).getRows().get(0).getId();

        checkout.Baskets.removeRowFromBasket(ident, rowId);

        Basket basket = checkout.Baskets.getBasketById(ident);
        if (basket.getRows() != null) {
            for (BasketRow row : basket.getRows()) {
                assertFalse(rowId.equals(row.getId()), "row " + rowId + " must be removed");
            }
        }
    }

    @Test
    @DisplayName("addSaleToBasket applies a sale and returns the updated basket")
    void addSaleToBasket() throws ApiException {
        String ident = LiveCheckout.basketWithPackage(checkout);

        Basket basket = checkout.Baskets.addSaleToBasket(ident, new Sale()
                .name("Integration Test Sale")
                .discountType(Sale.DiscountTypeEnum.AMOUNT)
                .amount(new BigDecimal("0.10")));

        assertEquals(ident, basket.getIdent());
        assertFalse(basket.getPriceDetails().getSales().isEmpty(), "the sale must be on the basket");
        checkContract(basket);
    }
}
