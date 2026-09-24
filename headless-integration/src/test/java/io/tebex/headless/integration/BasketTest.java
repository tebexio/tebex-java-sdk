package io.tebex.headless.integration;

import static io.tebex.headless.integration.LiveStore.checkContract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tebex.headless.invoker.ApiException;
import io.tebex.headless.model.AddBasketPackageRequest;
import io.tebex.headless.model.ApplyCoupon200Response;
import io.tebex.headless.model.ApplyCouponRequest;
import io.tebex.headless.model.ApplyCreatorCode200Response;
import io.tebex.headless.model.ApplyCreatorCodeRequest;
import io.tebex.headless.model.ApplyGiftCard200Response;
import io.tebex.headless.model.Basket;
import io.tebex.headless.model.BasketAuthResponseInner;
import io.tebex.headless.model.BasketPackage;
import io.tebex.headless.model.BasketResponse;
import io.tebex.headless.model.CategoryResponse;
import io.tebex.headless.model.GiftCard;
import io.tebex.headless.model.PackageResponse;
import io.tebex.headless.model.RemoveBasketPackageRequest;
import io.tebex.headless.model.RemoveGiftCardRequest;
import io.tebex.headless.model.SingleCategoryResponse;
import io.tebex.headless.model.UpdatePackageQuantityRequest;
import io.tebex.http.HeadlessApi;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The basket lifecycle: create, read, add/update/remove packages, codes, and auth. */
class BasketTest {

    private HeadlessApi headless;

    @BeforeEach
    void connect() {
        headless = LiveStore.client();
    }

    @Test
    @DisplayName("createBasket returns a new, empty basket")
    void createBasket() throws ApiException {
        BasketResponse response = headless.Headless.createBasket(new io.tebex.headless.model.CreateBasketRequest()
                .completeUrl(LiveStore.RETURN_URL + "/complete")
                .cancelUrl(LiveStore.RETURN_URL + "/cancel"));

        Basket basket = response.getData();
        assertNotNull(basket.getIdent(), "basket ident");
        assertEquals(Boolean.FALSE, basket.getComplete());
        assertTrue(basket.getPackages() == null || basket.getPackages().isEmpty(), "a new basket is empty");
        checkContract(response);
    }

    @Test
    @DisplayName("getBasket returns a basket by its ident")
    void getBasket() throws ApiException {
        String ident = LiveStore.newBasket(headless);

        BasketResponse response = headless.Headless.getBasket(ident);

        assertEquals(ident, response.getData().getIdent());
        checkContract(response);
    }

    @Test
    @DisplayName("addBasketPackage adds a package and returns the updated basket")
    void addBasketPackage() throws ApiException {
        String ident = LiveStore.newBasket(headless);
        String packageId = LiveStore.packageId(headless);

        BasketResponse response = headless.Baskets.addBasketPackage(ident,
                new AddBasketPackageRequest().packageId(packageId).quantity(1));

        Basket basket = response.getData();
        assertEquals(ident, basket.getIdent());
        BasketPackage added = find(basket, packageId);
        assertEquals(Integer.valueOf(1), added.getInBasket().getQuantity());
        assertNotNull(basket.getLinks().getCheckout(), "an unpaid basket with a package has a checkout link");
        checkContract(response);
    }

    @Test
    @DisplayName("updatePackageQuantity changes a package's quantity in the basket")
    void updatePackageQuantity() throws ApiException {
        String ident = LiveStore.newBasket(headless);
        String packageId = LiveStore.packageId(headless);
        headless.Baskets.addBasketPackage(ident, new AddBasketPackageRequest().packageId(packageId).quantity(1));

        headless.Baskets.updatePackageQuantity(ident, packageId, new UpdatePackageQuantityRequest().quantity(2));

        Basket basket = headless.Headless.getBasket(ident).getData();
        assertEquals(Integer.valueOf(2), find(basket, packageId).getInBasket().getQuantity());
    }

    @Test
    @DisplayName("removeBasketPackage removes a package and returns the updated basket")
    void removeBasketPackage() throws ApiException {
        String ident = LiveStore.newBasket(headless);
        String packageId = LiveStore.packageId(headless);
        headless.Baskets.addBasketPackage(ident, new AddBasketPackageRequest().packageId(packageId).quantity(1));

        BasketResponse response = headless.Baskets.removeBasketPackage(ident,
                new RemoveBasketPackageRequest().packageId(packageId));

        assertEquals(ident, response.getData().getIdent());
        for (BasketPackage p : response.getData().getPackages()) {
            assertTrue(!packageId.equals(String.valueOf(p.getId())), "package " + packageId + " must be removed");
        }
        checkContract(response);
    }

    @Test
    @DisplayName("getPackagesForBasket returns the store's packages in the context of a basket")
    void getPackagesForBasket() throws ApiException {
        String ident = LiveStore.newBasket(headless);

        PackageResponse response = headless.Headless.getPackagesForBasket(ident);

        LiveStore.assertNotEmpty(response.getData(), "packages");
        checkContract(response);
    }

    @Test
    @DisplayName("getAllPackagesWithAuthedIPAndBasket returns packages for a basket and IP (needs TEBEX_IT_PRIVATE_KEY)")
    void getAllPackagesWithAuthedIPAndBasket() throws ApiException {
        HeadlessApi authenticated = LiveStore.authenticatedClient();
        String ident = LiveStore.newBasket(authenticated);

        PackageResponse response = authenticated.Headless.getAllPackagesWithAuthedIPAndBasket(ident, "203.0.113.1");

        LiveStore.assertNotEmpty(response.getData(), "packages");
        checkContract(response);
    }

    @Test
    @DisplayName("getDynamicCategories returns categories in the context of a basket")
    void getDynamicCategories() throws ApiException {
        String ident = LiveStore.newBasket(headless);

        CategoryResponse response = headless.Headless.getDynamicCategories(ident);

        LiveStore.assertNotEmpty(response.getData(), "categories");
        checkContract(response);
    }

    @Test
    @DisplayName("getCategoryIncludeDynamicPackages returns a category in the context of a basket")
    void getCategoryIncludeDynamicPackages() throws ApiException {
        String ident = LiveStore.newBasket(headless);
        Integer categoryId = headless.Headless.getCategories().getData().get(0).getId();

        SingleCategoryResponse response = headless.Headless.getCategoryIncludeDynamicPackages(
                String.valueOf(categoryId), ident);

        assertNotNull(response.getData(), "category");
        assertEquals(categoryId, response.getData().getId());
        checkContract(response);
    }

    @Test
    @DisplayName("getBasketAuthUrl returns the login links for a basket (empty if the store has no login)")
    void getBasketAuthUrl() throws ApiException {
        String ident = LiveStore.newBasket(headless);

        List<BasketAuthResponseInner> links = headless.Headless.getBasketAuthUrl(ident, LiveStore.RETURN_URL);

        assertNotNull(links, "auth links");
        for (BasketAuthResponseInner link : links) {
            assertNotNull(link.getUrl(), "auth link url");
            checkContract(link);
        }
    }

    @Test
    @DisplayName("applyCoupon and removeCoupon apply and remove a coupon on a basket (needs TEBEX_IT_COUPON_CODE)")
    void coupon() throws ApiException {
        String code = LiveStore.required("TEBEX_IT_COUPON_CODE");
        String ident = basketWithPackage();

        ApplyCoupon200Response applied = headless.Headless.applyCoupon(ident, new ApplyCouponRequest().couponCode(code));
        assertEquals(Boolean.TRUE, applied.getSuccess(), "coupon must apply: " + applied.getMessage());
        checkContract(applied);
        checkContract(headless.Headless.getBasket(ident));

        headless.Headless.removeCoupon(ident, new ApplyCouponRequest().couponCode(code));
    }

    @Test
    @DisplayName("applyCreatorCode and removeCreatorCode apply and remove a creator code on a basket (needs TEBEX_IT_CREATOR_CODE)")
    void creatorCode() throws ApiException {
        String code = LiveStore.required("TEBEX_IT_CREATOR_CODE");
        String ident = basketWithPackage();

        ApplyCreatorCode200Response applied = headless.Headless.applyCreatorCode(ident,
                new ApplyCreatorCodeRequest().creatorCode(code));
        assertEquals(Boolean.TRUE, applied.getSuccess(), "creator code must apply: " + applied.getMessage());
        checkContract(applied);
        assertEquals(code, headless.Headless.getBasket(ident).getData().getCreatorCode());

        headless.Headless.removeCreatorCode(ident);
    }

    @Test
    @DisplayName("applyGiftCard and removeGiftCard apply and remove a gift card on a basket (needs TEBEX_IT_GIFT_CARD)")
    void giftCard() throws ApiException {
        String card = LiveStore.required("TEBEX_IT_GIFT_CARD");
        String ident = basketWithPackage();

        ApplyGiftCard200Response applied = headless.Headless.applyGiftCard(ident, new GiftCard().cardNumber(card));
        assertEquals(Boolean.TRUE, applied.getSuccess(), "gift card must apply: " + applied.getMessage());
        checkContract(applied);
        checkContract(headless.Headless.getBasket(ident));

        headless.Headless.removeGiftCard(ident, new RemoveGiftCardRequest().cardNumber(card));
    }

    private String basketWithPackage() throws ApiException {
        String ident = LiveStore.newBasket(headless);
        headless.Baskets.addBasketPackage(ident,
                new AddBasketPackageRequest().packageId(LiveStore.packageId(headless)).quantity(1));
        return ident;
    }

    private static BasketPackage find(Basket basket, String packageId) {
        for (BasketPackage p : basket.getPackages()) {
            if (packageId.equals(String.valueOf(p.getId()))) {
                return p;
            }
        }
        throw new AssertionError("package " + packageId + " is not in basket " + basket.getIdent());
    }
}
