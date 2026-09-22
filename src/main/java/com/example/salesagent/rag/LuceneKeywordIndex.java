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
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/** 使用相同的中文分析器完成 BM25 建索引和查询。 */
public final class LuceneKeywordIndex implements WritableKeywordIndex, AutoCloseable {
    private final Directory directory;
    private final Analyzer analyzer = new SmartChineseAnalyzer();

    public LuceneKeywordIndex(Path indexDir) {
        try { this.directory = FSDirectory.open(indexDir); }
        catch (IOException e) { throw new IllegalStateException("打开 Lucene 索引失败", e); }
    }

    @Override public synchronized void reset() {
        try (IndexWriter writer = writer()) { writer.deleteAll(); writer.commit(); }
        catch (IOException e) { throw new IllegalStateException("清空 Lucene 索引失败", e); }
    }

    @Override public synchronized void upsert(List<KnowledgeChunk> chunks) {
        try (IndexWriter writer = writer()) {
            for (KnowledgeChunk chunk : chunks) {
                Document doc = new Document();
                doc.add(new StringField("chunkId", chunk.chunkId(), Field.Store.YES));
                doc.add(new TextField("text", chunk.text(), Field.Store.YES));
                doc.add(new StringField("source", chunk.source(), Field.Store.YES));
                doc.add(new IntPoint("ordinal", chunk.ordinal()));
                doc.add(new StoredField("ordinalStored", chunk.ordinal()));
                writer.updateDocument(new org.apache.lucene.index.Term("chunkId", chunk.chunkId()), doc);
            }
            writer.commit();
        } catch (IOException e) { throw new IllegalStateException("写入 Lucene 索引失败", e); }
    }

    @Override public synchronized List<SearchHit> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        try {
            if (!DirectoryReader.indexExists(directory)) return List.of();
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                var parsed = new QueryParser("text", analyzer).parse(QueryParser.escape(query));
                var docs = searcher.search(parsed, topK).scoreDocs;
                List<SearchHit> result = new ArrayList<>(docs.length);
                for (var scoreDoc : docs) {
                    Document doc = searcher.storedFields().document(scoreDoc.doc);
                    KnowledgeChunk chunk = new KnowledgeChunk(doc.get("chunkId"), doc.get("text"), doc.get("source"),
                            doc.getField("ordinalStored").numericValue().intValue());
                    result.add(new SearchHit(chunk, scoreDoc.score, "bm25"));
                }
                return List.copyOf(result);
            }
        } catch (Exception e) { throw new IllegalStateException("查询 Lucene 索引失败", e); }
    }

    @Override public synchronized long count() {
        try {
            if (!DirectoryReader.indexExists(directory)) return 0;
            try (DirectoryReader reader = DirectoryReader.open(directory)) { return reader.numDocs(); }
        } catch (IOException e) { throw new IllegalStateException("读取 Lucene 记录数失败", e); }
    }

    private IndexWriter writer() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setSimilarity(new BM25Similarity());
        return new IndexWriter(directory, config);
    }

    @Override public void close() {
        try { directory.close(); analyzer.close(); }
        catch (IOException e) { throw new IllegalStateException("关闭 Lucene 索引失败", e); }
    }
}
