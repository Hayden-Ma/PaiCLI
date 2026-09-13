public class Alpha {

    private final Gamma gamma = new Gamma();

    public String name() {
        return "alpha+" + gamma.name();
    }
}
