package app.stacklab.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Le garde-fou réseau (warmup off, upstream sur port fermé) vit dans
// src/test/resources/application.yaml — il couvre tout @SpringBootTest.
@SpringBootTest
class ProxyApplicationTests {

	@Test
	void contextLoads() {
	}

}
