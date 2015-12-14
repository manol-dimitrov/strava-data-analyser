package org.stravadataanalyser.service;

import javastrava.api.v3.model.StravaActivity;
import javastrava.api.v3.model.StravaAthlete;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.stravadataanalyser.configuration.ApplicationConfigurationTest;

import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.assertNotNull;

/**
 * Created by Manol on 13/12/2015.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = ApplicationConfigurationTest.class)
@ActiveProfiles("dev")
public class StravaServiceTest {

    @Autowired
    private StravaDataRepository stravaDataRepository;

    @Test
    public void shouldGetAnAthleteSuccessfullyGivenAvalidId() {
        StravaAthlete athlete = stravaDataRepository.getAthelete(4124);
        assertNotNull(athlete);
    }

    @Test
    public void shoudlGetAllActivitiesForCurrentlyAuthenticatedAthlete() {
        LocalDate from = LocalDate.of(2015, 12, 11);
        LocalDate to = LocalDate.of(2015, 12, 12);
        List<StravaActivity> listOfActivities = stravaDataRepository.getAllActivitiesForAthelete(from, to);
        assertNotNull(listOfActivities);
    }
}
