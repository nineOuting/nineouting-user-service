package com.nineouting.backend.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nineouting.backend.user.TestcontainersConfiguration;
import com.nineouting.backend.user.TestcontainersConfiguration.CapturingMailNotifier;
import com.nineouting.backend.user.service.ProfileImageStorage;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProfileFlowTest {

    private static final String PASSWORD = "password123";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CapturingMailNotifier mail;

    @Autowired
    ProfileImageStorage profileImageStorage;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 내_정보를_조회하고_이름과_소속을_바꾼다() throws Exception {
        LoggedIn me = signupAndLogin();

        perform(me, get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(me.email))
                .andExpect(jsonPath("$.data.name").value("테스터"))
                .andExpect(jsonPath("$.data.affiliation").doesNotExist())
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist());

        perform(me, patchJson("/api/users/me", "{\"name\":\"새이름\",\"affiliation\":\"백엔드팀\"}"))
                .andExpect(jsonPath("$.data.name").value("새이름"))
                .andExpect(jsonPath("$.data.affiliation").value("백엔드팀"));

        // 보내지 않은 항목은 그대로, 소속을 빈 값으로 보내면 지운다
        perform(me, patchJson("/api/users/me", "{\"affiliation\":\"\"}"))
                .andExpect(jsonPath("$.data.name").value("새이름"))
                .andExpect(jsonPath("$.data.affiliation").doesNotExist());

        perform(me, patchJson("/api/users/me", "{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data[0].field").value("name"));
    }

    @Test
    void 비밀번호를_바꾸면_새_비밀번호로_로그인되고_기존_로그인은_끊긴다() throws Exception {
        LoggedIn me = signupAndLogin();

        perform(me, putJson("/api/users/me/password", "{\"currentPassword\":\"wrong1234\",\"newPassword\":\"changed123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));

        perform(me, putJson("/api/users/me/password", "{\"currentPassword\":\"%s\",\"newPassword\":\"changed123\"}".formatted(PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh").cookie(me.refresh)).andExpect(status().isUnauthorized());
        login(me.email, "changed123").andExpect(status().isOk());
    }

    @Test
    void 프로필_사진을_올리고_바꾸고_지운다() throws Exception {
        LoggedIn me = signupAndLogin();

        String firstKey = upload(me, "image/png", new byte[] {1, 2, 3});
        String url = JsonPath.read(perform(me, putJson("/api/users/me/profile-image", "{\"imageKey\":\"%s\"}".formatted(firstKey)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.profileImageUrl");
        assertThat(httpClient.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray()).body())
                .containsExactly(1, 2, 3);

        // 새 사진으로 바꾸면 이전 사진은 지운다
        String secondKey = upload(me, "image/jpeg", new byte[] {4, 5});
        perform(me, putJson("/api/users/me/profile-image", "{\"imageKey\":\"%s\"}".formatted(secondKey))).andExpect(status().isOk());
        assertThat(profileImageStorage.size(firstKey)).isEmpty();

        perform(me, delete("/api/users/me/profile-image")).andExpect(status().isOk());
        assertThat(profileImageStorage.size(secondKey)).isEmpty();
        perform(me, get("/api/users/me")).andExpect(jsonPath("$.data.profileImageUrl").doesNotExist());
    }

    @Test
    void 프로필_사진_등록_조건을_검사한다() throws Exception {
        LoggedIn me = signupAndLogin();
        LoggedIn other = signupAndLogin();

        perform(me, postJson("/api/users/me/profile-image/upload-url", "{\"contentType\":\"image/gif\"}"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));

        String notUploaded = JsonPath.read(perform(me, postJson("/api/users/me/profile-image/upload-url", "{\"contentType\":\"image/png\"}"))
                .andReturn().getResponse().getContentAsString(), "$.data.imageKey");
        perform(me, putJson("/api/users/me/profile-image", "{\"imageKey\":\"%s\"}".formatted(notUploaded)))
                .andExpect(jsonPath("$.code").value("PROFILE_IMAGE_NOT_UPLOADED"));

        String othersKey = upload(other, "image/png", new byte[] {9});
        perform(me, putJson("/api/users/me/profile-image", "{\"imageKey\":\"%s\"}".formatted(othersKey)))
                .andExpect(status().isForbidden());

        String tooLarge = upload(me, "image/png", new byte[5 * 1024 * 1024 + 1]);
        perform(me, putJson("/api/users/me/profile-image", "{\"imageKey\":\"%s\"}".formatted(tooLarge)))
                .andExpect(jsonPath("$.code").value("PROFILE_IMAGE_TOO_LARGE"));
        assertThat(profileImageStorage.size(tooLarge)).isEmpty();
    }

    @Test
    void 다른_서비스는_여러_사용자의_이름을_한_번에_조회한다() throws Exception {
        LoggedIn a = signupAndLogin();
        LoggedIn b = signupAndLogin();

        mockMvc.perform(get("/internal/users").param("ids", a.userId + "," + b.userId + ",999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("테스터"));

        String tooMany = IntStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(","));
        mockMvc.perform(get("/internal/users").param("ids", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private String upload(LoggedIn user, String contentType, byte[] content) throws Exception {
        String body = perform(user, postJson("/api/users/me/profile-image/upload-url", "{\"contentType\":\"%s\"}".formatted(contentType)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String uploadUrl = JsonPath.read(body, "$.data.uploadUrl");
        HttpResponse<Void> response = httpClient.send(HttpRequest.newBuilder(URI.create(uploadUrl))
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(body, "$.data.imageKey");
    }

    private LoggedIn signupAndLogin() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/email-verifications").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email))).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/email-verifications/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, mail.codeFor(email)))).andExpect(status().isOk());
        String signup = mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"테스터\"}".formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        MvcResult login = login(email, PASSWORD).andExpect(status().isOk()).andReturn();
        return new LoggedIn(((Number) JsonPath.read(signup, "$.data.userId")).longValue(), email,
                JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken"),
                login.getResponse().getCookie(AuthController.REFRESH_COOKIE));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions perform(LoggedIn user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken));
    }

    private static MockHttpServletRequestBuilder patchJson(String url, String body) {
        return patch(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder putJson(String url, String body) {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder postJson(String url, String body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private record LoggedIn(Long userId, String email, String accessToken, Cookie refresh) {
    }
}
