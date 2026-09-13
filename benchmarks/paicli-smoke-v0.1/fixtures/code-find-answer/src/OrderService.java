public class OrderService {
    /** 支付失败后的最大自动重试次数。 */
    private static final int MAX_RETRY_ATTEMPTS = 5;

    private static final long RETRY_BACKOFF_MILLIS = 2_000L;

    public boolean shouldRetry(int attempts) {
        return attempts < MAX_RETRY_ATTEMPTS;
    }

    public long backoffMillis() {
        return RETRY_BACKOFF_MILLIS;
    }
}
