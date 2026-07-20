package group.careerservice;/* I love coding */




import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.mapper.JobPromotionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jackson.date-format=yyyy-MM-dd'T'HH:mm:ss"
})
@ActiveProfiles("test")
class EdgeDataTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobPromotionMapper jobPromotionMapper;


}
