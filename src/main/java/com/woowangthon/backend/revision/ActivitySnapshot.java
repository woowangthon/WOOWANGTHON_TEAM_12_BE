package com.woowangthon.backend.revision;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.Period;

/** 활동 스냅샷(계약 「스냅샷」) — 이름 · 기간. 분류 · 계기 · 이어짐은 남기지 않는다. */
public record ActivitySnapshot(String name, Period period) {

    public static ActivitySnapshot of(Activity a) {
        return new ActivitySnapshot(a.getName(), a.getPeriod());
    }
}
