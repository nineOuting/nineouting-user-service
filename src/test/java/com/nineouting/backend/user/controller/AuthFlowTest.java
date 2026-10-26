package com.nineouting.backend.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nineouting.backend.common.security.jwt.AuthenticatedUser;
import com.nineouting.backend.common.security.jwt.JwksKeyLocator;
import com.nineouting.backend.common.security.jwt.JwtTokenVerifier;
import com.nineouting.backend.user.TestcontainersConfiguration;
import com.nineouting.backend.user.TestcontainersConfiguration.CapturingMailNotifier;
import com.nineouting.backend.user.security.AccessTokenIssuer;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthFlowTest {

    private static final String PASSWORD = "password123";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CapturingMailNotifier mail;

    @Test
    void 인증_가입_로그인_재발급_로그아웃() throws Exception {
        String email = newEmail();
        Long userId = signup(email);

        MvcResult login = postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andReturn();
        String accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken");
        Cookie refresh = login.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/auth");

        // 발급한 토큰은 다른 서비스와 같은 방식(공개키 JWKS)으로 검증된다
        String jwks = mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JwtTokenVerifier verifier = new JwtTokenVerifier(new JwksKeyLocator(() -> jwks), AccessTokenIssuer.ISSUER);
        assertThat(verifier.verify(accessToken)).isEqualTo(new AuthenticatedUser(userId));

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        Cookie rotated = refreshed.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(rotated.getValue()).isNotEqualTo(refresh.getValue());

        // 한 번 쓴 refresh 토큰은 다시 쓸 수 없다
        mockMvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        MvcResult logout = mockMvc.perform(post("/api/auth/logout").cookie(rotated))
                .andExpect(status().isOk()).andReturn();
        assertThat(logout.getResponse().getCookie(AuthController.REFRESH_COOKIE).getMaxAge()).isZero();
        mockMvc.perform(post("/api/auth/refresh").cookie(rotated))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 이미_가입된_이메일은_인증_메일을_보내지_않는다() throws Exception {
        String email = newEmail();
        signup(email);

        postJson("/api/auth/email-verifications", "{\"email\":\"%s\"}".formatted(email))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void 이메일_인증_없이는_가입할_수_없다() throws Exception {
        postJson("/api/auth/signup", signupBody(newEmail(), PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void 인증_코드를_5번_틀리면_30분_동안_제한된다() throws Exception {
        String email = newEmail();
        postJson("/api/auth/email-verifications", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isOk());
        String wrong = "000000".equals(mail.codeFor(email)) ? "111111" : "000000";

        for (int i = 1; i < 5; i++) {
            // MSG-005 틀린 횟수를 문구 끝에 붙인다
            confirm(email, wrong)
                    .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_MISMATCH"))
                    .andExpect(jsonPath("$.message").value("인증 번호가 올바르지 않습니다. 다시 확인해 주세요. (%d/5)".formatted(i)));
        }
        confirm(email, wrong)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VERIFICATION_LOCKED"));
        confirm(email, mail.codeFor(email)).andExpect(jsonPath("$.code").value("VERIFICATION_LOCKED"));
        postJson("/api/auth/email-verifications", "{\"email\":\"%s\"}".formatted(email))
                .andExpect(jsonPath("$.code").value("VERIFICATION_LOCKED"));
    }

    @Test
    void 비밀번호_규칙에_맞지_않으면_틀린_칸을_알려_준다() throws Exception {
        for (String password : new String[] {"short1", "onlyletters", "12345678", "password1!"}) {
            postJson("/api/auth/signup", signupBody(newEmail(), password))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.data[0].field").value("password"));
        }
    }

    @Test
    void 로그인에_실패하면_어느_쪽이_틀렸는지_알려_주지_않는다() throws Exception {
        String email = newEmail();
        signup(email);

        String wrongPassword = postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"wrong1234\"}".formatted(email))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownEmail = postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(newEmail(), PASSWORD))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownEmail).contains("LOGIN_FAILED");
    }

    @Test
    void 로그인에_5번_연속_실패하면_제한되고_비밀번호를_재설정하면_풀린다() throws Exception {
        String email = newEmail();
        signup(email);
        String wrong = "{\"email\":\"%s\",\"password\":\"wrong1234\"}".formatted(email);
        String right = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD);

        for (int i = 1; i < 5; i++) {
            postJson("/api/auth/login", wrong).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        }
        postJson("/api/auth/login", wrong)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
        postJson("/api/auth/login", right).andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));

        postJson("/api/auth/password-reset", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isOk());
        String token = verifyResetCode(email, mail.resetCodeFor(email));
        postJson("/api/auth/password-reset/confirm", "{\"token\":\"%s\",\"newPassword\":\"newPassword9\"}".formatted(token))
                .andExpect(status().isOk());
        postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"newPassword9\"}".formatted(email))
                .andExpect(status().isOk());
    }

    @Test
    void 로그인에_성공하면_실패_횟수가_처음부터_다시_세어진다() throws Exception {
        String email = newEmail();
        signup(email);
        String wrong = "{\"email\":\"%s\",\"password\":\"wrong1234\"}".formatted(email);

        for (int i = 1; i < 5; i++) {
            postJson("/api/auth/login", wrong);
        }
        postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))
                .andExpect(status().isOk());
        postJson("/api/auth/login", wrong).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    void 이전_비밀번호로는_재설정할_수_없고_토큰은_그대로_쓸_수_있다() throws Exception {
        String email = newEmail();
        signup(email);
        postJson("/api/auth/password-reset", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isOk());
        String token = verifyResetCode(email, mail.resetCodeFor(email));

        postJson("/api/auth/password-reset/confirm", "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_AS_OLD_PASSWORD"));
        postJson("/api/auth/password-reset/confirm", "{\"token\":\"%s\",\"newPassword\":\"newPassword9\"}".formatted(token))
                .andExpect(status().isOk());
    }

    @Test
    void 비밀번호를_재설정하면_새_비밀번호로_로그인되고_기존_로그인은_끊긴다() throws Exception {
        String email = newEmail();
        signup(email);
        Cookie oldRefresh = postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))
                .andReturn().getResponse().getCookie(AuthController.REFRESH_COOKIE);

        postJson("/api/auth/password-reset", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isOk());
        String token = verifyResetCode(email, mail.resetCodeFor(email));
        postJson("/api/auth/password-reset/confirm", "{\"token\":\"%s\",\"newPassword\":\"newPassword9\"}".formatted(token))
                .andExpect(status().isOk());

        postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"newPassword9\"}".formatted(email))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").cookie(oldRefresh)).andExpect(status().isUnauthorized());
        // 재설정 토큰은 한 번만 쓸 수 있다
        postJson("/api/auth/password-reset/confirm", "{\"token\":\"%s\",\"newPassword\":\"another123\"}".formatted(token))
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
    }

    @Test
    void 가입하지_않은_이메일로_재설정을_요청해도_같은_응답이다() throws Exception {
        String email = newEmail();

        postJson("/api/auth/password-reset", "{\"email\":\"%s\"}".formatted(email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(mail.resetCodeFor(email)).isNull();
        postJson("/api/auth/password-reset/verify", "{\"email\":\"%s\",\"code\":\"123456\"}".formatted(email))
                .andExpect(jsonPath("$.code").value("VERIFICATION_EXPIRED"));
    }

    @Test
    void 비밀번호_찾기_인증_번호는_한_번만_쓸_수_있고_5번_틀리면_30분_동안_제한된다() throws Exception {
        String email = newEmail();
        signup(email);
        postJson("/api/auth/password-reset", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isOk());
        String code = mail.resetCodeFor(email);
        verifyResetCode(email, code);
        postJson("/api/auth/password-reset/verify", "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code))
                .andExpect(jsonPath("$.code").value("VERIFICATION_EXPIRED"));

        postJson("/api/auth/password-reset", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isOk());
        String wrong = "000000".equals(mail.resetCodeFor(email)) ? "111111" : "000000";
        for (int i = 1; i < 5; i++) {
            postJson("/api/auth/password-reset/verify", "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, wrong))
                    .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_MISMATCH"));
        }
        postJson("/api/auth/password-reset/verify", "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, wrong))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VERIFICATION_LOCKED"));
        postJson("/api/auth/password-reset/verify",
                "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, mail.resetCodeFor(email)))
                .andExpect(jsonPath("$.code").value("VERIFICATION_LOCKED"));
    }

    @Test
    void 로그인이_필요한_주소는_토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 이메일은_대소문자와_앞뒤_공백을_구분하지_않는다() throws Exception {
        String email = newEmail();
        signup(email);

        postJson("/api/auth/login", "{\"email\":\"  %s \",\"password\":\"%s\"}".formatted(email.toUpperCase(), PASSWORD))
                .andExpect(status().isOk());
    }

    private Long signup(String email) throws Exception {
        postJson("/api/auth/email-verifications", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isOk());
        confirm(email, mail.codeFor(email)).andExpect(status().isOk());
        MvcResult result = postJson("/api/auth/signup", signupBody(email, PASSWORD))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.userId")).longValue();
    }

    private String verifyResetCode(String email, String code) throws Exception {
        MvcResult result = postJson("/api/auth/password-reset/verify",
                "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.resetToken");
    }

    private ResultActions confirm(String email, String code) throws Exception {
        return postJson("/api/auth/email-verifications/confirm", "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code));
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String signupBody(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"테스터\"}".formatted(email, password);
    }

    private static String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
