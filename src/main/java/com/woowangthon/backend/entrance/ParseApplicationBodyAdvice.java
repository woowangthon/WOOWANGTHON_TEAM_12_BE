package com.woowangthon.backend.entrance;

import com.woowangthon.backend.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** JSON 캡처 요청에만 25MB 한도. Tomcat의 폼·multipart 설정과 다른 엔드포인트는 건드리지 않는다. */
@ControllerAdvice(assignableTypes = EntranceController.class)
public class ParseApplicationBodyAdvice extends RequestBodyAdviceAdapter {
    static final int MAX_BYTES = 25 * 1024 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.getParameterType() == EntranceService.ParseRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        if (input.getHeaders().getContentLength() > MAX_BYTES) {
            throw tooLarge();
        }
        // Content-Length가 없는 요청도 같은 한도. 디스크·로그에는 쓰지 않는다.
        byte[] body = input.getBody().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            throw tooLarge();
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() { return new ByteArrayInputStream(body); }
            @Override
            public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "INVALID_IMAGE", "캡처 요청은 25MB 이하여야 합니다");
    }
}
