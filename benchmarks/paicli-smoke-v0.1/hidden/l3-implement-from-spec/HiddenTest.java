/** 隐藏测试：不在工作区内，被评 agent 不可见。 */
public class HiddenTest {
    public static void main(String[] args) {
        int failures = 0;
        failures += expect("repeat(ab,3) == ababab", "ababab".equals(RepeatUtil.repeat("ab", 3)));
        failures += expect("repeat(x,0) == 空串", "".equals(RepeatUtil.repeat("x", 0)));
        failures += expect("repeat(x,-2) == 空串", "".equals(RepeatUtil.repeat("x", -2)));
        failures += expect("repeat(null,5) == 空串", "".equals(RepeatUtil.repeat(null, 5)));
        failures += expect("repeat(ab,1) == ab", "ab".equals(RepeatUtil.repeat("ab", 1)));
        failures += expect("RepeatUtilTest.check() == 0", RepeatUtilTest.check() == 0);
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
