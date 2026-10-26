package com.tasksphere;

import com.tasksphere.controller.HomeController;
import com.tasksphere.dto.AuthTokenResponse;
import com.tasksphere.service.AuthClientService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class HomeControllerTest {

    @Test
    void landingPageShouldLoad() throws Exception {
        AuthClientService authClientService = mock(AuthClientService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HomeController(authClientService)).build();

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("index"));
    }

    @Test
    void loginShouldRedirectToDashboard() throws Exception {
        AuthClientService authClientService = mock(AuthClientService.class);
        AuthTokenResponse token = new AuthTokenResponse();
        token.setToken("demo-token");
        token.setTokenType("Bearer");
        token.setUsername("26MCA0012");
        token.setDisplayName("Aarav Sharma");
        token.setRoles(Set.of("STUDENT"));

        when(authClientService.login("26MCA0012", "secret")).thenReturn(token);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HomeController(authClientService)).build();

        mockMvc.perform(post("/login")
                .param("username", "26MCA0012")
                .param("password", "secret"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/dashboard"));
    }
}
