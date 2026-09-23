package com.example.salesagent.config;

import java.util.Objects;

public class McpProperties {
    private String url;
    private String businessUrl;

    public McpProperties() {
    }

    public McpProperties(String url, String businessUrl) {
        this.url = url;
        this.businessUrl = businessUrl;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getBusinessUrl() {
        return businessUrl;
    }

    public void setBusinessUrl(String businessUrl) {
        this.businessUrl = businessUrl;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        McpProperties that = (McpProperties) object;
        return Objects.equals(url, that.url) && Objects.equals(businessUrl, that.businessUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, businessUrl);
    }

    @Override
    public String toString() {
        return "McpProperties[url=" + url + ", businessUrl=" + businessUrl + "]";
    }
}
