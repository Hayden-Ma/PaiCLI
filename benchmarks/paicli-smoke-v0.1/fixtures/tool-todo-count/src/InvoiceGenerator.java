public class InvoiceGenerator {

    // TODO 支持多币种金额
    public String generate(int amount) {
        // TODO 输出改为 PDF 格式
        return "INVOICE-" + amount;
    }

    /** 归档发票。TODO: 接入对象存储 */
    public void archive(String id) {
    }
}
