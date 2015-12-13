package org.stravadataanalyser.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.stravadataanalyser.domain.Activity;

/**
 * Created by Manol on 12/12/2015.
 */
@Service
public class StravaService {

    @Autowired
    private RestTemplate restTemplate;

    public Activity getActivity() {
        Activity activity = restTemplate.getForObject("strava", Activity.class);
        return activity;
    }
}
