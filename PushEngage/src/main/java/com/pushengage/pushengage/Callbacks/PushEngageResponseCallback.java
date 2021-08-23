package com.pushengage.pushengage.Callbacks;

public interface PushEngageResponseCallback {
    void onSuccess(Object responseObject);

    void onFailure(Integer errorCode, String errorMessage);
}
