package com.example.salesagent.config;

import java.util.Objects;

public class GithubProperties {
    private String apiUrl;
    private String token;

    public GithubProperties() {
    }

    public GithubProperties(String apiUrl, String token) {
        this.apiUrl = apiUrl;
        this.token = token;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        GithubProperties that = (GithubProperties) object;
        return Objects.equals(apiUrl, that.apiUrl) && Objects.equals(token, that.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiUrl, token);
    }

    @Override
    public String toString() {
        return "GithubProperties[apiUrl=" + apiUrl + "]";
    }
}
