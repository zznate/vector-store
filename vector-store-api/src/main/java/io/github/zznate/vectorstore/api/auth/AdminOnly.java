package io.github.zznate.vectorstore.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource class or method as requiring an admin-scoped API key.
 *
 * <p>When placed on a class, every method on that class requires an admin
 * key. The {@code ApiKeyAuthenticationFilter} rejects non-admin callers
 * with HTTP 403.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AdminOnly {}
