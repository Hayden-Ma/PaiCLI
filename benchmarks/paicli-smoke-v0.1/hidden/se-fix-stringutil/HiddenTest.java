/** 隐藏测试：不在工作区内，被评 agent 不可见。 */
public class HiddenTest {
    public static void main(String[] args) {
        int failures = 0;
        failures += expect("isPalindrome(Level) == true", StringUtils.isPalindrome("Level"));
        failures += expect("isPalindrome(level) == true", StringUtils.isPalindrome("level"));
        failures += expect("isPalindrome(ABba) == true", StringUtils.isPalindrome("ABba"));
        failures += expect("isPalindrome(hello) == false", !StringUtils.isPalindrome("hello"));
        failures += expect("isPalindrome(\"\") == true", StringUtils.isPalindrome(""));
        failures += expect("isPalindrome(null) == false", !StringUtils.isPalindrome(null));
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
