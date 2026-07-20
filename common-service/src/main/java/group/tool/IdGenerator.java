package group.tool;/* I love coding */

import java.util.UUID;

public class IdGenerator {

    /**
     * 生成去掉"-"的32位唯一ID
     * 格式：550e8400e29b41d4a716446655440000
     */
    public static String generateShortId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成带前缀的短ID
     * 格式：prefix_550e8400e29b41d4a716446655440000
     *
     * @param prefix ID前缀
     * @return 带前缀的唯一ID
     */
    public static String generateShortId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
