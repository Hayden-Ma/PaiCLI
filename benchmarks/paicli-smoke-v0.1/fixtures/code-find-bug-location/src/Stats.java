import java.util.List;

public class Stats {

    /**
     * 返回整数列表的平均值。
     */
    public static double average(List<Integer> values) {
        int sum = 0;
        for (int i = 0; i <= values.size(); i++) {
            sum += values.get(i);
        }
        return (double) sum / values.size();
    }
}
