package com.woowangthon.backend.revision;

/** 누가 고쳤나(계약 README 「값 목록」 revisionBy) — 웹이면 USER, 외부 AI(MCP 쓰기 도구)면 EXTERNAL_AI. */
public enum RevisionBy {
    USER, EXTERNAL_AI
}
