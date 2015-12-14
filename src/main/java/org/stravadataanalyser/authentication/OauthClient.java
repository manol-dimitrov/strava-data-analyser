package org.stravadataanalyser.authentication;

import javastrava.api.v3.auth.AuthorisationService;
import javastrava.api.v3.auth.impl.retrofit.AuthorisationServiceImpl;
import javastrava.api.v3.auth.model.Token;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Created by Manol on 12/12/2015.
 */
@Component
public class OauthClient {

    private AuthorisationService service;
    private Token token;

    private String clientSecret;
    private String applicationClientId;
    private String code;

    @Autowired
    public OauthClient(@Value(value = "${strava.client-secret}") String clientSecret,
                       @Value(value = "${strava.application-client-id}") String applicationClientId,
                       @Value(value = "${strava.code}") String code) {
        this.clientSecret = clientSecret;
        this.applicationClientId = applicationClientId;
        this.code = code;
    }

    public Token getToken() {
        service = new AuthorisationServiceImpl();
        token = service.tokenExchange(Integer.valueOf(this.applicationClientId), this.clientSecret, this.code);
        return token;
    }
}
