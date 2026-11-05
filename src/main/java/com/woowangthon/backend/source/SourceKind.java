package com.woowangthon.backend.source;

public enum SourceKind {
    TEXT,
    FILE,
    /** 외부 AI 가 보낸 글(S-52) — BE 만 붙인다. POST /api/sources 는 받지 않는다. */
    MCP
}
