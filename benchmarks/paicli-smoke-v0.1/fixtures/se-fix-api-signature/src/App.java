public class App {

    public static String welcome(Notifier notifier) {
        return notifier.send("boot");
    }

    public static String alert(Notifier notifier) {
        return notifier.send("disk full");
    }
}
