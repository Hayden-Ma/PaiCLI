public class StringUtils {
    public static boolean isPalindrome(String input) {
        if (input == null) {
            return false;
        }
        String reversed = new StringBuilder(input).reverse().toString();
        return input.equals(reversed);
    }
}
