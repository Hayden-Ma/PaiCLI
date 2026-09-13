/** 隐藏测试：不在工作区内，被评 agent 不可见。 */
public class HiddenTest {
    public static void main(String[] args) {
        int failures = 0;
        Counter counter = new Counter();
        failures += expect("new Counter().value() == 0", counter.value() == 0);
        counter.increment();
        counter.increment();
        counter.increment();
        failures += expect("increment x3 == 3", counter.value() == 3);
        counter.increment(-5);
        failures += expect("inc(-5) 后保持 0（不为负）", counter.value() == 0);
        counter.increment(2);
        failures += expect("inc(2) == 2", counter.value() == 2);
        counter.reset();
        failures += expect("reset 后 == 0", counter.value() == 0);
        counter.increment(4);
        failures += expect("reset 后 inc(4) == 4", counter.value() == 4);
        counter.increment(-1);
        failures += expect("inc(-1) == 3", counter.value() == 3);
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
