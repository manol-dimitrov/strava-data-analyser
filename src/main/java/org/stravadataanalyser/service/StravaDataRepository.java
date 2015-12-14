package org.stravadataanalyser.service;

import javastrava.api.v3.model.StravaActivity;
import javastrava.api.v3.model.StravaAthlete;
import javastrava.api.v3.rest.API;
import javastrava.api.v3.rest.async.StravaAPIFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.stravadataanalyser.authentication.OauthClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * Created by Manol on 12/12/2015.
 */
@Component
public class StravaDataRepository {

    private OauthClient client;

    private API stravaApi;

    @Autowired
    public StravaDataRepository(OauthClient client) {
        this.client = client;
        stravaApi = new API(this.client.getToken());
    }

    public StravaActivity getActivity(Integer acitvityId) {
        StravaAPIFuture<StravaActivity> future = stravaApi.getActivityAsync(acitvityId, true);
        return future.get();
    }

    public List<StravaActivity> getAllActivitiesForAthelete(LocalDate from, LocalDate to) {
        long fromEpoch = from.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long toEpoch = to.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        StravaAPIFuture<StravaActivity[]> future = stravaApi.listAuthenticatedAthleteActivitiesAsync((int) fromEpoch, (int) toEpoch, 1, 100);
        return Arrays.asList(future.get());
    }

    public StravaAthlete getAthelete(Integer athleteId) {
        StravaAPIFuture<StravaAthlete> future = stravaApi.getAthleteAsync(athleteId);
        return future.get();
    }
}
