package com.harshpahurkar.rag;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final DockerImageName PGVECTOR = DockerImageName.parse("pgvector/pgvector:0.8.1-pg18")
		.asCompatibleSubstituteFor("postgres");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(PGVECTOR);
	}

}
