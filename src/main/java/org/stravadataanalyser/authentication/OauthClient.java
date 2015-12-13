package org.stravadataanalyser.authentication;

import javastrava.api.v3.auth.AuthorisationService;
import javastrava.api.v3.auth.impl.retrofit.AuthorisationServiceImpl;
import javastrava.api.v3.auth.model.Token;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Created by Manol on 12/12/2015.
 */
@Component
public final class OauthClient {

    private final AuthorisationService service;
    private final Token token;

    @Value("${strava.client-secret}")
    private String clientSecret;

    @Value("${strava.application-client-id}")
    private Integer applicationClientId;

    @Value("${strava.code}")
    private String code;

    public OauthClient() {
        service = new AuthorisationServiceImpl();
        token = service.tokenExchange(applicationClientId, clientSecret, code);
    }

    public Token getToken() {
        return token;
    }
}
