package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class LinkApiTest {

    private static final String URL = "https://example.com/some/long/path";

    @Autowired
    private MockMvc mvc;

    // ---- POST /api/links ----

    @Test
    @DisplayName("R1: POST with valid URL returns 201 with a generated code")
    void createWithGeneratedCode() throws Exception {
        String body = createLink("{\"url\":\"" + URL + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn().getResponse().getContentAsString();

        String code = JsonPath.read(body, "$.code");
        assertThat(JsonPath.<String>read(body, "$.shortUrl")).isEqualTo("http://localhost/" + code);
    }

    @Test
    @DisplayName("R1: POST sets Location header to the stats URL")
    void createSetsLocationHeader() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"loc-test\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/links/loc-test"));
    }

    @Test
    @DisplayName("R1: explicit null alias behaves like a missing alias")
    void explicitNullAliasGeneratesCode() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":null}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("[0-9A-Za-z]{7}")));
    }

    @Test
    @DisplayName("R2: POST with invalid URL returns 400 ProblemDetail")
    void createWithInvalidUrl() throws Exception {
        createLink("{\"url\":\"ftp://example.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid link"))
                .andExpect(jsonPath("$.detail").value("url must use http or https"));
    }

    @Test
    @DisplayName("R2: POST without url returns 400")
    void createWithMissingUrl() throws Exception {
        createLink("{\"alias\":\"no-url-here\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid link"));
    }

    @Test
    @DisplayName("R2: malformed JSON body returns 400 ProblemDetail, not 500")
    void createWithMalformedJson() throws Exception {
        createLink("{not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("R2: missing request body returns 400 ProblemDetail")
    void createWithNoBody() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("R3: POST with valid alias returns 201 and code equals alias")
    void createWithAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"my-alias\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("my-alias"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost/my-alias"));
    }

    @Test
    @DisplayName("R4: POST with invalid alias returns 400")
    void createWithInvalidAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"ab\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid link"));
    }

    @Test
    @DisplayName("R4: POST with empty-string alias returns 400")
    void createWithEmptyAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R4: POST with reserved alias returns 400")
    void createWithReservedAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"Error\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R5: POST with taken alias returns 409 ProblemDetail")
    void createWithTakenAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"dup-alias\"}")
                .andExpect(status().isCreated());

        createLink("{\"url\":\"https://other.example.com\",\"alias\":\"dup-alias\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Alias already taken"));
    }

    // ---- GET /{code} ----

    @Test
    @DisplayName("R6: GET /{code} redirects 302 to the original URL and counts a click")
    void redirectCountsClick() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"go-there\"}").andExpect(status().isCreated());

        mvc.perform(get("/go-there"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", URL));

        mvc.perform(get("/api/links/go-there"))
                .andExpect(jsonPath("$.clicks").value(1));
    }

    @Test
    @DisplayName("R6: HEAD /{code} returns the redirect but does not count a click")
    void headDoesNotCountClick() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"head-check\"}").andExpect(status().isCreated());

        mvc.perform(head("/head-check"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", URL));

        mvc.perform(get("/api/links/head-check"))
                .andExpect(jsonPath("$.clicks").value(0));
    }

    @Test
    @DisplayName("R7: HEAD /{code} with unknown code returns 404")
    void headUnknownCode() throws Exception {
        mvc.perform(head("/does-not-exist-head"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("R7: GET /{code} with unknown code returns 404 ProblemDetail")
    void redirectUnknownCode() throws Exception {
        mvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Link not found"));
    }

    @Test
    @DisplayName("R7: GET /api (no code) is an ordinary 404, not a server error")
    void bareApiPathIsNotFound() throws Exception {
        mvc.perform(get("/api"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- GET /api/links/{code} ----

    @Test
    @DisplayName("R7: GET stats with unknown code returns 404 ProblemDetail")
    void statsUnknownCode() throws Exception {
        mvc.perform(get("/api/links/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Link not found"));
    }

    @Test
    @DisplayName("R8: GET stats returns code, url, clicks and ISO-8601 createdAt")
    void statsReturnsAllFields() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"stats-me\"}").andExpect(status().isCreated());

        mvc.perform(get("/api/links/stats-me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("stats-me"))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.clicks").value(0))
                .andExpect(jsonPath("$.createdAt").value(
                        matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")));
    }

    @Test
    @DisplayName("R8: reading stats does not add a click")
    void statsDoesNotCountAsClick() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"just-look\"}").andExpect(status().isCreated());

        mvc.perform(get("/api/links/just-look"));
        mvc.perform(get("/api/links/just-look"));

        mvc.perform(get("/api/links/just-look"))
                .andExpect(jsonPath("$.clicks").value(0));
    }

    private ResultActions createLink(String json) throws Exception {
        return mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
