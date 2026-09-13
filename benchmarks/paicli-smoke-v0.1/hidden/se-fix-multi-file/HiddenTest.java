/** 隐藏测试：不在工作区内，被评 agent 不可见。 */
public class HiddenTest {
    public static void main(String[] args) {
        int failures = 0;

        Cart vip10 = new Cart();
        vip10.addItem(60);
        vip10.addItem(40);
        vip10.applyCoupon("VIP10");
        failures += expect("VIP10: 100 -> 90", vip10.checkoutTotal() == 90);

        Cart vip20 = new Cart();
        vip20.addItem(50);
        vip20.applyCoupon("VIP20");
        failures += expect("VIP20: 50 -> 40", vip20.checkoutTotal() == 40);

        Cart unknown = new Cart();
        unknown.addItem(80);
        unknown.applyCoupon("FAKE");
        failures += expect("未知券码: 原价 80", unknown.checkoutTotal() == 80);

        Cart none = new Cart();
        none.addItem(80);
        failures += expect("未用券: 原价 80", none.checkoutTotal() == 80);

        System.out.println(failures == 0 ? "ALL PASS" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int expect(String name, boolean condition) {
        if (!condition) {
            System.out.println("FAIL: " + name);
            return 1;
        }
        return 0;
    }
}
