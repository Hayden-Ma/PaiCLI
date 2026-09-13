public class GreeterTest {

    /**
     * 回归检查：返回 0 表示全部断言通过。
     * 注意：这里的期望值需要与 Greeter 的当前行为保持一致。
     */
    public static int check() {
        int failures = 0;
        if (!new Greeter().greet().equals("Howdy!")) {
            System.out.println("FAIL: greet 期望 Howdy!");
            failures++;
        }
        return failures;
    }
}
