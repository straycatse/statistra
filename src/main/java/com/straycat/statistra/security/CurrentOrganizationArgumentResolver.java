package com.straycat.statistra.security;

import com.straycat.statistra.entity.Organization;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies the authenticated {@link Organization} to controller methods that
 * declare a {@link CurrentOrganization} parameter.
 */
@Component
public class CurrentOrganizationArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentOrganization.class)
                && Organization.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        Object organization = webRequest.getAttribute(
                ApiKeyAuthFilter.ORGANIZATION_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);

        if (organization == null) {
            // The filter runs before any handler, so reaching here means an
            // endpoint outside /api/ asked for an organization. That is a wiring
            // bug, not a client error, so fail loudly rather than return null.
            throw new IllegalStateException(
                    "No authenticated organization on request. Is this endpoint under /api/?");
        }
        return organization;
    }
}
