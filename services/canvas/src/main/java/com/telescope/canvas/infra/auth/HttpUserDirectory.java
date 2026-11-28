package com.telescope.canvas.infra.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.telescope.canvas.canvas.dto.UserRef;
import com.telescope.canvas.canvas.service.UserDirectory;
import com.telescope.common.auth.InternalApiKeyFilter;
import com.telescope.common.web.RequestIdInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
public class HttpUserDirectory implements UserDirectory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient client;

    @Autowired
    public HttpUserDirectory(@Value("${app.auth-service.base-url}") String baseUrl,
                             @Value("${app.internal-api-key:}") String apiKey) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, apiKey)
                .requestInterceptor(new RequestIdInterceptor())
                .requestFactory(requestFactory)
                .build();
    }

    HttpUserDirectory(RestClient client) {
        this.client = client;
    }

    @Override
    public Map<Long, UserRef> find(Collection<Long> ids) {
        Set<Long> distinct = new LinkedHashSet<>(ids);
        distinct.removeIf(Objects::isNull);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return toMap(fetch(distinct));
            } catch (RuntimeException e) {
                log.warn("auth 사용자 조회 실패({}회째): {}", attempt, e.toString());
            }
        }
        return Map.of();
    }

    private List<Person> fetch(Set<Long> ids) {
        Envelope body = client.get()
                .uri(uri -> uri.path("/internal/v1/users/summaries").queryParam("ids", ids.toArray()).build())
                .retrieve()
                .body(Envelope.class);
        return body == null || body.data() == null ? List.of() : body.data();
    }

    private static Map<Long, UserRef> toMap(List<Person> people) {
        Map<Long, UserRef> found = new HashMap<>();
        for (Person p : people) {
            found.put(p.id(), new UserRef(p.id(), p.name(), p.dept()));
        }
        return found;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(List<Person> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Person(Long id, String name, String dept) {
    }
}
