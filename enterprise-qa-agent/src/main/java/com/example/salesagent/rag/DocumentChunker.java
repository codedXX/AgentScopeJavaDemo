// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 Document，用于待切分或待索引的文档对象。
import dev.langchain4j.data.document.Document;
// 引入 DocumentSplitter，用于LangChain4j 文档切分契约。
import dev.langchain4j.data.document.DocumentSplitter;
// 引入 DocumentSplitters，用于递归文档切分器工厂。
import dev.langchain4j.data.document.splitter.DocumentSplitters;
// 引入 IOException，用于文件或网络 I/O 受检异常。
import java.io.IOException;
import java.nio.charset.StandardCharsets;
// 引入 Path，用于安全组合与规范化文件路径。
import java.nio.file.Path;
// 引入 MessageDigest，用于计算稳定的 SHA-256 摘要。
import java.security.MessageDigest;
// 引入 NoSuchAlgorithmException，用于处理运行环境缺少摘要算法。
import java.security.NoSuchAlgorithmException;
// 引入 ArrayList，用于按插入顺序收集可变列表。
import java.util.ArrayList;
// 引入 HexFormat，用于把摘要字节转换为十六进制 ID。
import java.util.HexFormat;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;

/** 提取 PDF / Markdown 正文，并委托 LangChain4j 的字符递归切分器处理中文文本。 */
// 负责将知识文件清洗为子块；可选地保存语义父块，供检索命中后恢复上下文。
public final class DocumentChunker {
    // 规范化后的知识根目录，用于限制读取范围并生成相对来源路径。
    private final Path knowledgeRoot;
    // LangChain4j 递归分块器，控制子块长度与相邻子块的重叠窗口。
    private final DocumentSplitter splitter;
    // 子块 ID 到父文档的持久化映射；为 null 时使用普通分块模式。
    private final ParentDocumentStore parents;
    // 父块最大字符数，必须至少能够容纳一个子块。
    private final int parentSize;
    // 可选语义切分器；通过相邻语句组的向量相似度决定父块边界。
    private SemanticParentSplitter semantic;
    // 构造包含语义父块切分能力的文档切分器；其余参数沿用父子分块配置。
    public DocumentChunker(Path root, int size, int overlap, ParentDocumentStore parents, int parentSize, SemanticParentSplitter semantic) {
        // 先复用父子分块参数校验和初始化，再保存可选的语义切分器。
        this(root,size,overlap,parents,parentSize);this.semantic=semantic;
    }
    // 构造按字符递归生成父块的切分器，并允许不配置父文档存储。
    public DocumentChunker(Path root, int size, int overlap, ParentDocumentStore parents, int parentSize) {
        // 检查子块长度为正、重叠窗口有效，以及父块长度不小于子块长度。
        if (size <= 0 || overlap < 0 || overlap >= size || parentSize < size)
            // 拒绝不能形成有效父子分块的配置，防止切分阶段出现循环或丢失上下文。
            throw new IllegalArgumentException("父子分块参数不合法");
        // 把知识目录转换为规范化绝对路径，使之后的路径包含关系比较具有一致基准。
        this.knowledgeRoot = root.toAbsolutePath().normalize();
        // 创建按段落、句子等层级回退的递归子块切分器。
        this.splitter = DocumentSplitters.recursive(size, overlap);
        // 保存父文档存储和父块长度上限。
        this.parents = parents; this.parentSize = parentSize;
    }
    // 重建知识库前清空旧的父子映射；普通分块模式无需处理。
    public void resetParents() { if (parents != null) parents.reset(); }
    // 在知识索引完成后把父子映射发布到持久化清单。
    public void publishParents() { if (parents != null) parents.publish(); }
    // 检查父子映射记录数是否与本次知识子块数量一致。
    public void verifyParents(long count) {
        // 父子映射缺失时拒绝发布就绪状态，避免检索到子块后无法恢复上下文。
        if (parents != null && parents.childCount()!=count) throw new IllegalStateException("父子文档清单缺失或不一致，需要重建");
    }

    // 构造不保留父文档的基础切分器，兼容只需要固定长度子块的调用方。
    public DocumentChunker(Path knowledgeRoot, int chunkSize, int overlap) {
        // 检查子块大小和重叠大小，使每次切分都能向前推进。
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            // 向调用方说明切分参数的合法范围。
            throw new IllegalArgumentException("chunkSize 必须大于 overlap，且两者不能为负数");
        }
        // 保存基础分块模式的规范化知识根目录。
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
        // 建立指定最大长度和重叠窗口的递归切分规则。
        this.splitter = DocumentSplitters.recursive(chunkSize, overlap);
        // 关闭父块存储，并将父块长度字段初始化为子块长度。
        this.parents = null; this.parentSize = chunkSize;
    }

    // 读取并清洗单个知识文件，返回来源和稳定 ID 完整的知识子块列表。
    public List<KnowledgeChunk> split(Path file) {
        // 规范化输入文件路径，去掉冗余的相对路径层级。
        Path normalizedFile = file.toAbsolutePath().normalize();
        // 判断文件是否位于允许的知识目录之内。
        if (!normalizedFile.startsWith(knowledgeRoot)) {
            // 阻止知识目录外的文件被作为知识资料读取。
            throw new IllegalArgumentException("知识文件必须位于知识目录内: " + file);
        }
        // 记录相对知识目录的来源，并统一使用正斜杠以便跨平台引用。
        String source = knowledgeRoot.relativize(normalizedFile).toString().replace('\\', '/');
        // 声明清洗后的正文变量，读取成功后再赋值。
        String text;
        // 集中处理文件读取产生的受检异常。
        try {
            // PDF 提取文字，Markdown 按 UTF-8 读取，随后共用清洗与分块规则。
            text = KnowledgeDocumentReader.read(normalizedFile)
                    // 将 Windows 和旧式回车换行统一为换行符。
                    .replace("\r\n", "\n").replace('\r', '\n')
                    // 将制表符、纵向制表符、换页符与连续空格压缩成单个空格。
                    .replaceAll("[\\t\\x0B\\f ]+", " ")
                    // 去掉每行首尾的空格，再移除整个正文首尾的空白。
                    .replaceAll(" *\\n *", "\n").trim();
        // 捕获文件不存在、权限不足等读取异常。
        } catch (IOException e) {
            // 把读取失败转为业务状态异常，并保留来源和原始原因便于定位。
            throw new IllegalStateException("读取知识文件失败: " + source, e);
        }
        // 空白文件不生成知识分块，也不调用后续向量模型。
        if (text.isBlank()) return List.of();

        // 按文件中的出现顺序累积分块结果。
        List<KnowledgeChunk> result = new ArrayList<>();
        // 没有父文档存储时，使用基础的递归分块流程。
        if (parents == null) {
            // 把清洗正文包装为 LangChain4j Document 并执行递归切分。
            var segments = splitter.split(Document.from(text));
            // 遍历全部分段，索引作为该文件中的分块序号。
            for (int i = 0; i < segments.size(); i++) {
                // 取得当前分段的文本内容。
                String chunkText = segments.get(i).text();
                // 根据来源、序号和文本生成稳定 ID，保存正文及引用元数据。
                result.add(new KnowledgeChunk(hash(source, i, chunkText), chunkText, source, i));
            }
        // 配置父文档存储时，进入先生成父块再生成子块的流程。
        } else {
            // Markdown 标题先分节；递归切分按段落、句子、字符回退，父块保留章节上下文。
            // 为整个文件的子块维护连续序号。
            int ordinal = 0;
            // 父块不重叠，以减少恢复父文档时的冗余上下文。
            var parentSplitter = DocumentSplitters.recursive(parentSize, 0);
            // 在 Markdown 一至六级标题前分节；零宽匹配保留标题本身。
            for (String section : text.split("(?m)(?=^#{1,6} )")) {
                // 跳过标题边界产生的空白章节。
                if (section.isBlank()) continue;
                // 优先使用语义父块切分；未启用时回退到指定父块长度的递归切分。
                var parentTexts = semantic == null ? parentSplitter.split(Document.from(section)).stream().map(s -> s.text()).toList() : semantic.split(section);
                // 依次处理当前章节产生的每个父块。
                for (String parentText : parentTexts) {
                    // 为父块创建包含来源、当前序号和父块文本的稳定 ID。
                    String parentId = hash(source, ordinal, parentText);
                    // 继续在父块内部生成带重叠的短子块，子块用于精确召回。
                    for (var child : splitter.split(Document.from(parentText))) {
                        // 为当前子块生成稳定 ID。
                        String id = hash(source, ordinal, child.text());
                        // 添加检索子块，并在保存当前序号后递增全文件子块序号。
                        result.add(new KnowledgeChunk(id, child.text(), source, ordinal++));
                        // 保存子块到父块的映射；父块保留章节来源和完整上下文。
                        parents.add(id, new ParentDocumentStore.Parent(parentId, source, parentText));
                    }
                }
            }
        }
        // 返回不可修改的结果快照，避免外部调用方更改切分集合。
        return List.copyOf(result);
    }

    // 用 SHA-256 摘要生成分块标识，同源同序号同文本会获得相同 ID。
    private static String hash(String source, int ordinal, String text) {
        // 处理摘要算法在运行环境中不可用的情况。
        try {
            // 获取 JDK 内置的 SHA-256 摘要计算器。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 以换行分隔来源、序号和正文，计算摘要并转成十六进制文本。
            return HexFormat.of().formatHex(digest.digest((source + "\n" + ordinal + "\n" + text)
                    // 使用 UTF-8 把摘要输入编码为字节，保证中文文本的 ID 可重复。
                    .getBytes(StandardCharsets.UTF_8)));
        // 捕获运行时未提供 SHA-256 算法的异常。
        } catch (NoSuchAlgorithmException e) {
            // 把不完整的 JDK 能力报告为状态错误，并保留原始异常。
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
