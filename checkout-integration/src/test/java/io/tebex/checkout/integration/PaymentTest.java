package io.tebex.checkout.integration;

import static io.tebex.checkout.integration.LiveCheckout.checkContract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.tebex.checkout.invoker.ApiException;
import io.tebex.checkout.model.Payment;
import io.tebex.http.CheckoutApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Single payments: fetch and refund, against payments named by the environment. */
class PaymentTest {

    private CheckoutApi checkout;

    @BeforeEach
    void connect() {
        checkout = LiveCheckout.client();
    }

    @Test
    @DisplayName("getPaymentById returns a payment by its transaction id (needs TEBEX_IT_CHECKOUT_TXN_ID)")
    void getPaymentById() throws ApiException {
        String txnId = LiveCheckout.required("TEBEX_IT_CHECKOUT_TXN_ID");

        Payment payment = checkout.Payments.getPaymentById(txnId);

        assertEquals(txnId, payment.getTransactionId());
        assertNotNull(payment.getStatus(), "payment status");
        checkContract(payment);
    }

    @Test
    @DisplayName("refundPaymentById refunds a completed payment (needs TEBEX_IT_CHECKOUT_REFUND_TXN_ID; irreversible)")
    void refundPaymentById() throws ApiException {
        String txnId = LiveCheckout.required("TEBEX_IT_CHECKOUT_REFUND_TXN_ID");

        Payment payment = checkout.Payments.refundPaymentById(txnId);

        assertEquals(txnId, payment.getTransactionId());
        checkContract(payment);
    }
}
