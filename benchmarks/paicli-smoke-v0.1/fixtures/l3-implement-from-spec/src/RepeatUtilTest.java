public class RepeatUtilTest {

    /** 回归检查：返回 0 表示全部通过。不要修改本文件。 */
    public static int check() {
        int failures = 0;
        if (!"aaaa".equals(RepeatUtil.repeat("a", 4))) {
            System.out.println("FAIL: repeat(a,4)");
            failures++;
        }
        return failures;
    }
}
