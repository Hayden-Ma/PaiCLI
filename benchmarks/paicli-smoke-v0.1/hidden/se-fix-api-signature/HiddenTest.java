/** 隐藏测试：不在工作区内，被评 agent 不可见。签名不改则这里直接编译失败。 */
public class HiddenTest {
    public static void main(String[] args) {
        int failures = 0;

        // 旧的一参签名必须被替换而不是保留为重载（堵"加重载不换签名"的捷径）
        boolean legacyOverloadKept = false;
        for (java.lang.reflect.Method m : Notifier.class.getMethods()) {
            if ("send".equals(m.getName()) && m.getParameterCount() == 1) {
                legacyOverloadKept = true;
            }
        }
        failures += expect("旧签名 send(String) 已被替换（不得保留为一参重载）", !legacyOverloadKept);

        Notifier notifier = new Notifier();
        failures += expect("send(bob, hi) == sent[bob]: hi",
                "sent[bob]: hi".equals(notifier.send("bob", "hi")));
        failures += expect("App.welcome 联动更新",
                "sent[server]: boot".equals(App.welcome(notifier)));
        failures += expect("App.alert 联动更新",
                "sent[server]: disk full".equals(App.alert(notifier)));

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
