package com.fraternity.dues;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class AppTest {

    @Test
    void mainShouldInvokeSpringApplicationRun() {
        String[] args = {"--server.port=9090"};

        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            App.main(args);

            springApplication.verify(() -> SpringApplication.run(App.class, args));
        }
    }
}
