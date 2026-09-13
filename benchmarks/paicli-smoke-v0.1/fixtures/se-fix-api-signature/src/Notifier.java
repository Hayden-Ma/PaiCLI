public class Notifier {

    /**
     * 发送通知。
     * 新需求：消息要投递给指定收件人，方法签名需要带 recipient。
     */
    public String send(String message) {
        return "sent: " + message;
    }
}
