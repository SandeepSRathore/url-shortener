package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
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

    private ResultActions createLink(String json) throws Exception {
        return mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
