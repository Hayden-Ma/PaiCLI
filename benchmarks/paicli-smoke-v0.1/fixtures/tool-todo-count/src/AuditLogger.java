public class AuditLogger {

    public void log(String event) {
        // TODO 异步化写入
        System.out.println(event);
    }

    // TODO 补充审计字段：时间戳与操作人
    public void logSecurity(String event) {
        System.out.println("[SEC] " + event);
    }
}
