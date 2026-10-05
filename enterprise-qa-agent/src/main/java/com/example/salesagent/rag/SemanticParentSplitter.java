// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;
// 引入 EmbeddingClient，用于可替换的文本向量化接口。
import com.example.salesagent.bailian.EmbeddingClient;
// 引入 Document，用于待切分或待索引的文档对象。
import dev.langchain4j.data.document.Document;
// 引入 DocumentSplitters，用于递归文档切分器工厂。
import dev.langchain4j.data.document.splitter.DocumentSplitters;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
/** 用相邻语句组的 Embedding 相似度确定父块边界，长度上限始终生效。 */
// 按照相邻语句组的语义变化生成父块，同时遵守最大字符长度。
public final class SemanticParentSplitter {
    // 向量化客户端，用于比较相邻语句组的主题相似度。
    private final EmbeddingClient embedding;
    // 父块最大长度与允许按主题断开的最小正文长度。
    private final int maxSize,minSize;
    // 余弦相似度阈值，低于该值表示主题可能发生切换。
    private final double threshold;
    // 构造语义父块切分器并接收模型与长度、阈值参数。
    public SemanticParentSplitter(EmbeddingClient embedding,int maxSize,int minSize,double threshold) {
        // 最小长度必须为正、不能超过最大长度；余弦阈值限定为 [-1,1]。
        if(maxSize<minSize || minSize<1 || threshold< -1 || threshold>1) throw new IllegalArgumentException("语义分块参数不合法");
        // 保存向量模型及所有父块边界参数。
        this.embedding=embedding;this.maxSize=maxSize;this.minSize=minSize;this.threshold=threshold;
    }
    // 将单个 Markdown 章节切分为保留标题的语义父块。
    public List<String> split(String section) {
        // 先把标题设为空，支持无 Markdown 标题的正文。
        String heading="";
        // 定位第一行结束位置，用于判断章节开头是否为标题。
        int newline=section.indexOf('\n');
        // 仅提取符合一至六级 Markdown 标题格式的首行。
        if(newline>0 && section.substring(0,newline).matches("#{1,6} .*")) {
            // 标题最多占 240 字符或父块长度的四分之一，并附加段落间隔。
            heading=section.substring(0,Math.min(newline,Math.min(240,maxSize/4)))+"\n\n";
            // 从正文移除已提取标题行并清除首尾空白。
            section=section.substring(newline+1).strip();
        }
        // 只剩标题或空白时，返回去掉额外空白的标题块。
        if(section.isBlank()) return List.of(heading.strip());
        // 从父块总长度中扣除标题占用，计算正文可用空间。
        int bodyLimit=maxSize-heading.length();
        // 先生成不重叠、长度至多 240 字符且不超正文上限的语义单位。
        var units=DocumentSplitters.recursive(Math.min(240,bodyLimit),0).split(Document.from(section))
            // 只保留单位正文，后续按相邻向量比较进行合并。
            .stream().map(s->s.text()).toList();
        // 只有一个单位时无需向量化，直接补上标题返回。
        if(units.size()<2) return List.of(heading+units.getFirst());
        // 批量向量化所有语义单位，保留与单位一致的顺序。
        var vectors=embedding.embed(units);
        // 检查向量与单位数量对应，避免比较错配的主题向量。
        if(vectors.size()!=units.size()) throw new IllegalStateException("语义分块向量数量不匹配");
        // 准备父块结果集合，并用第一个单位初始化当前正文缓冲。
        var parents=new ArrayList<String>();var current=new StringBuilder(units.getFirst());
        // 从第二个单位开始依次判断是否继续拼接到当前父块。
        for(int i=1;i<units.size();i++) {
            // 计算相邻两个单位的余弦相似度。
            double similarity=cosine(vectors.get(i-1),vectors.get(i));
            // 超出正文长度上限时强制切块；达到最小长度且语义低于阈值时按主题切块。
            if(current.length()+units.get(i).length()+2>bodyLimit || current.length()>=minSize && similarity<threshold) {
                // 发布已有正文并保留章节标题，再清空缓冲以开始新父块。
                parents.add(heading+current);current.setLength(0);
            }
            // 已有正文时加入双换行，再追加当前语义单位。
            if(!current.isEmpty()) current.append("\n\n");current.append(units.get(i));
        }
        // 循环结束后保存尚未发布的最后一块。
        if(!current.isEmpty()) parents.add(heading+current);
        // 返回不可修改的父块列表。
        return List.copyOf(parents);
    }
    // 计算两个同维非零向量的余弦相似度。
    static double cosine(float[] a,float[] b) {
        // 拒绝空引用、空向量或不一致的维度。
        if(a==null || b==null || a.length!=b.length || a.length==0) throw new IllegalArgumentException("语义向量维度不匹配");
        // 分别累积点积和两个向量的平方范数。
        double dot=0,aa=0,bb=0;
        // 逐维检查有限数值，防止 NaN 或无穷数进入相似度计算。
        for(int i=0;i<a.length;i++) {if(!Float.isFinite(a[i])||!Float.isFinite(b[i])) throw new IllegalArgumentException("无效语义向量");
            // 先转换为 double 再累积点积和平方和，降低 float 运算的误差。
            dot+=(double)a[i]*b[i];aa+=(double)a[i]*a[i];bb+=(double)b[i]*b[i];}
        // 零范数无法定义余弦相似度，必须拒绝。
        if(aa==0 || bb==0) throw new IllegalArgumentException("零语义向量");
        // 返回点积除以两个向量范数之积，得到主题相似度。
        return dot/Math.sqrt(aa*bb);
    }
}
