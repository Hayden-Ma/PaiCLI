/**
 * 示例业务类，含多个可供审查的问题：
 * 1. dividePrice 除数为 0 时抛 ArithmeticException（缺少防护）
 * 2. applyDiscount 对 null 订单未做防御，可能 NPE
 * 3. 折扣率 0.1 / 0.05 为魔法数字，可维护性差
 */
public class ProductService {

    public double dividePrice(double price, int quantity) {
        return price / quantity;
    }

    public double applyDiscount(Order order) {
        if (order.getType() == "VIP") {
            return order.getAmount() * 0.1;
        }
        return order.getAmount() * 0.05;
    }

    public static class Order {
        private final String type;
        private final double amount;

        public Order(String type, double amount) {
            this.type = type;
            this.amount = amount;
        }

        public String getType() {
            return type;
        }

        public double getAmount() {
            return amount;
        }
    }
}
