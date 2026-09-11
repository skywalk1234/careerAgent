package group.career_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class CareerBackendApplicationTests {

	@Test
	void applicationCanBeConfigured() {
		SpringApplication application = new SpringApplication(CareerBackendApplication.class);
		application.setLogStartupInfo(false);
	}

}
