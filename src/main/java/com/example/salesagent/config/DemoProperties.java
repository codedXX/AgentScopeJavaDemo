package com.example.salesagent.config;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 业务参数集中管理；API Key 仅来自环境变量，不写入代码或日志。 */
@ConfigurationProperties("demo")
public class DemoProperties {
    private BailianProperties bailian = new BailianProperties();
    private RagProperties rag = new RagProperties();
    private MilvusProperties milvus = new MilvusProperties();
    private McpProperties mcp = new McpProperties();
    private GithubProperties github = new GithubProperties();

    public DemoProperties() {
    }

    public DemoProperties(BailianProperties bailian, RagProperties rag, MilvusProperties milvus,
                          McpProperties mcp, GithubProperties github) {
        this.bailian = bailian;
        this.rag = rag;
        this.milvus = milvus;
        this.mcp = mcp;
        this.github = github;
    }

    public BailianProperties getBailian() {
        return bailian;
    }

    public void setBailian(BailianProperties bailian) {
        this.bailian = bailian;
    }

    public RagProperties getRag() {
        return rag;
    }

    public void setRag(RagProperties rag) {
        this.rag = rag;
    }

    public MilvusProperties getMilvus() {
        return milvus;
    }

    public void setMilvus(MilvusProperties milvus) {
        this.milvus = milvus;
    }

    public McpProperties getMcp() {
        return mcp;
    }

    public void setMcp(McpProperties mcp) {
        this.mcp = mcp;
    }

    public GithubProperties getGithub() {
        return github;
    }

    public void setGithub(GithubProperties github) {
        this.github = github;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        DemoProperties that = (DemoProperties) object;
        return Objects.equals(bailian, that.bailian)
                && Objects.equals(rag, that.rag)
                && Objects.equals(milvus, that.milvus)
                && Objects.equals(mcp, that.mcp)
                && Objects.equals(github, that.github);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bailian, rag, milvus, mcp, github);
    }

    @Override
    public String toString() {
        return "DemoProperties[bailian=" + bailian + ", rag=" + rag + ", milvus=" + milvus
                + ", mcp=" + mcp + ", github=" + github + "]";
    }
}
