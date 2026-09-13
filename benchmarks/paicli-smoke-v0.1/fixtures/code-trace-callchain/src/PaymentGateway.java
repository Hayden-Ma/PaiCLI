public class PaymentGateway {

    /** 网关连接超时（毫秒）。 */
    public static final int DEFAULT_TIMEOUT_MILLIS = 8000;

    public int timeout() {
        return DEFAULT_TIMEOUT_MILLIS;
    }
}
