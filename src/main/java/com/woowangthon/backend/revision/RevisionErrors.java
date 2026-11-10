package com.woowangthon.backend.revision;

import com.woowangthon.backend.common.ApiException;
import org.springframework.http.HttpStatus;

/** 되돌리기의 409 두 가지(계약 「POST /api/revisions/{revisionId}/restore」). */
public final class RevisionErrors {

    private RevisionErrors() {
    }

    /** 그 경험 · 활동 · 문항이 지워졌다 — 복구는 범위 밖(S-80). */
    public static ApiException targetDeleted() {
        return new ApiException(HttpStatus.CONFLICT, "REVISION_TARGET_DELETED", "되돌릴 대상이 지워졌습니다");
    }

    /** 지금 값이 이미 그 수정 전 값과 같다 — 두 번 눌러도 한 번만 일어난다. */
    public static ApiException nothingToRestore() {
        return new ApiException(HttpStatus.CONFLICT, "NOTHING_TO_RESTORE", "이미 그 수정 전 값입니다");
    }
}
