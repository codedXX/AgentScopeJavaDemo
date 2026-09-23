package com.example.salesagent.rag;

import com.example.salesagent.model.KnowledgeChunk;
import com.example.salesagent.model.SearchHit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Lucene 关键词索引。建索引和查询都使用 SmartChineseAnalyzer，搜索时采用 BM25 相似度。
 * 每条文档保存分块 ID、正文、来源和序号，以便检索结果恢复为 KnowledgeChunk。
 */
public final class LuceneKeywordIndex implements WritableKeywordIndex, AutoCloseable {
    private final Directory directory;
    private final Analyzer analyzer = new SmartChineseAnalyzer();

    public LuceneKeywordIndex(Path indexDir) {
        try { this.directory = FSDirectory.open(indexDir); }
        catch (IOException e) { throw new IllegalStateException("打开 Lucene 索引失败", e); }
    }

    /** 清空旧关键词索引。 */
    @Override public synchronized void reset() {
        try (IndexWriter writer = writer()) { writer.deleteAll(); writer.commit(); }
        catch (IOException e) { throw new IllegalStateException("清空 Lucene 索引失败", e); }
    }

    /**
     * 将每个分块写为可搜索文档，并以 chunkId 作为更新键。正文参与分词搜索，
     * 来源和序号只作为检索结果元数据保存；写入结束后显式提交索引。
     */
    @Override public synchronized void upsert(List<KnowledgeChunk> chunks) {
        try (IndexWriter writer = writer()) {
            for (KnowledgeChunk chunk : chunks) {
                Document doc = new Document();
                doc.add(new StringField("chunkId", chunk.getChunkId(), Field.Store.YES));
                doc.add(new TextField("text", chunk.getText(), Field.Store.YES));
                doc.add(new StringField("source", chunk.getSource(), Field.Store.YES));
                doc.add(new IntPoint("ordinal", chunk.getOrdinal()));
                doc.add(new StoredField("ordinalStored", chunk.getOrdinal()));
                writer.updateDocument(new org.apache.lucene.index.Term("chunkId", chunk.getChunkId()), doc);
            }
            writer.commit();
        } catch (IOException e) { throw new IllegalStateException("写入 Lucene 索引失败", e); }
    }

    /**
     * 空查询直接返回空列表。先转义 Lucene 特殊字符，再按 BM25 取前 topK 条；
     * 如果磁盘上尚无索引，同样返回空列表而不创建新索引。
     */
    @Override public synchronized List<SearchHit> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        try {
            if (!DirectoryReader.indexExists(directory)) return List.of();
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                Query parsed = new QueryParser("text", analyzer).parse(QueryParser.escape(query));
                ScoreDoc[] docs = searcher.search(parsed, topK).scoreDocs;
                List<SearchHit> result = new ArrayList<>(docs.length);
                for (ScoreDoc scoreDoc : docs) {
                    Document doc = searcher.storedFields().document(scoreDoc.doc);
                    KnowledgeChunk chunk = new KnowledgeChunk(doc.get("chunkId"), doc.get("text"), doc.get("source"),
                            doc.getField("ordinalStored").numericValue().intValue());
                    result.add(new SearchHit(chunk, scoreDoc.score, "bm25"));
                }
                return List.copyOf(result);
            }
        } catch (Exception e) { throw new IllegalStateException("查询 Lucene 索引失败", e); }
    }

    /** 统计索引中的片段数。 */
    @Override public synchronized long count() {
        try {
            if (!DirectoryReader.indexExists(directory)) return 0;
            try (DirectoryReader reader = DirectoryReader.open(directory)) { return reader.numDocs(); }
        } catch (IOException e) { throw new IllegalStateException("读取 Lucene 记录数失败", e); }
    }

    /** 创建写入索引所需的 Lucene Writer。 */
    private IndexWriter writer() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setSimilarity(new BM25Similarity());
        return new IndexWriter(directory, config);
    }

    /** 关闭索引和分词器。 */
    @Override public void close() {
        try { directory.close(); analyzer.close(); }
        catch (IOException e) { throw new IllegalStateException("关闭 Lucene 索引失败", e); }
    }
}
