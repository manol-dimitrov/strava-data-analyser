package org.stravadataanalyser.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Created by Manol on 12/12/2015.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Activity {
    private String activityType;
    private int duration;
    private double distance;
    private String name;

    public Activity(String activityType, int duration, double distance, String name) {
        this.activityType = activityType;
        this.duration = duration;
        this.distance = distance;
        this.name = name;
    }

    public String getActivityType() {
        return activityType;
    }

    public void setActivityType(String activityType) {
        this.activityType = activityType;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }


}
