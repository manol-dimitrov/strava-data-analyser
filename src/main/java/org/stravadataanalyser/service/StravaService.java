package org.stravadataanalyser.service;

import javastrava.api.v3.model.StravaActivity;
import javastrava.api.v3.model.StravaAthlete;
import javastrava.api.v3.rest.API;
import javastrava.api.v3.rest.async.StravaAPIFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.stravadataanalyser.authentication.OauthClient;

/**
 * Created by Manol on 12/12/2015.
 */
@Service
public class StravaService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private OauthClient client;

    private API stravaApi;

    public StravaService(API stravaApi) {
        stravaApi = new API(client.getToken());
    }

    public StravaActivity getActivity(Integer acitvityId) {
        StravaAPIFuture<StravaActivity> future = stravaApi.getActivityAsync(acitvityId, true);
        return future.get();
    }

    public StravaAthlete getAthelete(Integer athleteId) {
        StravaAPIFuture<StravaAthlete> future = stravaApi.getAthleteAsync(athleteId);
        return future.get();
    }
}
