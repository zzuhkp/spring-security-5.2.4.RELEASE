/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.client.web;

import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The default implementation of an {@link OAuth2AuthorizedClientManager}.
 *
 * @author Joe Grandja
 * @since 5.2
 * @see OAuth2AuthorizedClientManager
 * @see OAuth2AuthorizedClientProvider
 */
public final class DefaultOAuth2AuthorizedClientManager implements OAuth2AuthorizedClientManager {
	private final ClientRegistrationRepository clientRegistrationRepository;
	private final OAuth2AuthorizedClientRepository authorizedClientRepository;
	private OAuth2AuthorizedClientProvider authorizedClientProvider = context -> null;
	private Function<OAuth2AuthorizeRequest, Map<String, Object>> contextAttributesMapper = new DefaultContextAttributesMapper();

	/**
	 * Constructs a {@code DefaultOAuth2AuthorizedClientManager} using the provided parameters.
	 *
	 * @param clientRegistrationRepository the repository of client registrations
	 * @param authorizedClientRepository the repository of authorized clients
	 */
	public DefaultOAuth2AuthorizedClientManager(ClientRegistrationRepository clientRegistrationRepository,
												OAuth2AuthorizedClientRepository authorizedClientRepository) {
		Assert.notNull(clientRegistrationRepository, "clientRegistrationRepository cannot be null");
		Assert.notNull(authorizedClientRepository, "authorizedClientRepository cannot be null");
		this.clientRegistrationRepository = clientRegistrationRepository;
		this.authorizedClientRepository = authorizedClientRepository;
	}

	@Nullable
	@Override
	public OAuth2AuthorizedClient authorize(OAuth2AuthorizeRequest authorizeRequest) {
		Assert.notNull(authorizeRequest, "authorizeRequest cannot be null");

		String clientRegistrationId = authorizeRequest.getClientRegistrationId();
		OAuth2AuthorizedClient authorizedClient = authorizeRequest.getAuthorizedClient();
		Authentication principal = authorizeRequest.getPrincipal();

		HttpServletRequest servletRequest = getHttpServletRequestOrDefault(authorizeRequest.getAttributes());
		Assert.notNull(servletRequest, "servletRequest cannot be null");
		HttpServletResponse servletResponse = getHttpServletResponseOrDefault(authorizeRequest.getAttributes());
		Assert.notNull(servletResponse, "servletResponse cannot be null");

		OAuth2AuthorizationContext.Builder contextBuilder;
		if (authorizedClient != null) {
			contextBuilder = OAuth2AuthorizationContext.withAuthorizedClient(authorizedClient);
		} else {
			ClientRegistration clientRegistration = this.clientRegistrationRepository.findByRegistrationId(clientRegistrationId);
			Assert.notNull(clientRegistration, "Could not find ClientRegistration with id '" + clientRegistrationId + "'");
			authorizedClient = this.authorizedClientRepository.loadAuthorizedClient(
					clientRegistrationId, principal, servletRequest);
			if (authorizedClient != null) {
				contextBuilder = OAuth2AuthorizationContext.withAuthorizedClient(authorizedClient);
			} else {
				contextBuilder = OAuth2AuthorizationContext.withClientRegistration(clientRegistration);
			}
		}
		OAuth2AuthorizationContext authorizationContext = contextBuilder
				.principal(principal)
				.attributes(attributes -> {
					Map<String, Object> contextAttributes = this.contextAttributesMapper.apply(authorizeRequest);
					if (!CollectionUtils.isEmpty(contextAttributes)) {
						attributes.putAll(contextAttributes);
					}
				})
				.build();

		authorizedClient = this.authorizedClientProvider.authorize(authorizationContext);
		if (authorizedClient != null) {
			this.authorizedClientRepository.saveAuthorizedClient(authorizedClient, principal, servletRequest, servletResponse);
		} else {
			// In the case of re-authorization, the returned `authorizedClient` may be null if re-authorization is not supported.
			// For these cases, return the provided `authorizationContext.authorizedClient`.
			if (authorizationContext.getAuthorizedClient() != null) {
				return authorizationContext.getAuthorizedClient();
			}
		}

		return authorizedClient;
	}

	private static HttpServletRequest getHttpServletRequestOrDefault(Map<String, Object> attributes) {
		HttpServletRequest servletRequest = (HttpServletRequest) attributes.get(HttpServletRequest.class.getName());
		if (servletRequest == null) {
			RequestAttributes context = RequestContextHolder.getRequestAttributes();
			if (context instanceof ServletRequestAttributes) {
				servletRequest = ((ServletRequestAttributes) context).getRequest();
			}
		}
		return servletRequest;
	}

	private static HttpServletResponse getHttpServletResponseOrDefault(Map<String, Object> attributes) {
		HttpServletResponse servletResponse = (HttpServletResponse) attributes.get(HttpServletResponse.class.getName());
		if (servletResponse == null) {
			RequestAttributes context = RequestContextHolder.getRequestAttributes();
			if (context instanceof ServletRequestAttributes) {
				servletResponse =  ((ServletRequestAttributes) context).getResponse();
			}
		}
		return servletResponse;
	}

	/**
	 * Sets the {@link OAuth2AuthorizedClientProvider} used for authorizing (or re-authorizing) an OAuth 2.0 Client.
	 *
	 * @param authorizedClientProvider the {@link OAuth2AuthorizedClientProvider} used for authorizing (or re-authorizing) an OAuth 2.0 Client
	 */
	public void setAuthorizedClientProvider(OAuth2AuthorizedClientProvider authorizedClientProvider) {
		Assert.notNull(authorizedClientProvider, "authorizedClientProvider cannot be null");
		this.authorizedClientProvider = authorizedClientProvider;
	}

	/**
	 * Sets the {@code Function} used for mapping attribute(s) from the {@link OAuth2AuthorizeRequest} to a {@code Map} of attributes
	 * to be associated to the {@link OAuth2AuthorizationContext#getAttributes() authorization context}.
	 *
	 * @param contextAttributesMapper the {@code Function} used for supplying the {@code Map} of attributes
	 *                                   to the {@link OAuth2AuthorizationContext#getAttributes() authorization context}
	 */
	public void setContextAttributesMapper(Function<OAuth2AuthorizeRequest, Map<String, Object>> contextAttributesMapper) {
		Assert.notNull(contextAttributesMapper, "contextAttributesMapper cannot be null");
		this.contextAttributesMapper = contextAttributesMapper;
	}

	/**
	 * The default implementation of the {@link #setContextAttributesMapper(Function) contextAttributesMapper}.
	 */
	public static class DefaultContextAttributesMapper implements Function<OAuth2AuthorizeRequest, Map<String, Object>> {

		@Override
		public Map<String, Object> apply(OAuth2AuthorizeRequest authorizeRequest) {
			Map<String, Object> contextAttributes = Collections.emptyMap();
			HttpServletRequest servletRequest = getHttpServletRequestOrDefault(authorizeRequest.getAttributes());
			String scope = servletRequest.getParameter(OAuth2ParameterNames.SCOPE);
			if (StringUtils.hasText(scope)) {
				contextAttributes = new HashMap<>();
				contextAttributes.put(OAuth2AuthorizationContext.REQUEST_SCOPE_ATTRIBUTE_NAME,
						StringUtils.delimitedListToStringArray(scope, " "));
			}
			return contextAttributes;
		}
	}
}
