package com.harshpahurkar.rag.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.harshpahurkar.rag.AppProperties;
import com.harshpahurkar.rag.TestcontainersConfiguration;
import com.harshpahurkar.rag.security.Roles;

/** The login page's demo hint: served only under the demo profile, to anyone, and never with a password. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "demo" })
@Import(TestcontainersConfiguration.class)
class DemoAccountsIT {

	/** This class needs the demo profile's beans, not its users and documents, so seeding is skipped. */
	@MockitoBean
	DemoDataLoader loader;

	@Autowired
	MockMvc mvc;

	@Autowired
	AppProperties props;

	@Test
	void listsDemoUsernamesAndRolesWithoutAPassword() throws Exception {
		String body = mvc.perform(get("/api/auth/demo-accounts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].username").value(
					contains("admin", "hr.manager", "finance.analyst", "legal.counsel", "engineer")))
			.andExpect(jsonPath("$[0].roles").value(contains(Roles.ALL.toArray())))
			.andExpect(jsonPath("$[4].roles").value(contains("ENGINEERING", "EMPLOYEE")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContainIgnoringCase("password").doesNotContain(props.demo().password());
	}

}
