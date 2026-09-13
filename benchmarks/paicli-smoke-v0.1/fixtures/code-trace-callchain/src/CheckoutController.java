public class CheckoutController {

    private final PaymentClient paymentClient;

    public CheckoutController(PaymentClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    public String checkout(String orderId) {
        return paymentClient.charge(orderId);
    }
}
