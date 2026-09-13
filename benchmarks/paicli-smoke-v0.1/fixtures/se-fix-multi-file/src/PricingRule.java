public class PricingRule {

    /** 返回优惠券对应的折扣百分比（10 表示 9 折），未知券码返回 0。 */
    public static int discountPercent(String coupon) {
        if ("VIP10".equals(coupon)) {
            return 10;
        }
        if ("VIP20".equals(coupon)) {
            return 20;
        }
        return 0;
    }
}
