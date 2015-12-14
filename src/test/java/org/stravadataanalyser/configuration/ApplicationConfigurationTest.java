package org.stravadataanalyser.configuration;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

/**
 * Created by Manol on 13/12/2015.
 */
@Configuration
@ComponentScan(basePackages = "org.stravadataanalyser")
@TestPropertySource(value = "classpath:test-application.properties")
public class ApplicationConfigurationTest {
}
