package com.woowangthon.backend.mcp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * MCP 요청 본문 1MB 한도 — 토큰을 보기 전에 읽는 본문이라 크기부터 막는다. 가장 큰 정상 요청(add_source 50000자)도 수백 KB 다.
 * 넘으면 읽지 않고 BodyTooLarge — McpController 가 JSON-RPC 오류 본문으로 돌려준다.
 */
@ControllerAdvice(assignableTypes = McpController.class)
public class McpBodyLimitAdvice extends RequestBodyAdviceAdapter {
    static final int MAX_BYTES = 1024 * 1024;

    static class BodyTooLarge extends RuntimeException {
        BodyTooLarge() {
            super(null, null, false, false);
        }
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.getContainingClass() == McpController.class && parameter.getParameterType() == String.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        if (input.getHeaders().getContentLength() > MAX_BYTES) {
            throw new BodyTooLarge();
        }
        // Content-Length 가 없는 요청도 같은 한도
        byte[] body = input.getBody().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            throw new BodyTooLarge();
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() { return new ByteArrayInputStream(body); }
            @Override
            public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }
}
