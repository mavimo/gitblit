/*
 * Copyright 2011 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit;

import java.io.IOException;
import java.text.MessageFormat;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.gitblit.Constants.RpcRequest;
import com.gitblit.models.UserModel;

/**
 * The RpcFilter is a servlet filter that secures the RpcServlet.
 * 
 * The filter extracts the rpc request type from the url and determines if the
 * requested action requires a Basic authentication prompt. If authentication is
 * required and no credentials are stored in the "Authorization" header, then a
 * basic authentication challenge is issued.
 * 
 * http://en.wikipedia.org/wiki/Basic_access_authentication
 * 
 * @author James Moger
 * 
 */
public class RpcFilter extends AuthenticationFilter {

	/**
	 * doFilter does the actual work of preprocessing the request to ensure that
	 * the user may proceed.
	 * 
	 * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest,
	 *      javax.servlet.ServletResponse, javax.servlet.FilterChain)
	 */
	@Override
	public void doFilter(final ServletRequest request, final ServletResponse response,
			final FilterChain chain) throws IOException, ServletException {

		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;

		String fullUrl = getFullUrl(httpRequest);
		RpcRequest requestType = RpcRequest.fromName(httpRequest.getParameter("req"));
		if (requestType == null) {
			httpResponse.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
			return;
		}

		boolean adminRequest = requestType.exceeds(RpcRequest.LIST_BRANCHES);

		// conditionally reject all rpc requests
		if (!GitBlit.getBoolean(Keys.web.enableRpcServlet, true)) {
			logger.warn(Keys.web.enableRpcServlet + " must be set TRUE for rpc requests.");
			httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		boolean authenticateView = GitBlit.getBoolean(Keys.web.authenticateViewPages, false);
		boolean authenticateAdmin = GitBlit.getBoolean(Keys.web.authenticateAdminPages, true);

		// Wrap the HttpServletRequest with the RpcServletRequest which
		// overrides the servlet container user principal methods.
		AuthenticatedRequest authenticatedRequest = new AuthenticatedRequest(httpRequest);
		UserModel user = getUser(httpRequest);
		if (user != null) {
			authenticatedRequest.setUser(user);
		}

		// conditionally reject rpc management/administration requests
		if (adminRequest && !GitBlit.getBoolean(Keys.web.enableRpcManagement, false)) {
			logger.warn(Keys.web.enableRpcManagement
					+ " must be set TRUE for management/administrative rpc requests.");
			httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		// BASIC authentication challenge and response processing
		if ((adminRequest && authenticateAdmin) || (!adminRequest && authenticateView)) {
			if (user == null) {
				// challenge client to provide credentials. send 401.
				if (GitBlit.isDebugMode()) {
					logger.info(MessageFormat.format("RPC: CHALLENGE {0}", fullUrl));

				}
				httpResponse.setHeader("WWW-Authenticate", CHALLENGE);
				httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			} else {
				// check user access for request
				if (user.canAdmin || canAccess(user, requestType)) {
					// authenticated request permitted.
					// pass processing to the restricted servlet.
					newSession(authenticatedRequest, httpResponse);
					logger.info(MessageFormat.format("RPC: {0} ({1}) authenticated", fullUrl,
							HttpServletResponse.SC_CONTINUE));
					chain.doFilter(authenticatedRequest, httpResponse);
					return;
				}
				// valid user, but not for requested access. send 403.
				if (GitBlit.isDebugMode()) {
					logger.info(MessageFormat.format("RPC: {0} forbidden to access {1}",
							user.username, fullUrl));
				}
				httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
				return;
			}
		}

		if (GitBlit.isDebugMode()) {
			logger.info(MessageFormat.format("RPC: {0} ({1}) unauthenticated", fullUrl,
					HttpServletResponse.SC_CONTINUE));
		}
		// unauthenticated request permitted.
		// pass processing to the restricted servlet.
		chain.doFilter(authenticatedRequest, httpResponse);
	}

	private boolean canAccess(UserModel user, RpcRequest requestType) {
		switch (requestType) {
		case LIST_REPOSITORIES:
			return true;
		default:
			return user.canAdmin;
		}
	}
}