package com.sam.insurance;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

  public static UUID current() {
    return CURRENT.get() == null ? UUID.randomUUID() : CURRENT.get();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    UUID id;
    try {
      id = UUID.fromString(req.getHeader("X-Correlation-ID"));
    } catch (RuntimeException e) {
      id = UUID.randomUUID();
    }
    CURRENT.set(id);
    res.setHeader("X-Correlation-ID", id.toString());
    try {
      chain.doFilter(req, res);
    } finally {
      CURRENT.remove();
    }
  }
}
