package io.tebex.headless.integration;

import static io.tebex.headless.integration.LiveStore.checkContract;
import static io.tebex.headless.integration.LiveStore.assertNotEmpty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.tebex.headless.invoker.ApiException;
import io.tebex.headless.model.CMSPagesResponse;
import io.tebex.headless.model.Category;
import io.tebex.headless.model.CategoryResponse;
import io.tebex.headless.model.ModelPackage;
import io.tebex.headless.model.ModulesResponse;
import io.tebex.headless.model.PackageResponse;
import io.tebex.headless.model.SingleCategoryResponse;
import io.tebex.headless.model.SinglePackageResponse;
import io.tebex.headless.model.WebstoreResponse;
import io.tebex.http.HeadlessApi;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The read-only store endpoints: webstore, pages, categories, packages, sidebar. */
class CatalogueTest {

    private HeadlessApi headless;

    @BeforeEach
    void connect() {
        headless = LiveStore.client();
    }

    @Test
    @DisplayName("getWebstore returns the store the token belongs to")
    void getWebstore() throws ApiException {
        WebstoreResponse response = headless.Headless.getWebstore();

        assertNotNull(response.getData(), "webstore data");
        assertNotNull(response.getData().getName(), "webstore name");
        assertNotNull(response.getData().getCurrency(), "webstore currency");
        checkContract(response);
    }

    @Test
    @DisplayName("getCustomPages returns the store's CMS pages")
    void getCustomPages() throws ApiException {
        CMSPagesResponse response = headless.Headless.getCustomPages();

        assertNotNull(response.getData(), "pages list (may be empty)");
        checkContract(response);
    }

    @Test
    @DisplayName("getCategories returns categories without packages")
    void getCategories() throws ApiException {
        CategoryResponse response = headless.Headless.getCategories();

        assertNotEmpty(response.getData(), "categories");
        checkContract(response);
    }

    @Test
    @DisplayName("getCategoriesIncludePackages returns categories with their packages")
    void getCategoriesIncludePackages() throws ApiException {
        CategoryResponse response = headless.Headless.getCategoriesIncludePackages();

        assertNotEmpty(response.getData(), "categories");
        checkContract(response);
    }

    @Test
    @DisplayName("getCategory returns the requested category")
    void getCategory() throws ApiException {
        Category first = firstCategory();

        SingleCategoryResponse response = headless.Headless.getCategory(String.valueOf(first.getId()));

        assertNotNull(response.getData(), "category");
        assertEquals(first.getId(), response.getData().getId());
        checkContract(response);
    }

    @Test
    @DisplayName("getCategoryIncludePackages returns the requested category with its packages")
    void getCategoryIncludePackages() throws ApiException {
        Category first = firstCategory();

        SingleCategoryResponse response = headless.Headless.getCategoryIncludePackages(String.valueOf(first.getId()));

        assertNotNull(response.getData(), "category");
        assertEquals(first.getId(), response.getData().getId());
        assertNotNull(response.getData().getPackages(), "the category's packages");
        checkContract(response);
    }

    @Test
    @DisplayName("getAllPackages returns the store's packages")
    void getAllPackages() throws ApiException {
        PackageResponse response = headless.Headless.getAllPackages();

        assertNotEmpty(response.getData(), "packages");
        checkContract(response);
    }

    @Test
    @DisplayName("getAllPackagesWithAuthedIP returns the store's packages priced for an IP (needs TEBEX_IT_PRIVATE_KEY)")
    void getAllPackagesWithAuthedIP() throws ApiException {
        HeadlessApi authenticated = LiveStore.authenticatedClient();

        // 203.0.113.0/24 is reserved for documentation (RFC 5737).
        PackageResponse response = authenticated.Headless.getAllPackagesWithAuthedIP("203.0.113.1");

        assertNotEmpty(response.getData(), "packages");
        checkContract(response);
    }

    @Test
    @DisplayName("getPackage returns the requested package")
    void getPackage() throws ApiException {
        List<ModelPackage> all = headless.Headless.getAllPackages().getData();
        assumeTrue(all != null && !all.isEmpty(), "the store has no packages");
        Integer id = all.get(0).getId();

        SinglePackageResponse response = headless.Headless.getPackage(String.valueOf(id));

        assertNotNull(response.getData(), "package");
        assertEquals(id, response.getData().getId());
        checkContract(response);
    }

    @Test
    @DisplayName("getSidebar returns the store's sidebar modules")
    void getSidebar() throws ApiException {
        ModulesResponse response = headless.Headless.getSidebar(LiveStore.publicToken());

        assertNotNull(response.getData(), "modules list (may be empty)");
        checkContract(response);
    }

    private Category firstCategory() throws ApiException {
        List<Category> categories = headless.Headless.getCategories().getData();
        assumeTrue(categories != null && !categories.isEmpty(), "the store has no categories");
        return categories.get(0);
    }
}
