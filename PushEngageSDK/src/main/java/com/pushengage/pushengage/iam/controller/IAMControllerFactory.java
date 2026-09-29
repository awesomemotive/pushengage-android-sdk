package com.pushengage.pushengage.iam.controller;

import androidx.annotation.RestrictTo;

/**
 * Factory class for creating IAMController instances
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class IAMControllerFactory {

    private static IAMController instance;

    /**
     * Gets the singleton instance of IAMController
     * 
     * @return The IAMController instance
     */
    public static synchronized IAMController getInstance() {
        if (instance == null) {
            instance = new IAMControllerImpl();
        }
        return instance;
    }
}