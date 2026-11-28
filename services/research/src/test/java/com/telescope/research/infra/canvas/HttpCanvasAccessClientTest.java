package com.telescope.research.infra.canvas;

import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpCanvasAccessClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://canvas");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpCanvasAccessClient client = new HttpCanvasAccessClient(builder.build());

    private void respond(String role) {
        String body = role == null
                ? "{\"data\":{\"active\":true,\"role\":null},\"error\":null}"
                : "{\"data\":{\"active\":true,\"role\":\"" + role + "\"},\"error\":null}";
        server.expect(requestTo("http://canvas/internal/v1/canvases/7/access?userId=3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void 볼_권한이_있으면_통과한다() {
        respond("view");
        client.requireView(7L, 3L);
        server.verify();
    }

    @Test
    void 권한이_없으면_볼_수_없다_K001() {
        respond(null);
        assertThatThrownBy(() -> client.requireView(7L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CANVAS_ACCESS_DENIED);
        server.verify();
    }

    @Test
    void 편집은_소유자_편집만_된다() {
        respond("owner");
        client.requireEdit(7L, 3L);
        server.verify();
    }

    @Test
    void 보기_권한으로는_편집할_수_없다_K002() {
        respond("view");
        assertThatThrownBy(() -> client.requireEdit(7L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CANVAS_EDIT_DENIED);
        server.verify();
    }

    @Test
    void 캔버스가_없으면_C002를_그대로_전한다() {
        server.expect(requestTo("http://canvas/internal/v1/canvases/7/access?userId=3"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body("{\"data\":null,\"error\":{\"code\":\"C002\",\"message\":\"없음\"}}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.requireView(7L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        server.verify();
    }

    @Test
    void 권한을_확인하지_못하면_막는다() {
        server.expect(requestTo("http://canvas/internal/v1/canvases/7/access?userId=3"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client.requireView(7L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
        server.verify();
    }
}
