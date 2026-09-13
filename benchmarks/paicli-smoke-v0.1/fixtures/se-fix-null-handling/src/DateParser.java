import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class DateParser {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 解析 yyyy-MM-dd 日期。
     * 规格：输入为 null 或空串时应返回 null，而不是抛出异常。
     */
    public static LocalDate parse(String input) {
        return LocalDate.parse(input, FORMAT);
    }
}
