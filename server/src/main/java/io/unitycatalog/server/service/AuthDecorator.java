package io.unitycatalog.server.service;

import static io.unitycatalog.server.security.SecurityContext.Issuers.INTERNAL;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.netty.util.AttributeKey;
import io.unitycatalog.control.model.User;
import io.unitycatalog.server.exception.AuthorizationException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.security.JwtClaim;
import io.unitycatalog.server.utils.JwksOperations;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple JWT access-token authorization decorator.
 *
 * <p>This decorator implements simple authorization. It requires an Authorization header in the
 * request with a Bearer token. The token is verified to be from the "internal" issuer and the token
 * signature is checked against the internal issuer key. If all these checks pass, the request is
 * allowed to continue.
 *
 * <p>The decoded token is also added to the request attributes so it can be referenced by the
 * request if needed.
 */
public class AuthDecorator implements DecoratingHttpServiceFunction {

  private static final Logger LOGGER = LoggerFactory.getLogger(AuthDecorator.class);
  private static final UserRepository USER_REPOSITORY = UserRepository.getInstance();

  private static final String UC_TOKEN_KEY = "UC_TOKEN";

  private static final String BEARER_PREFIX = "Bearer ";
  private static final Pattern UC_TOKEN_KEY_PATTERN = Pattern.compile("UC_TOKEN=(\\S+)");

  public static final AttributeKey<DecodedJWT> DECODED_JWT_ATTR =
      AttributeKey.valueOf(DecodedJWT.class, "DECODED_JWT_ATTR");

  @Override
  public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
      throws Exception {
    LOGGER.debug("AuthDecorator checking {}", req.path());

    String bearerToken = req.headers().get(HttpHeaderNames.AUTHORIZATION);
    String cookieToken = req.headers().get(HttpHeaderNames.COOKIE);

    if (bearerToken == null && cookieToken == null) {
      throw new AuthorizationException(ErrorCode.UNAUTHENTICATED, "No authorization found.");
    }

    DecodedJWT decodedJWT =
        JWT.decode(getAccessTokenFromCookieOrAuthHeader(bearerToken, cookieToken));

    JwksOperations jwksOperations = new JwksOperations();

    String issuer = decodedJWT.getClaim(JwtClaim.ISSUER.key()).asString();
    String keyId = decodedJWT.getHeaderClaim(JwtClaim.KEY_ID.key()).asString();

    LOGGER.debug("Validating access-token for issuer: {}", issuer);

    if (!issuer.equals(INTERNAL)) {
      throw new AuthorizationException(ErrorCode.PERMISSION_DENIED, "Invalid access token.");
    }

    JWTVerifier jwtVerifier = jwksOperations.verifierForIssuerAndKey(issuer, keyId);
    decodedJWT = jwtVerifier.verify(decodedJWT);

    User user;
    try {
      user = USER_REPOSITORY.getUserByEmail(decodedJWT.getClaim(JwtClaim.SUBJECT.key()).asString());
    } catch (Exception e) {
      user = null;
    }
    if (user == null || user.getState() != User.StateEnum.ENABLED) {
      throw new AuthorizationException(ErrorCode.PERMISSION_DENIED, "User not allowed.");
    }

    LOGGER.debug("Access allowed for subject: {}", decodedJWT.getClaim(JwtClaim.SUBJECT.key()));

    ctx.setAttr(DECODED_JWT_ATTR, decodedJWT);

    return delegate.serve(ctx, req);
  }

  private String getAccessTokenFromCookieOrAuthHeader(String bearerToken, String cookieToken) {
    if (bearerToken != null && bearerToken.startsWith(BEARER_PREFIX)) {
      return bearerToken.substring(BEARER_PREFIX.length());
    }
    if (cookieToken != null && cookieToken.contains(UC_TOKEN_KEY)) {
      LOGGER.debug("Getting Access token From the cookie");
      Matcher matcher = UC_TOKEN_KEY_PATTERN.matcher(cookieToken);
      if (matcher.find()) {
        return matcher.group(1);
      }
    }
    throw new AuthorizationException(ErrorCode.UNAUTHENTICATED, "No authorization found.");
  }
}
