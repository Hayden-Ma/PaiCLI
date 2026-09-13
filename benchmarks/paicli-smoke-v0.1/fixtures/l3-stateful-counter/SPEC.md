# Counter 规格

在工作区 src 下实现 Counter 类，严格按以下语义：

- `public Counter()`：初始计数为 0
- `public void increment()`：计数加 1
- `public void increment(int step)`：计数加 step（step 可以为负数）
- `public int value()`：返回当前计数
- `public void reset()`：计数归零
- 边界：计数不允许为负——任何一次增量后如果结果小于 0，计数保持为 0
