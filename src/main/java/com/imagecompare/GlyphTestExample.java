package com.imagecompare;

public class GlyphTestExample {

    public static void main(String[] args) {
        System.out.println("开始测试字形比较算法...");
        
        // 1. 初始化比较器
        GlyphComparator comparator = new GlyphComparator();

        // 2. 指定两张需要比较的图片路径
        // 注意：这里使用你提供的图片路径
        String image1 = "c:\\Users\\wang\\IdeaProjects\\image-compare\\1.png";
        String image2 = "c:\\Users\\wang\\IdeaProjects\\image-compare\\2.png";

        try {
            // 3. 执行比较
            System.out.println("正在比较: " + image1 + " 和 " + image2);
            CompareResult result = comparator.compare(image1, image2);

            // 4. 获取详细评分
            double totalScore = result.getTotalScore();
            boolean isSameGlyph = result.isSame();
            
            // 打印基础结果
            System.out.println("\n--- 比较结果 ---");
            System.out.printf("综合相似度评分: %.2f / 100%n", totalScore);
            System.out.println("判定结果: " + (isSameGlyph ? "是同一个字形 (相同)" : "不是同一个字形 (不同)"));
            
            System.out.println("\n--- 各项算法详细得分 ---");
            System.out.printf("网格直方图匹配得分 (抗粗细差异): %.2f%%%n", result.getGridScore());
            System.out.printf("距离容忍匹配得分 (抗锯齿位移): %.2f%%%n", result.getDistanceScore());
            System.out.printf("轮廓形状匹配得分 (宏观结构): %.2f%%%n", result.getContourScore());

            // 5. 如果你想看漂亮的表格输出，也可以直接调用这个自带的方法：
//             GlyphComparator.printResult(image1, result);

        } catch (Exception e) {
            System.err.println("比较过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
