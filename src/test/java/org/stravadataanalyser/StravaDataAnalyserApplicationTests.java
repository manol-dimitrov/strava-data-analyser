package org.stravadataanalyser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.stravadataanalyser.configuration.ApplicationConfigurationTest;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(ApplicationConfigurationTest.class)
@WebAppConfiguration
public class StravaDataAnalyserApplicationTests {

	@Test
	public void contextLoads() {
	}

}
