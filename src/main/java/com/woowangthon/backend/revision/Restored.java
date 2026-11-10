package com.woowangthon.backend.revision;

/** 되돌리기로 바뀐 대상(응답에 싣는 값)과, 그때 더한 RESTORE 기록. */
public record Restored<T>(T value, Revision revision) {
}
