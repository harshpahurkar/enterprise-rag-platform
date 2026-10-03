package com.harshpahurkar.rag.demo;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.harshpahurkar.rag.demo.DemoDataLoader.DemoUser;

/**
 * Demo profile only: the seeded accounts, for the login page's hint. Usernames and roles, never a password; that
 * stays in DEMO_PASSWORD. Without the demo profile this endpoint doesn't exist, so the hint doesn't either.
 */
@RestController
@Profile("demo")
class DemoAccountsController {

	@GetMapping("/api/auth/demo-accounts")
	List<DemoUser> demoAccounts() {
		return DemoDataLoader.USERS;
	}

}
