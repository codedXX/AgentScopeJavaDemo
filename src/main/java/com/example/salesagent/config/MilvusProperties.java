package com.example.salesagent.config;

import java.util.Objects;

/** Milvus 向量库的连接和集合配置。 */
public class MilvusProperties {
    private String uri;
    private String token;
    private String collection;

    public MilvusProperties() {
    }

    public MilvusProperties(String uri, String token, String collection) {
        this.uri = uri;
        this.token = token;
        this.collection = collection;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        MilvusProperties that = (MilvusProperties) object;
        return Objects.equals(uri, that.uri)
                && Objects.equals(token, that.token)
                && Objects.equals(collection, that.collection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, token, collection);
    }

    @Override
    public String toString() {
        return "MilvusProperties[uri=" + uri + ", collection=" + collection + "]";
    }
}
