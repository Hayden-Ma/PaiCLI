/** 隐藏测试：不在工作区内，被评 agent 不可见。 */
public class HiddenTest {
    public static void main(String[] args) {
        TemperatureConverter converter = new TemperatureConverter();
        int failures = 0;
        failures += expect("fahrenheitToCelsius(212) == 100.0",
                Math.abs(converter.fahrenheitToCelsius(212.0) - 100.0) < 1e-6);
        failures += expect("fahrenheitToCelsius(32) == 0.0",
                Math.abs(converter.fahrenheitToCelsius(32.0) - 0.0) < 1e-6);
        failures += expect("fahrenheitToCelsius(98.6) ~= 37.0",
                Math.abs(converter.fahrenheitToCelsius(98.6) - 37.0) < 1e-6);
        failures += expect("已有 celsiusToFahrenheit(100) == 212 未被破坏",
                Math.abs(converter.celsiusToFahrenheit(100.0) - 212.0) < 1e-6);
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
