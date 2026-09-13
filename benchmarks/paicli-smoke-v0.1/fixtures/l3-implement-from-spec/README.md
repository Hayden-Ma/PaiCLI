# RepeatUtil 规格

在工作区 src 下实现 RepeatUtil 类，提供如下静态方法：

- `public static String repeat(String s, int n)`：返回把 s 重复 n 次拼接后的字符串
- `n <= 0` 时返回空字符串 `""`
- `s` 为 null 时返回空字符串 `""`
- 其他情况按普通语义拼接，例如 `repeat("ab", 3)` 返回 `"ababab"`

工作区 src/RepeatUtilTest.java 是可见的回归检查：实现完成后 `RepeatUtilTest.check()` 必须返回 0。不要修改 RepeatUtilTest。
