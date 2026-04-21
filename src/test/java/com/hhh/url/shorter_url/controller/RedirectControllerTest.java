package com.hhh.url.shorter_url.controller;

import com.hhh.url.shorter_url.exception.GlobalExceptionHandler;
import com.hhh.url.shorter_url.exception.ResourceNotFoundException;
import com.hhh.url.shorter_url.exception.UrlExpiredException;
import com.hhh.url.shorter_url.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RedirectController.class)
@Import(GlobalExceptionHandler.class)
class RedirectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlService urlService;

    @Test
    void redirect_success_returns302WithLocationHeader() throws Exception {
        when(urlService.redirect("abc123")).thenReturn("https://example.com/long-path");

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/long-path"));
    }

    @Test
    void redirect_unknownCode_returns404() throws Exception {
        when(urlService.redirect("notexist"))
                .thenThrow(new ResourceNotFoundException("Url not found with id: notexist"));

        mockMvc.perform(get("/notexist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void redirect_expiredCode_returns410() throws Exception {
        when(urlService.redirect("expired"))
                .thenThrow(new UrlExpiredException("URL has expired"));

        mockMvc.perform(get("/expired"))
                .andExpect(status().isGone());
    }
}
