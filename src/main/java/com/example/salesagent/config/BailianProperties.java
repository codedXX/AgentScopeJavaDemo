package com.example.salesagent.config;

import java.util.Objects;

public class BailianProperties {
    private String apiKey;
    private String baseUrl;
    private String chatModel;
    private String embeddingModel;
    private String rerankModel;
    private int dimension;
    private int timeoutSeconds;

    public BailianProperties() {
    }

    public BailianProperties(String apiKey, String baseUrl, String chatModel, String embeddingModel,
                             String rerankModel, int dimension, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.rerankModel = rerankModel;
        this.dimension = dimension;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        BailianProperties that = (BailianProperties) object;
        return dimension == that.dimension
                && timeoutSeconds == that.timeoutSeconds
                && Objects.equals(apiKey, that.apiKey)
                && Objects.equals(baseUrl, that.baseUrl)
                && Objects.equals(chatModel, that.chatModel)
                && Objects.equals(embeddingModel, that.embeddingModel)
                && Objects.equals(rerankModel, that.rerankModel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiKey, baseUrl, chatModel, embeddingModel, rerankModel, dimension, timeoutSeconds);
    }

    @Override
    public String toString() {
        return "BailianProperties[baseUrl=" + baseUrl + ", chatModel=" + chatModel
                + ", embeddingModel=" + embeddingModel + ", rerankModel=" + rerankModel
                + ", dimension=" + dimension + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
