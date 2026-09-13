/** 隐藏测试：不在工作区内，被评 agent 不可见。 */
public class HiddenTest {
    public static void main(String[] args) {
        int failures = 0;
        failures += expect("parse(2026-09-13) 正常解析",
                java.time.LocalDate.of(2026, 9, 13).equals(DateParser.parse("2026-09-13")));
        failures += expect("parse(null) == null", DateParser.parse(null) == null);
        failures += expect("parse(\"\") == null", DateParser.parse("") == null);
        failures += expect("parse(\"  \") == null", DateParser.parse("  ") == null);
        boolean threw = false;
        try {
            DateParser.parse("not-a-date");
        } catch (java.time.format.DateTimeParseException e) {
            threw = true;
        }
        failures += expect("非法非空输入仍抛 DateTimeParseException", threw);
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
