package com.pushengage.pushengage.iam.display;

import androidx.annotation.RestrictTo;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.os.Looper;

import com.pushengage.pushengage.helper.PELogger;
import com.pushengage.pushengage.iam.action.IAMActionHandler;
import com.pushengage.pushengage.iam.action.IAMCustomActionHandler;
import com.pushengage.pushengage.iam.analytics.IAMAnalyticsManager;
import com.pushengage.pushengage.iam.model.IAMAction;
import com.pushengage.pushengage.iam.model.IAMMessage;
import com.pushengage.pushengage.iam.repository.IAMRepository;
import kotlin.Unit;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

import com.pushengage.pushengage.iam.action.IAMPermissionResultCallback;

/**
 * Implementation of the IAMDisplayManager interface.
 * Handles the display of in-app messages using WebView container.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class IAMDisplayManagerImpl implements IAMDisplayManager {

    private static final String TAG = "IAMDisplayManagerImpl";
    private volatile Context context;
    private final IAMRepository repository;

    // Volatile because the display session is inspected from more than one thread.
    // The controller dispatches display work to a background executor and asks for
    // getDisplayingMessageId() from there, while the view work itself, the gesture
    // and dismissal callbacks, and the lifecycle callbacks all run on the main
    // thread. isDisplaying in particular was written inside synchronized
    // (displayLock) but read outside it in three places, so those reads had no
    // happens-before edge and could see a stale value.
    //
    // Visibility only. A check-then-act over these is still not atomic, which is
    // what displayLock is for — see dismissCurrentMessage, whose guard has to be
    // indivisible for dismissal to settle exactly once.
    private volatile IAMWebViewContainer webViewContainer;
    private volatile IAMMessage currentMessage = null;
    private volatile WeakReference<Activity> currentActivity = null;
    private volatile IAMActionHandler actionHandler;
    private volatile IAMCustomActionHandler customActionHandler;
    private volatile boolean isDisplaying = false;
    private volatile boolean isPaused = false;
    private volatile boolean isAnimatingDismissal = false;
    private volatile boolean isHandlingConfigChange = false;

    private final IAMAnalyticsManager analyticsManager;

    // Pending auto-dismiss timer. Tracked so it can be cancelled when the
    // message ends early — a live timer from message A must never fire into
    // message B's display session, and the pending runnable pins the posting
    // activity's decor view until it runs.
    private android.view.View autoDismissAnchor = null;
    private Runnable autoDismissRunnable = null;

    // Add message completion listener. Volatile: set from whichever thread
    // dispatched the display, consumed on the main thread when the message ends.
    private volatile Runnable messageCompletionListener = null;

    // Add new fields for display record tracking
    private volatile long currentDisplayRecordId = -1;
    private final Object displayLock = new Object();

    public IAMDisplayManagerImpl(Context context, IAMRepository repository) {
        this(context, repository, null);
    }

    /**
     * Injection seam for tests (package-private): pass an
     * {@code analyticsManager} to observe/stub analytics interactions.
     */
    IAMDisplayManagerImpl(Context context, IAMRepository repository, IAMAnalyticsManager analyticsManager) {
        updateContext(context);
        this.repository = repository;
        // this.context — the application context resolved by updateContext —
        // NOT the constructor argument, which may be an Activity the manager
        // would then outlive.
        this.analyticsManager = analyticsManager != null
                ? analyticsManager
                : new IAMAnalyticsManager(this.context, repository);
    }

    public void updateContext(Context newContext) {
        if (newContext == null) {
            PELogger.error("Context cannot be null", new IllegalArgumentException());
            return;
        }

        Context applicationContext = newContext.getApplicationContext();
        if (applicationContext != null) {
            this.context = applicationContext;
            // Deliberately NOT pushed into the container. The container is built
            // on the Activity hosting it and is rebound to its next host by
            // handleConfigurationChange. Handing it the application context here
            // made it rebuild its WebView on every activity change — destroying
            // the one holding the message before the re-attach check had even
            // run — and a WebView built on the application context has no window
            // to size against anyway.
        } else {
            PELogger.error("Failed to get application context", new IllegalStateException());
        }
    }

    /**
     * Sets a handler for custom actions
     */
    public void setCustomActionHandler(IAMCustomActionHandler handler) {
        this.customActionHandler = handler;
        // Will be set on actionHandler when it's created
    }

    /**
     * Sets a listener to be called when a message is dismissed
     * 
     * @param listener The listener to be called
     */
    public void setMessageCompletionListener(Runnable listener) {
        this.messageCompletionListener = listener;
    }

    @Override
    public void initialize() {
        PELogger.debug("IAM Display Manager initialized");
    }

    /**
     * Handles activity configuration changes
     * 
     * @param newActivity The new activity instance after configuration change
     */
    public void handleConfigurationChange(Activity newActivity, boolean isConfigChange) {
        if (webViewContainer == null || currentMessage == null) {
            return;
        }

        isHandlingConfigChange = isConfigChange;
        currentActivity = new WeakReference<>(newActivity);

        // Detach from old activity and reattach to new one
        ViewGroup parent = (ViewGroup) webViewContainer.getParent();
        if (parent != null) {
            parent.removeView(webViewContainer);
        }

        // Update the container with new activity context
        webViewContainer.updateContext(newActivity);

        // Re-display the message without recording impression or starting auto-dismiss
        displayMessageInternal(currentMessage, true);

        isHandlingConfigChange = false;
    }

    @Override
    public boolean displayMessage(IAMMessage message, Activity activity) {
        if (message == null) {
            PELogger.debug("displayMessage: Cannot display null message");
            return false;
        }

        if (isDisplaying) {
            PELogger.debug("displayMessage: Already displaying a message, can't display " + message.id);
            return false;
        }

        if (isPaused) {
            PELogger.debug("displayMessage: Display manager is paused, can't display " + message.id);
            return false;
        }

        PELogger.debug("displayMessage: Setting up to display message " + message.id +
                " with position " + message.position + " in " + activity.getClass().getSimpleName());

        currentActivity = new WeakReference<>(activity);

        // Ensure we run on the UI thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            PELogger.debug("displayMessage: Already on main thread, displaying message " + message.id);
            return displayMessageInternal(message, false);
        } else {
            // Post the display to the main thread and return true, meaning
            // "display scheduled". Returning the runnable's (not-yet-run) result
            // here reported false to the queue, which marked the message complete
            // and advanced — so a second queued message displayed over the first.
            // If the posted display fails, fire the completion listener so the
            // queue advances past the dead message instead of stalling.
            PELogger.debug("displayMessage: Not on main thread, posting to main thread for message " + message.id);
            activity.runOnUiThread(() -> {
                boolean ok = displayMessageInternal(message, false);
                PELogger.debug("displayMessage: Result of displaying message " + message.id + " is " + ok);
                if (!ok && !isDisplaying) {
                    Runnable listener = messageCompletionListener;
                    messageCompletionListener = null;
                    if (listener != null) {
                        PELogger.debug("displayMessage: Scheduled display failed for " + message.id
                                + ", advancing queue");
                        listener.run();
                    }
                }
            });
            return true;
        }
    }

    /**
     * Sets the current display record ID for the active message
     * 
     * @param recordId The record ID from the controller
     */
    public void setCurrentDisplayRecordId(long recordId) {
        synchronized (displayLock) {
            this.currentDisplayRecordId = recordId;
        }
    }

    /**
     * Checks if a message is currently being displayed
     * 
     * @return True if a message is currently being displayed
     */
    public boolean isDisplaying() {
        synchronized (displayLock) {
            return isDisplaying && currentMessage != null;
        }
    }

    /**
     * The id of the message currently on screen, or null when nothing displays.
     * Lets the controller treat a re-display request for the SAME message (e.g.
     * after a rotation re-attach) as a no-op success instead of a failure that
     * would falsely complete the message and advance the queue.
     */
    public String getDisplayingMessageId() {
        synchronized (displayLock) {
            return (isDisplaying && currentMessage != null) ? currentMessage.id : null;
        }
    }

    /**
     * Moves the displayed message into {@code activity} unless that activity already
     * hosts it. Covers both ways the user can end up on a different activity while a
     * message is up: a rotation, which destroyed the previous host, and an ordinary
     * forward navigation, which merely stopped it. No-ops when {@code activity} is
     * already the host. Never records an impression.
     */
    public void reattachIfNotHostedBy(Activity activity) {
        // View work. Two callers reach here from the background executor (the
        // queue's onMessageAvailable, and updateCurrentActivity's pending branch);
        // the old window-token check happened to return early on that thread, the
        // hosting check below does not.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> reattachIfNotHostedBy(activity));
            return;
        }
        synchronized (displayLock) {
            if (webViewContainer == null || currentMessage == null) {
                return;
            }
            // Never trade a live host for one that cannot host. handleConfigurationChange
            // detaches BEFORE displayMessageInternal gets to refuse a finishing or
            // destroyed activity, so the container would be left with no parent at all
            // — the SDK's own notification trampoline resumes already finishing. And a
            // message mid-exit-animation is on its way out; moving it would re-display
            // it into the new host.
            if (isActivityGone(activity) || isAnimatingDismissal) {
                return;
            }
            // The question is whether THIS activity hosts the container — not
            // whether the container still has a window. A forward A→B navigation
            // leaves A stopped but alive, so the container's window token stays
            // valid while it hangs in an activity nobody can see. Keyed on the
            // token, this returned early and the message was orphaned on A:
            // nothing on screen, isDisplaying still true, every later message
            // queued behind it, and A's whole screen input-dead on return because
            // the container swallows outside taps. A container whose host was
            // destroyed (rotation) has no parent at all, so the same test covers
            // the detached case too.
            ViewGroup target = activity.findViewById(android.R.id.content);
            if (target == null || webViewContainer.getParent() == target) {
                return;
            }
        }
        PELogger.debug("reattachIfNotHostedBy: container is not hosted by "
                + activity.getClass().getSimpleName() + " — re-attaching");
        handleConfigurationChange(activity, true);
    }

    /**
     * Whether {@code activity} can no longer host a message.
     *
     * <p>{@code isDestroyed()} arrived in API 17 and the SDK supports 16, hence the
     * version guard; on 16 a finishing activity is the only signal available.
     */
    public static boolean isActivityGone(Activity activity) {
        if (activity.isFinishing()) {
            return true;
        }
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1
                && activity.isDestroyed();
    }

    private boolean displayMessageInternal(IAMMessage message, boolean isConfigChange) {
        if (message == null) {
            PELogger.debug("displayMessageInternal: Message is null");
            return false;
        }

        Activity activity = currentActivity != null ? currentActivity.get() : null;
        if (activity == null) {
            PELogger.debug("displayMessageInternal: Activity reference is null or cleared for message " + message.id);
            return false;
        }

        // The activity is captured when the message is dequeued, but the display
        // work runs later on a background executor and is then posted back to the
        // main thread, so by the time we get here it may be gone — the user
        // navigated away, or a configuration change destroyed it.
        //
        // This has to be checked explicitly, because nothing downstream fails on a
        // dead activity. findViewById(android.R.id.content) keeps returning the
        // (now detached) content view rather than null, so the guard below does not
        // fire, the WebView is added to a view tree nobody is looking at, and
        // isDisplaying is set. The message then counts as showing while being
        // invisible: with no displayDuration nothing ever dismisses it, every later
        // message is stuck behind it in the queue, and the activity's whole view
        // tree is retained through the container.
        //
        // Both flags are needed. A finishing activity is not yet destroyed, and a
        // destroyed one does not necessarily report isFinishing.
        if (isActivityGone(activity)) {
            PELogger.debug("displayMessageInternal: activity " + activity.getClass().getSimpleName()
                    + " is finishing or destroyed — not displaying " + message.id);
            return false;
        }

        // Early return if already displaying this message — EXCEPT during a
        // config-change re-attach, which is by definition a same-message
        // re-display into the new activity (the container was just detached
        // from the destroyed one). Returning here left the message detached
        // and invisible after rotation, with the queue stuck behind it.
        synchronized (displayLock) {
            if (!isConfigChange && isDisplaying && currentMessage != null
                    && currentMessage.id.equals(message.id)) {
                PELogger.debug("displayMessageInternal: Already displaying this message: " + message.id);
                return false;
            }
        }

        PELogger.debug("displayMessageInternal: Setting up WebView container for message " + message.id +
                " with position " + message.position);

        // Anchor the overlay to the activity content view (android.R.id.content)
        // — the app's usable content region, below the status bar and above the
        // navigation bar. FULL messages fill exactly this region, so the
        // takeover has no status-bar overlap and no navigation-bar gap, and
        // TOP/BOTTOM banners sit flush against the content edges. This is the
        // sensible cross-host definition of "full screen": IAM fills the same
        // area the app itself draws into, regardless of host window config.
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) {
            PELogger.debug("displayMessageInternal: Could not find content view in activity");
            return false;
        }

        if (webViewContainer == null) {
            PELogger.debug("displayMessageInternal: Creating new WebView container");
            try {
                webViewContainer = new IAMWebViewContainer(activity);
            } catch (Exception | LinkageError e) {
                // Devices whose WebView provider is missing, disabled or mid-update throw
                // from the WebView constructor. Skip the message rather than crash the host;
                // returning false lets the queue mark it complete and move on.
                PELogger.error("displayMessageInternal: WebView unavailable, skipping message " + message.id, e);
                return false;
            }
        }

        // (Re)wire the listeners on EVERY display pass, not only on container
        // creation: the container nulls them in cleanup() when its window
        // detaches (the old activity being destroyed during rotation), so a
        // config-change re-attach without re-wiring produced a visible message
        // whose buttons routed nowhere ("No parent action handler found") —
        // undismissable, blocking the queue.
        webViewContainer.setDismissListener(() -> {
            PELogger.debug("displayMessageInternal: Dismiss listener triggered");
            dismissCurrentMessage();
            return Unit.INSTANCE;
        });
        webViewContainer.setActionListener(actionId -> {
            PELogger.debug("displayMessageInternal: Action listener triggered: " + actionId);
            handleAction(actionId);
            return Unit.INSTANCE;
        });

        // Set up the action handler. Recreated on config change too — the old
        // instance holds the DESTROYED activity, so permission requests and
        // URL intents after a rotation would target a dead activity.
        if (actionHandler == null || isConfigChange) {
            PELogger.debug("displayMessageInternal: Creating action handler");
            actionHandler = new IAMActionHandler(activity);
            if (customActionHandler != null) {
                actionHandler.setCustomActionHandler(customActionHandler);
            }
            // Informational only: the SDK's self-contained permission flow
            // (PEPermissionFragment / PEPermissionHelperActivity) receives the
            // system result itself and already subscribes on grant.
            actionHandler.setPermissionResultCallback(new IAMPermissionResultCallback() {
                @Override
                public void onPermissionResult(int requestCode, boolean granted) {
                    PELogger.debug("Permission result received in IAM: " + (granted ? "granted" : "denied"));
                }
            });
        }

        // Arm the entry animation before loading content so the card stays
        // hidden until it is sized and painted, then animates in. Skipped on
        // configuration changes — a re-displayed message must appear instantly.
        if (!isConfigChange) {
            final IAMWebViewContainer animatingContainer = webViewContainer;
            webViewContainer.armEntryAnimation(() -> {
                IAMAnimationUtil.INSTANCE.animateEntry(
                        animatingContainer.animationTarget(),
                        message.position,
                        () -> Unit.INSTANCE);
                return Unit.INSTANCE;
            });
        }

        // The campaign's own instruction: false means it may be answered only by its
        // own buttons, so no outside tap or fling dismisses it. Set on every display
        // pass, like the listeners above, since a re-attach recreates the gesture
        // handler.
        webViewContainer.setDismissOnTap(message.shouldDismissOnTap);

        // Load the message content
        PELogger.debug("displayMessageInternal: Loading HTML content for message " + message.id);
        webViewContainer.loadContent(message.htmlContent);

        // Update container with message position - we will pass 0 as the initial
        // height,
        // and it will be updated after the content is fully loaded and measured by the
        // WebView
        webViewContainer.updateLayout(message.position, 0);

        // Add to root view if not already attached
        if (webViewContainer.getParent() == null) {
            PELogger.debug("displayMessageInternal: Adding WebView container to activity root view");

            // Set proper FrameLayout parameters for the container
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);

            rootView.addView(webViewContainer, params);
            PELogger.debug("displayMessageInternal: WebView container added with MATCH_PARENT dimensions");
        } else {
            PELogger.debug("displayMessageInternal: WebView container already in view hierarchy");
        }

        // Only update state if not handling config change
        if (!isConfigChange) {
            synchronized (displayLock) {
                // Store current message
                currentMessage = message;
                isDisplaying = true;

                // Set up auto-dismiss if needed
                if (message.displayDuration > 0) {
                    PELogger.debug("displayMessageInternal: Setting up auto-dismiss for " + message.displayDuration
                            + " seconds");
                    cancelAutoDismiss();
                    final String autoDismissMessageId = message.id;
                    autoDismissAnchor = activity.getWindow().getDecorView();
                    autoDismissRunnable = () -> {
                        // Guard by message id as well as cancellation: only the
                        // message that scheduled this timer may be auto-dismissed.
                        synchronized (displayLock) {
                            if (currentMessage == null || !currentMessage.id.equals(autoDismissMessageId)) {
                                return;
                            }
                        }
                        dismissCurrentMessage();
                    };
                    autoDismissAnchor.postDelayed(autoDismissRunnable, message.displayDuration * 1000L);
                }
            }
        } else {
            PELogger.debug(
                    "displayMessageInternal: Configuration change - not recording display for message " + message.id);
            synchronized (displayLock) {
                // Just update the current message reference
                currentMessage = message;
                isDisplaying = true;
            }
        }

        return true;
    }

    /**
     * Cancels a pending auto-dismiss timer and releases the decor view it was
     * posted to. Must be called whenever the current display session ends.
     */
    private void cancelAutoDismiss() {
        if (autoDismissAnchor != null && autoDismissRunnable != null) {
            autoDismissAnchor.removeCallbacks(autoDismissRunnable);
        }
        autoDismissAnchor = null;
        autoDismissRunnable = null;
    }

    private void cleanup() {
        synchronized (displayLock) {
            cancelAutoDismiss();
            if (webViewContainer != null) {
                ViewGroup parent = (ViewGroup) webViewContainer.getParent();
                if (parent != null) {
                    parent.removeView(webViewContainer);
                }
                if (webViewContainer != null) {
                    webViewContainer.cleanup();
                }
                webViewContainer = null;
            }

            actionHandler = null;
            currentMessage = null;
            currentActivity = null;
            isHandlingConfigChange = false;
            isDisplaying = false;
            currentDisplayRecordId = -1;
        }
    }

    @Override
    public void shutdown() {
        cleanup();
    }

    /**
     * Dismisses the current message: stops the auto-dismiss timer, animates out and
     * releases the container. The display record itself needs no update — it already
     * carries the impression, and nothing about closing changes what is reported.
     *
     * <p>The guard makes this idempotent, so the several ways a message can close
     * (close button, tap-to-dismiss gesture, fling, auto-dismiss timer, a handled
     * button action, backgrounding) each settle the session exactly once.
     */
    @Override
    public void dismissCurrentMessage() {
        synchronized (displayLock) {
            if (!isDisplaying || currentMessage == null || webViewContainer == null || isAnimatingDismissal) {
                return;
            }

            isAnimatingDismissal = true;

            // The session is ending — a still-pending auto-dismiss timer must
            // not fire into whatever message displays next.
            cancelAutoDismiss();

            // Reset display record ID
            currentDisplayRecordId = -1;

            // Animate exit based on message position. Animate the sized WebView
            // card rather than the full-screen container — smaller hardware-layer
            // texture, and slide distances match the card height.
            IAMAnimationUtil.INSTANCE.animateExit(
                    webViewContainer.animationTarget(),
                    currentMessage.position,
                    () -> {
                        // Cleanup after animation
                        if (webViewContainer != null && webViewContainer.getParent() != null) {
                            ((ViewGroup) webViewContainer.getParent()).removeView(webViewContainer);
                        }
                        if (webViewContainer != null) {
                            webViewContainer.cleanup();
                        }
                        webViewContainer = null;

                        // Reset state
                        isDisplaying = false;
                        isAnimatingDismissal = false;

                        // Store references before cleanup
                        final IAMMessage dismissedMessage = currentMessage;
                        final Runnable completionListener = messageCompletionListener;

                        // Reset state
                        currentMessage = null;
                        currentActivity = null;
                        actionHandler = null;
                        messageCompletionListener = null;

                        // Notify completion listener if set
                        if (completionListener != null) {
                            PELogger.debug("Notifying message completion listener for message: " +
                                    (dismissedMessage != null ? dismissedMessage.id : "unknown"));
                            completionListener.run();
                        }

                        return Unit.INSTANCE;
                    });
        }
    }

    /**
     * Handles actions triggered from the in-app message
     * 
     * @param actionId The identifier of the action to handle
     */
    @Override
    public void handleAction(String actionId) {
        if (currentMessage == null || actionHandler == null) {
            PELogger.debug("handleAction: Cannot handle action, currentMessage or actionHandler is null");
            return;
        }

        PELogger.debug("handleAction: Handling action: " + actionId + " for message: " + currentMessage.id);
        final String messageId = currentMessage.id;

        // Ensure we're on the UI thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handleActionInternal(actionId, messageId);
        } else {
            // Post to UI thread if needed
            PELogger.debug("handleAction: Not on UI thread, posting to UI thread");
            if (currentActivity != null && currentActivity.get() != null) {
                currentActivity.get().runOnUiThread(() -> {
                    PELogger.debug("handleAction: Now on UI thread, handling action: " + actionId);
                    handleActionInternal(actionId, messageId);
                });
            } else {
                PELogger.error("handleAction: Cannot post to UI thread, activity reference is null"
                        + " (action " + actionId + " on message " + messageId + ")");
            }
        }
    }

    private void handleActionInternal(String actionId, String messageId) {
        try {
            // Parse actions JSON
            JSONObject actionsJson = new JSONObject(currentMessage.actionsJson);
            PELogger.debug("handleActionInternal: Action JSON parsed, checking for action: " + actionId);

            if (actionsJson.has(actionId)) {
                JSONObject actionJson = actionsJson.getJSONObject(actionId);
                IAMAction action = IAMActionHandler.createActionFromJson(actionJson);
                PELogger.debug(
                        "handleActionInternal: Created action object: " + (action != null ? action.getType() : "null"));

                if (action != null) {
                    // Report the click on the tap itself ("sent per button tap"
                    // semantics), independent of whether the action handler
                    // succeeds — e.g. a custom action with no app listener is still
                    // a user click. Decoupled from the display-record/impression
                    // flow because the impression may already be synced by the time
                    // the tap happens. Matches iOS (trackButtonClick fires before
                    // the action is forwarded).
                    // btn_id = actions-map key, btn_text = label, btn_type = type.
                    // btn_type carries the lowercase wire value (not the enum
                    // constant name), so outbound analytics uses the same
                    // vocabulary as the inbound actions[].type payload.
                    analyticsManager.recordClick(
                            messageId,
                            actionId,
                            action.getLabel(),
                            action.getType().wireValue);

                    boolean handled = actionHandler.handleAction(action, actionId);
                    PELogger.debug("handleActionInternal: Action handled: " + handled);

                    // Every tap on a RECOGNISED button dismisses the message —
                    // including CUSTOM (the app has been notified; a tapped button
                    // should close the card). Matches iOS, which dismisses after
                    // every non-dismiss action.
                    //
                    // Deliberately not gated on `handled`. An action that could not
                    // execute (e.g. an open_url with a non-http scheme, or a custom
                    // action with no registered listener) still came from a real
                    // button press, and leaving the card up strands the user: a
                    // campaign whose only button has a bad URL, no close block and
                    // displayDuration = 0 cannot be closed at all, because the
                    // container consumes every touch outside it.
                    //
                    // Unrecognised action ids and unparseable action types are
                    // handled by the surrounding branches and still leave the
                    // message up — those indicate authoring bugs, and closing on an
                    // unknown key would hide them.
                    PELogger.debug("handleActionInternal: Auto-dismissing after action completion"
                            + (handled ? "" : " (action was not executed)"));
                    dismissCurrentMessage();
                }
            } else {
                PELogger.debug("handleActionInternal: Action " + actionId + " not found in actions JSON");
            }
        } catch (JSONException e) {
            PELogger.error("handleActionInternal: Error parsing actions JSON for action " + actionId
                    + " on message " + messageId, e);
        }
    }

    @Override
    public void pause() {
        isPaused = true;
        PELogger.debug("IAM Display Manager paused");

        if (isDisplaying && !isHandlingConfigChange) {
            // Backgrounding ends the display. Guarded against a config change so a
            // rotation, which also pauses the activity, does not close the message.
            PELogger.debug("Dismissing displayed message: app backgrounded");
            dismissCurrentMessage();
        }
    }

    @Override
    public void resume() {
        isPaused = false;
        PELogger.debug("IAM Display Manager resumed");
    }
}