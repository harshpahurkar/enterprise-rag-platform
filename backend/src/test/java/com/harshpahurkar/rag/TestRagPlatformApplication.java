package com.harshpahurkar.rag;

import org.springframework.boot.SpringApplication;

public class TestRagPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.from(RagPlatformApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
