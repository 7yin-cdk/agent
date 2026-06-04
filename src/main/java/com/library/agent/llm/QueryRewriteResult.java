package com.library.agent.llm;

import lombok.Data;

@Data
public class QueryRewriteResult {

    private String originalQuery;

    private String rewrittenQuery;

    private boolean rewritten;

    public static QueryRewriteResult unchanged(String query) {
        QueryRewriteResult result = new QueryRewriteResult();
        result.setOriginalQuery(query);
        result.setRewrittenQuery(query);
        result.setRewritten(false);
        return result;
    }
}
