public class PaymentClient {

    private final PaymentGateway gateway;

    public PaymentClient(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    /** 发起支付请求，底层使用网关的超时配置。 */
    public String charge(String orderId) {
        int timeout = gateway.timeout();
        return "charge " + orderId + " with timeout " + timeout;
    }
}
