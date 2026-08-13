package com.straycat.statistra.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the {@link com.straycat.statistra.entity.Organization} that the
 * request's API key resolved to.
 *
 * <p>Using this rather than reading an organization id from the request is what
 * makes cross-tenant access impossible by construction: a controller has no way
 * to name an organization other than the authenticated one.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentOrganization {
}
