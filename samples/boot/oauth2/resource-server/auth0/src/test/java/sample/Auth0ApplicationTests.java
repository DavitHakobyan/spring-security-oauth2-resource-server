/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sample;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.JwsBuilder;
import org.springframework.test.context.junit4.SpringRunner;

import java.security.PrivateKey;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class Auth0ApplicationTests {

	@Autowired
	TestRestTemplate rest;

	@Autowired
	PrivateKey sign;

	String messageBothAuthority;
	String messageReadAuthority;
	String messageWriteAuthority;

	@Before
	public void setUp() throws Exception {
		this.messageBothAuthority =
				JwsBuilder.withAlgorithm("RS256")
						.claim("iss", "rob")
						.scope("message.read")
						.scope("message.write")
						.sign("id", this.sign)
						.build();

		this.messageReadAuthority =
				JwsBuilder.withAlgorithm("RS256")
						.claim("iss", "rob")
						.scope("message.read")
						.sign("id", this.sign)
						.build();

		this.messageWriteAuthority =
				JwsBuilder.withAlgorithm("RS256")
						.claim("iss", "rob")
						.scope("message.write")
						.sign("id", this.sign)
						.build();
	}

	@Test
	public void requestWhenProperAuthorizationHeaderThenBothRequestsAreAllowed() throws Exception {
		Message toSave = new Message("New");

		ResponseEntity<Message> response = postForMessage("/messages", this.messageBothAuthority, toSave);

		Message saved = response.getBody();
		assertThat(saved.getText()).isEqualTo(toSave.getText());

		response = getForMessage("/messages/{id}", this.messageBothAuthority, saved.getId());
		Message message = response.getBody();

		assertThat(message.getText()).isEqualTo(saved.getText());
	}

	@Test
	public void readWhenProperAuthorizationHeaderThenGetIsAllowed() {
		ResponseEntity<Message> response = getForMessage("/messages/{id}", this.messageReadAuthority, 1L);

		Message message = response.getBody();

		assertThat(message.getText()).isEqualTo("Hello World");
	}

	@Test
	public void writeWhenProperAuthorizationHeaderThenPostIsAllowed() {
		Message toSave = new Message("New");

		ResponseEntity<Message> response = postForMessage("/messages", this.messageWriteAuthority, toSave);

		Message saved = response.getBody();
		assertThat(saved.getText()).isEqualTo(toSave.getText());

		response = getForMessage("/messages/{id}", this.messageReadAuthority, saved.getId());
		Message message = response.getBody();

		assertThat(message.getText()).isEqualTo(saved.getText());
	}

	@Test
	public void readWhenNoAuthorizationHeaderThenRequestIsUnauthorized() {
		ResponseEntity<Message> response = getForMessage("/messages/{id}", null, 1L);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().get("WWW-Authenticate"))
				.isNotNull()
				.contains("Bearer");
	}

	@Test
	public void writeWhenNoAuthorizationHeaderThenRequestIsForbiddenByCsrf() {
		Message toSave = new Message("New");
		ResponseEntity<Message> response = postForMessage("/messages", null, toSave);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders().get("WWW-Authenticate"))
				.isNotNull()
				.contains("Bearer");
	}

	@Test
	public void readWhenAuthorizationHeaderIsMalformedThenRequestIsBadRequest() {
		ResponseEntity<Message> response = getForMessage("/messages/{id}", "a\"malformed\"token", 1L);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getHeaders().get("WWW-Authenticate"))
				.isNotNull()
				.contains("Bearer error=\"invalid_request\"");
	}

	@Test
	public void writeWhenAuthorizationHeaderIsMalformedThenRequestIsBadRequest() {
		Message toSave = new Message("New");
		ResponseEntity<Message> response = postForMessage("/messages", "a\"malformed\"token", toSave);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getHeaders().get("WWW-Authenticate"))
				.isNotNull()
				.contains("Bearer error=\"invalid_request\"");
	}

	@Test
	public void readWhenBadAuthorizationHeaderThenRequestIsForbidden() {
		ResponseEntity<Message> response = getForMessage("/messages/{id}", this.messageWriteAuthority, 1L);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders().get("WWW-Authenticate"))
				.isNotNull()
				.contains("Bearer error=\"insufficient_scope\", " +
						"error_description=\"Resource requires any or all of these scopes [message.read]\", " +
						"error_uri=\"https://tools.ietf.org/html/rfc6750#section-3.1\", " +
						"scope=\"message.read\"");
	}

	@Test
	public void writeWhenBadAuthorizationHeaderThenRequestIsForbidden() {
		Message toSave = new Message("New");
		ResponseEntity<Message> response = postForMessage("/messages", this.messageReadAuthority, toSave);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders().get("WWW-Authenticate"))
				.isNotNull()
				.contains("Bearer error=\"insufficient_scope\", " +
						"error_description=\"Resource requires any or all of these scopes [message.write]\", " +
						"error_uri=\"https://tools.ietf.org/html/rfc6750#section-3.1\", " +
						"scope=\"message.write\"");
	}

	protected ResponseEntity<Message> getForMessage(String uri, String token, Long id) {
		HttpHeaders headers = new HttpHeaders();

		if ( token != null ) {
			headers.add("Authorization", "Bearer " + token);
		}

		HttpEntity<?> entity = new HttpEntity<>(headers);

		return this.rest.exchange(uri, HttpMethod.GET, entity, Message.class, id);
	}

	protected ResponseEntity<Message> postForMessage(String uri, String token, Message body) {
		HttpHeaders headers = new HttpHeaders();

		if ( token != null ) {
			headers.add("Authorization", "Bearer " + token);
		}

		headers.add("Content-Type", "application/json");
		headers.add("Accept", "application/json");

		HttpEntity<?> entity = new HttpEntity<>(body, headers);

		return this.rest.exchange(uri, HttpMethod.POST, entity, Message.class);
	}
}
