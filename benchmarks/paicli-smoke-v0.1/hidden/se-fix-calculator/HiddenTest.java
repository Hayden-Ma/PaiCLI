/** 隐藏测试：不在工作区内，被评 agent 不可见。 */
public class HiddenTest {
    public static void main(String[] args) {
        Calculator calculator = new Calculator();
        int failures = 0;
        failures += expect("subtract(5,2) == 3", calculator.subtract(5, 2) == 3);
        failures += expect("subtract(0,7) == -7", calculator.subtract(0, 7) == -7);
        failures += expect("subtract(9,9) == 0", calculator.subtract(9, 9) == 0);
        failures += expect("add(2,3) == 5", calculator.add(2, 3) == 5);
        failures += expect("multiply(3,4) == 12", calculator.multiply(3, 4) == 12);
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
