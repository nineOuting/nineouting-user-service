package com.nineouting.backend.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserServiceApplicationTests {

    @Test
    void 애플리케이션이_뜨고_Flyway_스키마가_엔티티와_맞는다() {
    }
}
