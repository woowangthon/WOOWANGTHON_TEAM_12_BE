package com.woowangthon.backend.share;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 사용자의 공개 프로필 설정 하나(S-65 · S-66). token 이 없으면 링크가 꺼져 있다. 줄은 처음 고칠 때 생긴다. */
@Entity
@Table(name = "public_profile")
public class PublicProfile {

    @Id
    private Long userId;

    private String title;

    private String token;

    protected PublicProfile() {
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getToken() {
        return token;
    }

    void rename(String newTitle) {
        title = newTitle;
    }

    void replaceToken(String newToken) {
        token = newToken;
    }

    void disableLink() {
        token = null;
    }
}
