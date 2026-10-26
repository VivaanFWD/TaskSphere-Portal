package com.tasksphere;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ActiveProfiles("test")
class WebSecurityIntegrationTest {
    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    void bootstrapWebjarIsAvailableWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/webjars/bootstrap/5.3.3/css/bootstrap.min.css"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(".btn")));
    }

    @Test
    void logoutInvalidatesSessionAndRedirectsToHome() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("currentUser", "signed-in-user");
        session.setAttribute("token", "Bearer example-token");

        mockMvc.perform(post("/logout").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));

        mockMvc.perform(get("/dashboard"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
    }

    @Test
    void serverErrorDispatchUsesStyledTemplate() throws Exception {
        mockMvc.perform(get("/error")
                .accept(MediaType.TEXT_HTML)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(containsString("Something went wrong")))
            .andExpect(content().string(containsString("/webjars/bootstrap/5.3.3/css/bootstrap.min.css")));
    }
}
