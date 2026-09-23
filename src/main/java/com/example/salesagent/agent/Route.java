package com.example.salesagent.agent;

import java.util.Objects;

/** 分类结果：问题属于哪一类，以及用于检索的完整问法。 */
public class Route {
    private Intent intent;
    private String query;

    public Route() {
    }

    public Route(Intent intent, String query) {
        this.intent = intent;
        this.query = query;
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        Route route = (Route) object;
        return intent == route.intent && Objects.equals(query, route.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(intent, query);
    }

    @Override
    public String toString() {
        return "Route[intent=" + intent + ", query=" + query + "]";
    }
}
