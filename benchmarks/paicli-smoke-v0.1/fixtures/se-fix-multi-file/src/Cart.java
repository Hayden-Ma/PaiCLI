import java.util.ArrayList;
import java.util.List;

public class Cart {

    private final List<Integer> itemPrices = new ArrayList<>();
    private String couponCode;

    public void addItem(int price) {
        itemPrices.add(price);
    }

    public void applyCoupon(String couponCode) {
        this.couponCode = couponCode;
    }

    public int itemsTotal() {
        int total = 0;
        for (int price : itemPrices) {
            total += price;
        }
        return total;
    }

    /**
     * 结算总价：应在商品总价基础上套用优惠券折扣。
     */
    public int checkoutTotal() {
        return itemsTotal();
    }
}
