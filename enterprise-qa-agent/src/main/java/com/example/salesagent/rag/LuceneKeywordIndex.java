// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 SearchHit，用于检索命中分块、分数和通道。
import com.example.salesagent.model.SearchHit;
// 引入 IOException，用于文件或网络 I/O 受检异常。
import java.io.IOException;
// 引入 Path，用于安全组合与规范化文件路径。
import java.nio.file.Path;
// 引入 ArrayList，用于按插入顺序收集可变列表。
import java.util.ArrayList;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;
// 引入 Analyzer，用于Lucene 文本分析器抽象。
import org.apache.lucene.analysis.Analyzer;
// 引入 SmartChineseAnalyzer，用于统一中文建库与查询分词。
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
// 引入 Document，用于待切分或待索引的文档对象。
import org.apache.lucene.document.Document;
// 引入 Field，用于指定 Lucene 字段是否存储。
import org.apache.lucene.document.Field;
// 引入 IntPoint，用于索引分块序号的整数字段。
import org.apache.lucene.document.IntPoint;
// 引入 StoredField，用于保存可从命中结果恢复的元数据。
import org.apache.lucene.document.StoredField;
// 引入 StringField，用于存储不分词的标识和来源。
import org.apache.lucene.document.StringField;
// 引入 TextField，用于索引需要中文分词的正文。
import org.apache.lucene.document.TextField;
// 引入 DirectoryReader，用于打开 Lucene 索引只读快照。
import org.apache.lucene.index.DirectoryReader;
// 引入 IndexWriter，用于更新与提交 Lucene 文档。
import org.apache.lucene.index.IndexWriter;
// 引入 IndexWriterConfig，用于设置分词器和 BM25 相似度。
import org.apache.lucene.index.IndexWriterConfig;
// 引入 QueryParser，用于解析并转义用户关键词查询。
import org.apache.lucene.queryparser.classic.QueryParser;
// 引入 IndexSearcher，用于执行 Lucene TopK 检索。
import org.apache.lucene.search.IndexSearcher;
// 引入 BM25Similarity，用于按 BM25 计算词项相关性。
import org.apache.lucene.search.similarities.BM25Similarity;
// 引入 Directory，用于Lucene 索引目录抽象。
import org.apache.lucene.store.Directory;
// 引入 FSDirectory，用于将 Lucene 索引保存到文件系统。
import org.apache.lucene.store.FSDirectory;

/** 使用相同的中文分析器完成 BM25 建索引和查询。 */
// 实现基于中文分词和 BM25 的可写关键词索引；支持自动关闭。
public final class LuceneKeywordIndex implements WritableKeywordIndex, AutoCloseable {
    // Lucene 文件系统索引目录句柄，供读写器共用。
    private final Directory directory;
    // 建库和查询复用同一中文分析器，保证词项解释一致。
    private final Analyzer analyzer = new SmartChineseAnalyzer();

    // 按配置目录打开磁盘中的 Lucene 索引。
    public LuceneKeywordIndex(Path indexDir) {
        // 创建文件系统目录实现并保存目录句柄。
        try { this.directory = FSDirectory.open(indexDir); }
        // 将目录打开失败包装为业务状态异常。
        catch (IOException e) { throw new IllegalStateException("打开 Lucene 索引失败", e); }
    }

    // 同步清空已有索引，供知识库全量重建使用。
    @Override public synchronized void reset() {
        // 在自动关闭的写入器中删除全部文档并提交变更。
        try (IndexWriter writer = writer()) { writer.deleteAll(); writer.commit(); }
        // 向上层报告清空索引产生的 I/O 失败。
        catch (IOException e) { throw new IllegalStateException("清空 Lucene 索引失败", e); }
    }

    // 同步写入全部知识分块，以 chunkId 进行幂等更新。
    @Override public synchronized void upsert(List<KnowledgeChunk> chunks) {
        // 打开写入器，并保证操作结束后关闭。
        try (IndexWriter writer = writer()) {
            // 逐条把知识分块转换为 Lucene 文档。
            for (KnowledgeChunk chunk : chunks) {
                // 创建当前子块对应的索引文档容器。
                Document doc = new Document();
                // chunkId 不分词并持久化，既用于幂等更新，也用于恢复命中标识。
                doc.add(new StringField("chunkId", chunk.chunkId(), Field.Store.YES));
                // 正文作为可分词 TextField 建索引，同时保留原文本作为回答证据。
                doc.add(new TextField("text", chunk.text(), Field.Store.YES));
                // 来源路径不分词并持久化，便于结果引用。
                doc.add(new StringField("source", chunk.source(), Field.Store.YES));
                // 将序号建立为数值字段，保留按序号筛选的能力。
                doc.add(new IntPoint("ordinal", chunk.ordinal()));
                // 另外保存序号值，因为 IntPoint 字段本身不能用于恢复原值。
                doc.add(new StoredField("ordinalStored", chunk.ordinal()));
                // 根据精确 chunkId 更新文档，已存在则替换，否则新增。
                writer.updateDocument(new org.apache.lucene.index.Term("chunkId", chunk.chunkId()), doc);
            }
            // 提交本批全部更新，使新读者看到完整文档集合。
            writer.commit();
        // 将索引写入或提交失败报告给重建服务。
        } catch (IOException e) { throw new IllegalStateException("写入 Lucene 索引失败", e); }
    }

    // 同步执行关键词 TopK 检索，返回含引用元数据的命中。
    @Override public synchronized List<SearchHit> search(String query, int topK) {
        // 空查询、空白查询或无效 TopK 不产生检索结果。
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        // 统一处理索引打开、查询解析与检索异常。
        try {
            // 尚未建立索引时返回空结果，而不打开不存在的读取快照。
            if (!DirectoryReader.indexExists(directory)) return List.of();
            // 为本次查询打开只读快照，结束后自动释放。
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                // 使用快照构造搜索器。
                IndexSearcher searcher = new IndexSearcher(reader);
                // 显式启用与建库一致的 BM25 相关性计算。
                searcher.setSimilarity(new BM25Similarity());
                // 转义用户查询中的 Lucene 语法字符，再用中文分析器解析正文查询。
                var parsed = new QueryParser("text", analyzer).parse(QueryParser.escape(query));
                // 取排名靠前的 topK 条文档及其 BM25 分数。
                var docs = searcher.search(parsed, topK).scoreDocs;
                // 按实际命中数量预分配结果列表。
                List<SearchHit> result = new ArrayList<>(docs.length);
                // 逐条读取有序命中的已存储字段。
                for (var scoreDoc : docs) {
                    // 根据 Lucene 内部文档号取得原始存储字段。
                    Document doc = searcher.storedFields().document(scoreDoc.doc);
                    // 从已存储 ID、正文和来源恢复业务知识分块。
                    KnowledgeChunk chunk = new KnowledgeChunk(doc.get("chunkId"), doc.get("text"), doc.get("source"),
                            // 从 ordinalStored 字段还原该分块在文件中的序号。
                            doc.getField("ordinalStored").numericValue().intValue());
                    // 保留 BM25 分数并标记关键词召回通道。
                    result.add(new SearchHit(chunk, scoreDoc.score, "bm25"));
                }
                // 返回不可修改的命中快照，防止调用方更改排名结果。
                return List.copyOf(result);
            }
        // 解析或检索失败时统一报告关键词查询错误。
        } catch (Exception e) { throw new IllegalStateException("查询 Lucene 索引失败", e); }
    }

    // 同步读取有效索引文档数量，用于与向量索引核对。
    @Override public synchronized long count() {
        // 在异常处理范围内检查并打开索引。
        try {
            // 索引还未创建时记录数为零。
            if (!DirectoryReader.indexExists(directory)) return 0;
            // 使用自动关闭的读取器返回未删除文档数。
            try (DirectoryReader reader = DirectoryReader.open(directory)) { return reader.numDocs(); }
        // 将索引计数失败包装为状态异常。
        } catch (IOException e) { throw new IllegalStateException("读取 Lucene 记录数失败", e); }
    }

    // 建立配置相同的 Lucene 写入器，交由调用方关闭。
    private IndexWriter writer() throws IOException {
        // 给写入器配置复用的中文分析器。
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        // 让写入端显式使用 BM25，和查询端保持一致。
        config.setSimilarity(new BM25Similarity());
        // 基于文件目录及配置创建写入器。
        return new IndexWriter(directory, config);
    }

    // 关闭索引封装持有的长期资源。
    @Override public void close() {
        // 依次释放目录句柄和中文分析器。
        try { directory.close(); analyzer.close(); }
        // 关闭文件系统索引失败时保留原始原因。
        catch (IOException e) { throw new IllegalStateException("关闭 Lucene 索引失败", e); }
    }
}
