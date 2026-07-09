package dev.outpost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OutpostApplication {

	public static void main(String[] args) {
		SpringApplication.run(OutpostApplication.class, args);
	}

}
