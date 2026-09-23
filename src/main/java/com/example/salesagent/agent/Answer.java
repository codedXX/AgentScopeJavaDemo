package com.example.salesagent.agent;

import java.util.List;
import java.util.Objects;

public class Answer {
    private String answer;
    private List<String> sources;

    public Answer() {
    }

    public Answer(String answer, List<String> sources) {
        this.answer = answer;
        this.sources = sources;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        Answer that = (Answer) object;
        return Objects.equals(answer, that.answer) && Objects.equals(sources, that.sources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(answer, sources);
    }

    @Override
    public String toString() {
        return "Answer[answer=" + answer + ", sources=" + sources + "]";
    }
}
