public class Beta {

    private final Alpha alpha = new Alpha();

    public String describe() {
        return "beta of " + alpha.name();
    }
}
