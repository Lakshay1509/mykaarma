package com.mykaarma.reminders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Caps request bodies at 8 KB (section 5). A filter runs before anything reads the body;
// without it Jackson loads a body of any size and ignores the unknown fields.
// Only Content-Length is checked, so a chunked body gets through. The ingress proxy
// (nginx client_max_body_size) should enforce the cap once there is one.
@Component
class RequestSizeLimitFilter extends OncePerRequestFilter {

	private static final long MAX_BYTES = 8 * 1024;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > MAX_BYTES) {
			response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
			return;
		}
		chain.doFilter(request, response);
	}

}
