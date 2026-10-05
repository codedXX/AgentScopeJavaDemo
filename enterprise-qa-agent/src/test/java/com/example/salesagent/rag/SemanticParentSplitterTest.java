// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 引入 Test，用于标记 JUnit 测试方法。
import org.junit.jupiter.api.Test;
// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;
// 验证语义父块按主题分开，同时保留标题并遵守长度约束。
class SemanticParentSplitterTest {
    // 覆盖主题变化、最大长度、标题恢复及零向量拒绝。
    @Test void separatesTopicChangeAndHonorsLengthBound() {
        // 用库存与营养的正交固定向量模拟主题变化；最大父块 600、最小 200、阈值 0.72。
        var splitter=new SemanticParentSplitter(texts->texts.stream().map(t->t.contains("库存")?new float[]{0,1}:new float[]{1,0}).toList(),600,200,.72);
        // 生成足够长的营养主题正文，满足语义断块最小长度。
        String nutrition="蛋白质营养说明。".repeat(25);
        // 生成同样足够长的库存主题正文，与营养主题形成变化。
        String stock="仓库库存管理说明。".repeat(25);
        // 将两个主题放在同一章节中进行语义切分。
        var parents=splitter.split(nutrition+"\n\n"+stock);
        // 断言生成两个父块，且营养与库存分别位于正确父块。
        assertEquals(2,parents.size());assertTrue(parents.get(0).contains("蛋白质"));assertTrue(parents.get(1).contains("库存"));
        // 断言所有无标题父块都不超过 600 字符。
        assertTrue(parents.stream().allMatch(p->p.length()<=600));
        // 给相同正文添加产品 A 章节标题，再次执行切分。
        var headed=splitter.split("# 产品A\n\n"+nutrition+"\n\n"+stock);
        // 断言每个父块都恢复相同章节标题，并将标题长度计入 600 字符上限。
        assertTrue(headed.stream().allMatch(p->p.startsWith("# 产品A") && p.length()<=600));
        // 断言零向量无法计算有效余弦相似度，必须被拒绝。
        assertThrows(IllegalArgumentException.class,()->SemanticParentSplitter.cosine(new float[]{0,0},new float[]{1,0}));
    }
}
